#!/usr/bin/env python3
"""Differentially execute generated integer and control-flow programs after each LLVM pass."""

import argparse
import ctypes
import random
import re
import subprocess
import tempfile
from pathlib import Path


PASSES = ("native-diversify", "native-mix", "native-encode",
          "native-route", "native-compare", "native-dispatch")
FIXTURES = Path(__file__).resolve().parent
OPERATIONS = ("add", "sub", "mul", "and", "or", "xor")
RELATIONS = ("slt", "sle", "sgt", "sge", "ult", "ule", "ugt", "uge")
METHODS = 32


def run(*command, cwd):
    result = subprocess.run(command, cwd=cwd, capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise RuntimeError(f"{command} exited {result.returncode}\n{result.stdout}{result.stderr}")
    return result.stderr


def program(seed):
    rng = random.Random(seed)
    functions = []
    for index in range(METHODS):
        width = (8, 16, 32, 64)[index % 4]
        size = f"i{width}"
        header = f"""
define i64 @method_random_{index}(i64 noundef %left, i64 noundef %right) {{
entry:
  %base = {rng.choice(OPERATIONS)} i64 %left, %right"""
        if width == 64:
            left, right = "%base", "%right"
            result = "%value"
        else:
            header += f"""
  %a = trunc i64 %base to {size}
  %b = trunc i64 %right to {size}"""
            left, right = "%a", "%b"
            result = "%extended"
        case = index // 4
        constant = (0, 1, -1, 3, -6, -(1 << (width - 1)), (1 << (width - 1)) - 1,
                    rng.getrandbits(width))[case]
        # Force constant products in both operand orders and exercise high-bit
        # corrections from the original width down to a one-bit product.
        factors = (left, constant) if case % 2 == 0 else (constant, left)
        header += f"\n  %scaled = mul {size} {factors[0]}, {factors[1]}"
        left = "%scaled"
        if case != 6:
            # Case 6 keeps two uses of the product across the branch.
            lhs, rhs = (left, right) if case % 2 == 0 else (right, left)
            opcode = ("add", "sub", "xor")[case % 3]
            header += f"\n  %combined = {opcode} {size} {lhs}, {rhs}"
            left = "%combined"
        body = f"""
  %condition = icmp {rng.choice(RELATIONS)} i64 %left, %right
  br i1 %condition, label %first, label %second
first:
  %x = {rng.choice(OPERATIONS)} {size} {left}, {right}
  br label %join
second:
  %y = {rng.choice(OPERATIONS)} {size} {right}, {left}
  br label %join
join:
  %initial = phi {size} [ %x, %first ], [ %y, %second ]
  %limit = and i64 %right, 15
  br label %loop
loop:
  %previous = phi {size} [ %initial, %join ], [ %value, %loop ]
  %first_value = phi {size} [ %initial, %join ], [ %second_value, %loop ]
  %second_value = phi {size} [ {right}, %join ], [ %first_value, %loop ]
  %index = phi i64 [ 0, %join ], [ %next, %loop ]
  %shifted = add {size} %previous, %first_value
  %value = {rng.choice(OPERATIONS)} {size} %shifted, %second_value
  %next = add i64 %index, 1
  %continue = icmp ult i64 %next, %limit
  br i1 %continue, label %loop, label %exit
exit:"""
        if width != 64:
            body += f"\n  %extended = zext {size} %value to i64"
        functions.append(header + body + f"""
  %narrow = trunc i64 %left to i8
  %incremented = add nsw i8 %narrow, 1
  %use_increment = icmp ne i8 %narrow, 127
  br label %return
return:
  %safe = select i1 %use_increment, i8 %incremented, i8 0
  %offset = zext i8 %safe to i64
  %output = add i64 {result}, %offset
  ret i64 %output
}}
""")
    return "".join(functions)


def probe_library(library, inputs):
    handle = ctypes.CDLL(str(library))
    values = []
    for index in range(METHODS):
        function = getattr(handle, f"method_random_{index}")
        function.argtypes = (ctypes.c_uint64, ctypes.c_uint64)
        function.restype = ctypes.c_uint64
        values.extend(function(left, right) for left, right in inputs)
    return values


def test_program(args, work, seed):
    work.mkdir()
    tool = args.llvm_bin
    driver = (tool / "opt", f"-load-pass-plugin={args.plugin}", "-verify-each")
    source = work / "program.ll"
    source.write_text(program(seed), encoding="utf-8")
    baseline = work / "baseline.bc"
    run(tool / "opt", "-passes=verify", source, "-o", baseline, cwd=work)

    def link(bitcode):
        obj = bitcode.with_suffix(".o")
        library = bitcode.with_suffix(".so")
        run(tool / "llc", "-O=2", "-filetype=obj", "-relocation-model=pic",
            bitcode, "-o", obj, cwd=work)
        run(args.cc, "-shared", obj, "-o", library, cwd=work)
        return library

    def transform(label, names, rewrite_seed):
        bitcode = work / f"{label}.bc"
        # Keep the composed fixture below the dispatcher's CFG and IR limits.
        caps = {"native-diversify": 2, "native-mix": 4, "native-encode": 4,
                "native-route": 2, "native-compare": 2, "native-dispatch": 64}
        stages = ",".join(f"{name}<seed={rewrite_seed};max={caps[name]};prefix=method_random_>"
                          for name in names)
        report = run(*driver, f"-passes={stages},verify", baseline, "-o", bitcode, cwd=work)
        for name in names:
            if not re.search(rf"(?m)^native-obfuscator: {name} .* transformed=[1-9]\d* ", report):
                raise AssertionError(f"{label} did not exercise {name}\n{report}")
        return bitcode

    boundary = [0, 1, 15, 127, 128, 255, 32767, 32768, 65535, 2147483647,
                2147483648, 4294967295, (1 << 63) - 1, 1 << 63, (1 << 64) - 1]
    inputs = [(x, y) for x in boundary for y in boundary]
    rng = random.Random(seed)
    inputs.extend((rng.getrandbits(64), rng.getrandbits(64)) for _ in range(256))
    expected = probe_library(link(baseline), inputs)
    pipeline = transform("pipeline", PASSES, 42)
    repeated = transform("pipeline-repeat", PASSES, 42)
    if pipeline.read_bytes() != repeated.read_bytes():
        raise AssertionError(f"seed {seed} produced different bitcode on a repeated run")
    other_seed = transform("pipeline-other-seed", PASSES, 43)
    if pipeline.read_bytes() == other_seed.read_bytes():
        raise AssertionError(f"seed {seed} produced identical bitcode with different rewrite seeds")
    isolated_passes = [transform(name, (name,), 42) for name in PASSES]
    mixed = work / "mixed.ll"
    run(tool / "llvm-dis", work / "native-mix.bc", "-o", mixed, cwd=work)
    if "native.mix.rebased" not in mixed.read_text(encoding="utf-8"):
        raise AssertionError("The isolated mixing pass did not move a product correction")
    for variant in (*isolated_passes, pipeline, other_seed):
        observed = probe_library(link(variant), inputs)
        for offset, (left, right) in enumerate(zip(expected, observed, strict=True)):
            if left != right:
                method, sample = divmod(offset, len(inputs))
                raise AssertionError(f"{variant.stem}, seed {seed}, method {method}, "
                                     f"input {inputs[sample]}: baseline={left}, transformed={right}")
    comparisons = METHODS * len(inputs) * (len(isolated_passes) + 2)
    print(f"Program seed {seed}: {comparisons} matching native evaluations over all six passes")


def test_dispatch_amx(args, work):
    bits = work / "dispatch-amx.bc"
    after = work / "dispatch-amx-protected.bc"
    opt = args.llvm_bin / "opt"
    llc = args.llvm_bin / "llc"
    run(opt, "-passes=verify", FIXTURES / "dispatch-amx.ll", "-o", bits, cwd=work)
    log = run(opt, f"-load-pass-plugin={args.plugin}", "-verify-each",
              "-passes=native-dispatch,verify", bits, "-o", after, cwd=work)
    if not re.search(r"function=method_tile candidates=1 eligible=0 transformed=0 .*skipped_type=1", log):
        raise AssertionError(f"Dispatcher accepted an AMX register it cannot spill\n{log}")
    if after.read_bytes() != bits.read_bytes():
        raise AssertionError("Rejected dispatch changed AMX bitcode")
    run(llc, "-mattr=+amx-tile", "-filetype=obj", after, "-o", work / "dispatch-amx.o", cwd=work)
    print("x86 AMX dispatcher rejection produced valid machine code")


def test_dispatch_edges(args, work):
    source = work / "sentinel.ll"
    sentinel = """
define i32 @method_sentinel(i1 %condition) {
entry:
  br i1 %condition, label %yes, label %no
yes:
  ret i32 1
no:
  ret i32 2
}
"""
    long_body = "\n".join(f"block{index}: br label %block{index + 1}" for index in range(32))
    large = f"""
define void @method_over_limit(i1 %condition) {{
entry:
  br i1 %condition, label %block0, label %block32
{long_body}
block32:
  ret void
}}
"""
    source.write_text(sentinel + large, encoding="utf-8")
    output = work / "sentinel-out.ll"
    # This seed generates the uint32_t maximum as the first label for this
    # function name. LLVM 22's DenseSet reserves the value as an empty bucket.
    log = run(args.llvm_bin / "opt", f"-load-pass-plugin={args.plugin}", "-verify-each",
              "-passes=native-dispatch<seed=420152387661141279>,verify",
              "-S", source, "-o", output, cwd=work)
    if "i32 -1, label %dispatch.first" not in output.read_text(encoding="utf-8"):
        raise AssertionError("The dispatcher did not preserve the full range of state labels")
    if not re.search(r"function=method_over_limit candidates=33 eligible=0 transformed=0 .*skipped_size=33", log):
        raise AssertionError(f"Dispatcher did not report the graph size rejection\n{log}")
    print("Dispatcher accepts all 32 bits of a state label and reports its graph size limit")


def test_entry_setup(args, work):
    opt = args.llvm_bin / "opt"
    llc = args.llvm_bin / "llc"
    fixture = FIXTURES / "entry-setup.ll"
    for name in ("native-diversify", "native-route", "native-dispatch"):
        bitcode = work / f"entry-{name}.bc"
        log = run(opt, f"-load-pass-plugin={args.plugin}", "-verify-each",
                  f"-passes={name}<seed=42;max=8>,verify", fixture, "-o", bitcode,
                  cwd=work)
        if name == "native-dispatch":
            expected = (r"function=method_frame_escape candidates=1 eligible=0 transformed=0 "
                        r".*skipped_unsafe_control_flow=1")
            if not re.search(expected, log):
                raise AssertionError(f"Dispatcher accepted an entry-only frame escape\n{log}")
        else:
            methods = ["method_frame_escape", "method_late_alloca"]
            if name == "native-diversify":
                methods.append("method_gcroot")
            for method in methods:
                expected = (rf"function={method} candidates=2 eligible=1 transformed=1 "
                            r".*skipped_unsafe_blocks=1")
                if not re.search(expected, log):
                    raise AssertionError(f"{name} did not preserve entry setup in {method}\n{log}")
        run(llc, "-filetype=obj", bitcode, "-o", bitcode.with_suffix(".obj"), cwd=work)
    print("Windows x64 COFF codegen preserves frame escapes, GC roots, and static entry allocations")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--llvm-bin", required=True, type=lambda value: Path(value).resolve())
    parser.add_argument("--plugin", required=True, type=lambda value: Path(value).resolve())
    parser.add_argument("--cc", default="cc")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as directory:
        work = Path(directory)
        for seed in (1729, 65537):
            test_program(args, work / str(seed), seed)
        test_dispatch_amx(args, work)
        test_dispatch_edges(args, work)
        test_entry_setup(args, work)


if __name__ == "__main__":
    main()

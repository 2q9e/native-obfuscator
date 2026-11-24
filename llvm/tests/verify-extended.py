import argparse
import os
from pathlib import Path
import platform
import re
import subprocess
import tempfile


FIXTURES = Path(__file__).resolve().parent
PASSED = "checked 165825 input pairs across 24 operations, a chain, and a PHI"
UNSAFE_ROUTE = (
    "indirect", "convergent", "convergent_call", "returns_twice_call",
    "personality", "naked", "gc",
)


def run(*command, reject=False):
    result = subprocess.run(command, capture_output=True, text=True)
    if (result.returncode != 0) != reject:
        raise RuntimeError(f"Unexpected exit {result.returncode} for {command}\n"
                           f"{result.stdout}\n{result.stderr}")
    return result.stdout, result.stderr


def function_bodies(ir):
    return {match[1]: match[0] for match in re.finditer(
        r"(?ms)^define [^\n]*?@([\w.]+)\(.*?^}", ir
    )}


def counts(line):
    return {key: int(value) for key, value in re.findall(r"\b(\w+)=(\d+)\b", line)}


def verify_reports(log, pass_name, names):
    prefix = f"native-obfuscator-function: pass={pass_name} function="
    reports = {}
    for line in log.splitlines():
        if line.startswith(prefix):
            name, fields = line[len(prefix):].split(" ", maxsplit=1)
            assert name not in reports, f"duplicate {pass_name} report for {name}"
            reports[name] = counts(fields)
    assert set(reports) == {name for name in names if name.startswith("method_")}
    summary = next(line for line in log.splitlines()
                   if line.startswith(f"native-obfuscator: {pass_name} "))
    totals = counts(summary)
    for field in ("candidates", "eligible", "transformed", "skipped_guard", "skipped_cap",
                  "skipped_flags", "skipped_type", "skipped_undefined", "skipped_unsafe_blocks"):
        if field in totals:
            assert totals[field] == sum(report[field] for report in reports.values()), field
    return reports, totals


def test(args, work):
    def tool(name):
        return args.llvm_bin / (name + (".exe" if os.name == "nt" else ""))

    opt = tool("opt")
    driver = ([args.static_opt] if args.static_opt else
              [opt, f"-load-pass-plugin={args.plugin}", "-verify-each"])
    target = [f"--target={args.target}"] if args.target else []
    triple = [f"-mtriple={args.target}"] if args.target else []

    def transform(pass_name, options, source, output, reject=False):
        choice = f"{pass_name}<{options}>" if options is not None else pass_name
        _, log = run(*driver, f"-passes=verify,{choice},verify", source, "-o", output,
                     reject=reject)
        return log

    def get_bodies(source):
        ir, _ = run(opt, "-passes=verify", "-S", source, "-o", "-")
        return function_bodies(ir)

    eligibility = FIXTURES / "extended-eligibility.ll"
    original = get_bodies(eligibility)
    cfg = FIXTURES / "extended-cfg.ll"
    reference_cfg = work / "reference-cfg.bc"
    run(opt, "-passes=verify", cfg, "-o", reference_cfg)
    fixture_c = FIXTURES / "extended.c"
    reference = work / "reference.bc"
    run(tool("clang"), *target, "-ffreestanding", "-DBUILD_FIXTURE", "-O2",
        "-emit-llvm", "-c", fixture_c, "-o", reference)
    reference_bodies = get_bodies(reference)

    def link(variant, code, control_flow):
        objects = [work / f"{variant}-{name}.o" for name in ("ops", "cfg")]
        for source, obj in zip((code, control_flow), objects):
            run(tool("llc"), "-O=2", "-filetype=obj", "-relocation-model=pic",
                *triple, source, "-o", obj)
        exe = work / (variant + (".exe" if os.name == "nt" else ""))
        run(args.cc, *args.cc_arg, "-O2", fixture_c, *objects, "-o", exe)
        result, _ = run(exe)
        assert result.strip() == PASSED, (variant, result)
        print(f"{variant}: {result.strip()}", flush=True)
        return exe

    reference_exe = link("baseline", reference, reference_cfg)
    for pass_name, default_cap, bound in (("native-mix", 4, 5), ("native-route", 1, 13)):
        output = work / f"{pass_name}-eligibility.bc"
        log = transform(pass_name, "seed=42;max=4;prefix=method_", eligibility, output)
        rejected = get_bodies(output)
        reports, totals = verify_reports(log, pass_name, original)
        assert totals["skipped_prefix_functions"] == 1
        assert totals["skipped_optnone_functions"] == 1

        expected = {"flags": "skipped_flags", "width": "skipped_type",
                    "vector": "skipped_type", "optnone": "skipped_optnone",
                    "naked": "skipped_unsafe_control_flow"}
        if pass_name == "native-mix":
            expected.update({name: "skipped_undefined" for name in ("unknown", "undef", "poison")})
            expected["disjoint"] = "skipped_flags"
        else:
            expected.update({name: "skipped_unsafe_control_flow" for name in UNSAFE_ROUTE})
            expected.update(unknown="skipped_guard", constant="skipped_guard",
                            address_taken="skipped_unsafe_blocks")
        for name, reason in expected.items():
            assert reports[f"method_{name}"][reason] == 1, (pass_name, name, reason)
        excluded = [f"method_{name}" for name in (*expected, "identity", "exact")]
        if pass_name == "native-route":
            excluded.append("method_disjoint")
        for name in (*excluded, "unselected"):
            assert original[name] == rejected[name], (pass_name, name)
        for name in ("method_identity", "method_exact"):
            assert reports[name]["candidates"] == 0
        assert reports["method_frozen"]["transformed"] == 1
        if pass_name == "native-route":
            assert reports["method_undef"]["transformed"] == 1
            assert reports["method_poison"]["transformed"] == 1
            assert "ret i32 %value" in rejected["method_poison"]
            assert rejected["method_poison"].count(", poison") == 2

        default = work / f"{pass_name}-default.bc"
        log = transform(pass_name, None, reference_cfg, default)
        _, totals = verify_reports(log, pass_name, get_bodies(reference_cfg))
        assert totals["max_sites_per_function"] == default_cap and totals["seed"] == 0
        disabled = work / f"{pass_name}-disabled.bc"
        transform(pass_name, "max=0;seed=18446744073709551615;prefix=method_",
                  reference_cfg, disabled)
        assert disabled.read_bytes() == reference_cfg.read_bytes()
        for invalid in ("", "max=-1", "max=4294967296", "seed=18446744073709551616",
                        "max=1;max=2", "prefix=", "prefix=bad-prefix", "unknown=1", "max=1;"):
            log = transform(pass_name, invalid, reference_cfg, work / "invalid.bc", reject=True)
            assert f"invalid {pass_name} options" in log, invalid

        capped = work / f"{pass_name}-cap.bc"
        log = transform(pass_name, "seed=42;max=1;prefix=method_", reference_cfg, capped)
        reports, _ = verify_reports(log, pass_name, get_bodies(reference_cfg))
        assert reports["method_chain"]["eligible"] == 3
        assert reports["method_chain"]["transformed"] == 1
        assert reports["method_chain"]["skipped_cap"] == 2

        seeds = (42, 43, 44, 45) if pass_name == "native-mix" else (42, 43)
        for seed in seeds:
            name = f"{pass_name}-{seed}"
            output = work / f"{name}.bc"
            options = f"seed={seed};max=4;prefix=method_"
            log = transform(pass_name, options, reference, output)
            rewritten = get_bodies(output)
            reports, totals = verify_reports(log, pass_name, rewritten)
            assert totals["transformed"] == (24 if pass_name == "native-mix" else 12)
            for function, before in reference_bodies.items():
                after = rewritten[function]
                if reports[function]["transformed"]:
                    assert before != after, (pass_name, function)
                    growth = len(re.findall(r"(?m)^  \S", after)) - len(re.findall(r"(?m)^  \S", before))
                    assert growth <= bound, (pass_name, function, growth)
                    if pass_name == "native-route":
                        assert after.count(" br i1 ") == 1
                        assert after.count(" phi i") == 1
                        assert len(re.findall(r"(?m)^native\.route\.[\w]+:", after)) == 3
                        assert not re.search(r"\b(load|store|volatile|unreachable)\b", after)
                else:
                    assert before == after, (pass_name, function)

            cfg_output = work / f"{name}-cfg.bc"
            transform(pass_name, options, reference_cfg, cfg_output)
            if pass_name == "native-route":
                cfg_bodies = get_bodies(cfg_output)
                assert cfg_bodies["method_chain"].count(" phi i32 ") == 3
                assert cfg_bodies["method_existing_phi"].count(" phi i32 ") == 3
                assert cfg_bodies["method_existing_phi"].count(" br i1 ") == 3

            exe = link(name, output, cfg_output)
            if pass_name == "native-mix" and seed in (42, 44):
                normalized, _ = run(opt, "-passes=instcombine,reassociate,instcombine,verify", "-S", output, "-o", "-")
                product = function_bodies(normalized)["method_mul32"]
                assert len(re.findall(r"\bmul i32\b", product)) == (1 if seed == 42 else 2)
            if pass_name == "native-mix" and seed == 44:
                product = rewritten["method_mul32"]
                assert all(label in product for label in ("native.mix.shifted", "native.mix.selected", "native.mix.complement"))
                assert len(re.findall(r"\bmul i32\b", product)) == 2
                check_linked(tool("llvm-objdump"), pass_name, reference_exe, exe, split_product=True)
            if seed == 42:
                if pass_name == "native-mix":
                    assert "native.mix.adjustment" in rewritten["method_mul32"]
                first_output = output.read_bytes()
                repeated = work / f"{name}-repeat.bc"
                transform(pass_name, options, reference, repeated)
                assert first_output == repeated.read_bytes()
                check_linked(tool("llvm-objdump"), pass_name, reference_exe, exe)
            if seed == 43:
                assert first_output != output.read_bytes()

    test_compare(args, work, tool, driver, triple)


def compare_fixture():
    functions = []
    export = "dllexport " if os.name == "nt" else ""
    for width in (8, 16, 32, 64):
        for predicate in ("slt", "sle", "sgt", "sge", "ult", "ule", "ugt", "uge"):
            operands = ("%left", "%right")
            body = ""
            if width != 64:
                operands = ("%x", "%y")
                body = (f"  %x = trunc i64 %left to i{width}\n"
                        f"  %y = trunc i64 %right to i{width}\n")
            functions.append(f"""define {export}i32 @method_{predicate}{width}(i64 noundef %left, i64 noundef %right) {{
{body}  %cmp = icmp {predicate} i{width} {operands[0]}, {operands[1]}
  br i1 %cmp, label %yes, label %no
yes:
  %a = call i32 @compare_true()
  ret i32 %a
no:
  %b = call i32 @compare_false()
  ret i32 %b
}}""")
    return "\n\n".join(functions) + """

declare i32 @compare_true()
declare i32 @compare_false()

define i32 @method_compare_loop(i32 %limit) {
entry:
  br label %loop
loop:
  %value = phi i32 [ 0, %entry ], [ %next, %loop ]
  %next = add i32 %value, 1
  %cmp = icmp ult i32 %next, %limit
  br i1 %cmp, label %loop, label %exit
exit:
  %result = phi i32 [ %next, %loop ]
  ret i32 %result
}

define i1 @method_shared(i32 %left, i32 %right) {
  %cmp = icmp slt i32 %left, %right
  br i1 %cmp, label %yes, label %no
yes:
  ret i1 %cmp
no:
  ret i1 false
}

define i32 @method_same_sign(i32 %left, i32 %right) {
  %cmp = icmp samesign ult i32 %left, %right
  br i1 %cmp, label %yes, label %no
yes:
  ret i32 %left
no:
  ret i32 %right
}

define i32 @method_pointer(ptr %left, ptr %right) {
  %cmp = icmp ult ptr %left, %right
  br i1 %cmp, label %yes, label %no
yes:
  ret i32 1
no:
  ret i32 0
}

define i32 @method_constant(i32 %left) {
  %cmp = icmp slt i32 %left, 37
  br i1 %cmp, label %yes, label %no
yes:
  ret i32 -1
no:
  ret i32 %left
}
"""


def test_compare(args, work, tool, driver, triple):
    source = work / "compare.ll"
    source.write_text(compare_fixture(), encoding="utf-8")
    baseline = work / "compare-baseline.bc"
    run(tool("opt"), "-passes=verify", source, "-o", baseline)
    original, _ = run(tool("opt"), "-S", baseline, "-o", "-")
    bodies = function_bodies(original)
    targets = ("x86_64-linux-gnu", "aarch64-linux-gnu",
               "x86_64-windows-gnu", "aarch64-windows-gnu")
    expected = "checked 727040 integer relations and 128 loop lengths"

    def rewrite(seed, output, cap=1):
        pipeline = f"native-compare<seed={seed};max={cap};prefix=method_>"
        _, log = run(*driver, f"-passes=verify,{pipeline},verify", baseline, "-o", output)
        return verify_reports(log, "native-compare", bodies)

    def compile_object(bitcode, obj, target):
        run(tool("llc"), "-O=2", "-filetype=obj", "-relocation-model=pic",
            *target, bitcode, "-o", obj)

    variants = [baseline]
    for seed in (42, 43):
        output = work / f"compare-{seed}.bc"
        reports, totals = rewrite(seed, output)
        assert totals["eligible"] == totals["transformed"] == 33, totals
        assert reports["method_shared"]["skipped_guard"] == 1
        assert reports["method_constant"]["skipped_guard"] == 1
        assert reports["method_same_sign"]["skipped_flags"] == 1
        assert reports["method_pointer"]["skipped_type"] == 1
        ir, _ = run(tool("opt"), "-S", output, "-o", "-")
        rewritten = function_bodies(ir)
        for name, before in bodies.items():
            after = rewritten[name]
            if not reports[name]["transformed"]:
                assert after == before, name
                continue
            assert after.count(" br i1 ") == before.count(" br i1 ") + 1, name
            assert "native.compare.result = phi i1" in after, name
            if name == "method_compare_loop":
                assert after.count("freeze i32") == 2
                assert "[ %next, %native.compare.join ]" in after
            else:
                assert " freeze " not in after, name
        variants.append(output)

    disabled = work / "compare-disabled.bc"
    _, totals = rewrite(42, disabled, cap=0)
    assert totals["transformed"] == 0 and totals["skipped_cap"] == 33
    disabled_ir, _ = run(tool("opt"), "-S", disabled, "-o", "-")
    assert disabled_ir.splitlines()[1:] == original.splitlines()[1:]
    repeated = work / "compare-repeat.bc"
    rewrite(42, repeated)
    assert repeated.read_bytes() == variants[1].read_bytes()
    assert variants[2].read_bytes() != variants[1].read_bytes()
    normalized, _ = run(tool("opt"), "-passes=instcombine,simplifycfg,instcombine,verify",
                        "-S", variants[1], "-o", "-")
    normalized_comparison = function_bodies(normalized)["method_slt32"]
    assert normalized_comparison.count("icmp ") >= 3

    def compare_shape(before, after, arch):
        conditions = (r"j(?!mp)[a-z]+" if arch in ("x86_64", "amd64") else
                      r"(tb|cb)n?z|b\.[a-z]{2}|csel|csinc|csinv|csneg")
        counts = [sum(bool(re.fullmatch(conditions, op)) for op in ops)
                  for ops in (before, after)]
        assert counts[1] > counts[0], (arch, before, after)
        return counts

    linked = []
    for bitcode in variants:
        obj = bitcode.with_suffix(".o")
        compile_object(bitcode, obj, triple)
        exe = bitcode.with_suffix(".exe" if os.name == "nt" else ".test")
        run(args.cc, *args.cc_arg, "-O2", FIXTURES / "compare.c", obj, "-o", exe)
        result, _ = run(exe)
        assert result.strip() == expected, (bitcode, result)
        print(f"{bitcode.stem}: {result.strip()}", flush=True)
        linked.append(exe)

    host = platform.machine().lower()
    if host in ("x86_64", "amd64", "aarch64", "arm64"):
        before, after = (mnemonics(tool("llvm-objdump"), "method_slt32", exe)
                         for exe in linked[:2])
        base, routed = compare_shape(before, after, host)
        print(f"linked {host} comparison decisions: {base} -> {routed}", flush=True)

    for target in targets:
        names = []
        for bitcode in variants[:2]:
            obj = work / f"{bitcode.stem}-{target}.o"
            compile_object(bitcode, obj, [f"-mtriple={target}"])
            names.append(mnemonics(tool("llvm-objdump"), "method_slt32", obj))
        base, routed = compare_shape(*names, target.split("-", 1)[0])
        print(f"{target} comparison decisions: {base} -> {routed}", flush=True)


def mnemonics(objdump, symbol, binary):
    assembly, _ = run(objdump, "-d", "--no-show-raw-insn",
                      f"--disassemble-symbols={symbol}", binary)
    assert f"<{symbol}>:" in assembly, f"missing symbol {symbol} in {binary}"
    result = re.findall(r"(?m)^ *[0-9a-f]+:\s+([a-z][a-z0-9.]*)", assembly)
    assert result, f"missing instructions in {symbol} from {binary}"
    return result


def check_linked(objdump, pass_name, baseline, transformed, split_product=False):
    arch = platform.machine().lower()
    if arch not in ("x86_64", "amd64", "aarch64", "arm64"):
        print(f"Linked mnemonic check unavailable for {arch}")
        return
    x86 = arch in ("x86_64", "amd64")
    if split_product:
        symbols = ("method_mul32",)
    elif pass_name == "native-mix":
        symbols = ("method_add32",)
    else:
        symbols = ("method_add32", "method_mul32")
    for symbol in symbols:
        before = mnemonics(objdump, symbol, baseline)
        after = mnemonics(objdump, symbol, transformed)
        if split_product:
            multiply = r"imul[bwlq]?" if x86 else r"mul|madd"
            assert sum(bool(re.fullmatch(multiply, op)) for op in before) == 1
            assert sum(bool(re.fullmatch(multiply, op)) for op in after) == 2
        elif pass_name == "native-mix":
            mixed = r"(and|xor)[bwlq]?" if x86 else r"and|eor"
            assert not any(re.fullmatch(mixed, op) for op in before)
            assert any(re.fullmatch(mixed, op) for op in after)
        else:
            selector = r"j(?!mp)[a-z]+" if x86 else r"(tb|cb)n?z|b\.[a-z]{2}|csel"
            assert not any(re.fullmatch(selector, op) for op in before)
            assert any(re.fullmatch(selector, op) for op in after)
            multiply = r"imul[bwlq]?" if x86 else r"mul|madd|msub"
            assert sum(bool(re.fullmatch(multiply, op)) for op in after) >= 2
        print(f"{pass_name} linked {symbol}: {' '.join(after)}", flush=True)


def main():
    parser = argparse.ArgumentParser(description="Test LLVM mix and route rewrites")
    parser.add_argument("--llvm-bin", required=True, type=Path)
    driver = parser.add_mutually_exclusive_group(required=True)
    driver.add_argument("--plugin", type=Path)
    driver.add_argument("--static-opt", type=Path)
    parser.add_argument("--target")
    parser.add_argument("--cc", default="cc")
    parser.add_argument("--cc-arg", action="append", default=[])
    parser.add_argument("--work-dir", type=Path)
    args = parser.parse_args()
    if args.work_dir:
        args.work_dir.mkdir(parents=True, exist_ok=True)
        test(args, args.work_dir.resolve())
    else:
        with tempfile.TemporaryDirectory() as temp:
            test(args, Path(temp))


if __name__ == "__main__":
    main()

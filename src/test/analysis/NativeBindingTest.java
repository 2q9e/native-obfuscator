package dev.nativeobfuscator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.Consumer;

public final class NativeBindingTest {
    private static final String SECTION_NAME = ".noab";
    private static final byte[] ANCHOR = HexFormat.of().parseHex(
        "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20");

    private static final int ELF_PROGRAM = 64;
    private static final int ELF_SECTION_SIZE = 64;
    private static final int ELF_PAYLOAD = 0x100;
    private static final int ELF_OTHER_PAYLOAD = 0x140;
    private static final int ELF_STRINGS = 0x180;
    private static final int ELF_SECTIONS = 0x200;
    private static final int ELF_ANCHOR = ELF_SECTIONS + ELF_SECTION_SIZE;
    private static final int ELF_OTHER = ELF_ANCHOR + ELF_SECTION_SIZE;
    private static final int ELF_NAMES = ELF_OTHER + ELF_SECTION_SIZE;
    private static final long ELF_BASE = 0x1000;

    private static final int PE_HEADER = 0x80;
    private static final int PE_OPTIONAL = PE_HEADER + 24;
    private static final int PE_OPTIONAL_SIZE = 240;
    private static final int PE_SECTION_SIZE = 40;
    private static final int PE_ANCHOR = PE_OPTIONAL + PE_OPTIONAL_SIZE;
    private static final int PE_OTHER = PE_ANCHOR + PE_SECTION_SIZE;
    private static final int PE_PAYLOAD = 0x200;
    private static final int PE_RAW_SIZE = 0x200;
    private static final int PE_OTHER_PAYLOAD = PE_PAYLOAD + PE_RAW_SIZE;
    private static final int PE_READ_ONLY_DATA = 0x40000040;

    private NativeBindingTest() {}

    public static void main(String[] args) throws IOException {
        checkElf();
        checkPe();
        System.out.println("Native binding section checks passed");
    }

    private static void checkElf() throws IOException {
        NativeTarget target = NativeTarget.LINUX_X64;
        byte[] x64 = elf(62);
        byte[] arm64 = elf(183);
        accept(x64, target);
        accept(arm64, NativeTarget.LINUX_ARM64);
        reject(arm64, target, "ELF machine mismatch");
        reject(x64, NativeTarget.LINUX_ARM64, "ELF machine mismatch in reverse");

        reject(x64, target, "ELF payload mismatch", image -> image.put(ELF_PAYLOAD, (byte) 0));
        reject(x64, target, "ELF payload length mismatch",
            image -> image.putLong(ELF_ANCHOR + 32, ANCHOR.length - 1));
        reject(x64, target, "missing ELF anchor", image -> image.putInt(ELF_ANCHOR, 7));
        reject(x64, target, "duplicate ELF anchor", image -> image.putInt(ELF_OTHER, 1));
        reject(x64, target, "ELF anchor is not file-backed", image -> image.putInt(ELF_ANCHOR + 4, 8));
        reject(x64, target, "ELF anchor is not allocated", image -> image.putLong(ELF_ANCHOR + 8, 0));
        reject(x64, target, "writable ELF section", image -> image.putLong(ELF_ANCHOR + 8, 3));
        reject(x64, target, "executable ELF section", image -> image.putLong(ELF_ANCHOR + 8, 6));
        reject(x64, target, "ELF anchor has no load segment", image -> image.putInt(ELF_PROGRAM, 4));
        reject(x64, target, "unreadable ELF segment", image -> image.putInt(ELF_PROGRAM + 4, 0));
        reject(x64, target, "writable ELF segment", image -> image.putInt(ELF_PROGRAM + 4, 6));
        reject(x64, target, "executable ELF segment", image -> image.putInt(ELF_PROGRAM + 4, 5));
        reject(x64, target, "ELF segment does not map the section file bytes",
            image -> image.putLong(ELF_PROGRAM + 32, ELF_PAYLOAD + ANCHOR.length - 1));
        reject(x64, target, "ELF section and segment offsets disagree",
            image -> image.putLong(ELF_ANCHOR + 16, ELF_BASE + ELF_PAYLOAD + 1));
        reject(x64, target, "ELF program table outside file", image -> image.putLong(32, Long.MAX_VALUE));
        reject(x64, target, "ELF section table outside file", image -> image.putLong(40, Long.MAX_VALUE));
        reject(x64, target, "ELF section name index outside table", image -> image.putShort(62, (short) 4));
        reject(x64, target, "ELF section name outside strings", image -> image.putInt(ELF_ANCHOR, Integer.MAX_VALUE));
        reject(x64, target, "ELF payload outside file", image -> image.putLong(ELF_ANCHOR + 24, Long.MAX_VALUE));
        reject(Arrays.copyOf(x64, 63), target, "truncated ELF header");
        reject(Arrays.copyOf(x64, ELF_NAMES + ELF_SECTION_SIZE - 1), target, "truncated ELF section table");
    }

    private static void checkPe() throws IOException {
        NativeTarget target = NativeTarget.WINDOWS_X64;
        byte[] x64 = pe(0x8664);
        byte[] arm64 = pe(0xaa64);
        accept(x64, target);
        accept(arm64, NativeTarget.WINDOWS_ARM64);
        reject(arm64, target, "PE machine mismatch");
        reject(x64, NativeTarget.WINDOWS_ARM64, "PE machine mismatch in reverse");

        reject(x64, target, "PE payload mismatch", image -> image.put(PE_PAYLOAD, (byte) 0));
        reject(x64, target, "PE virtual size mismatch",
            image -> image.putInt(PE_ANCHOR + 8, ANCHOR.length + 1));
        reject(x64, target, "PE raw size is shorter than the anchor",
            image -> image.putInt(PE_ANCHOR + 16, ANCHOR.length - 1));
        reject(x64, target, "nonzero PE raw padding",
            image -> image.put(PE_PAYLOAD + PE_RAW_SIZE - 1, (byte) 1));
        reject(x64, target, "missing exact PE anchor", image -> peName(image, PE_ANCHOR, ".noabx"));
        reject(x64, target, "duplicate PE anchor", image -> peName(image, PE_OTHER, SECTION_NAME));
        reject(x64, target, "PE anchor is not initialized data",
            image -> image.putInt(PE_ANCHOR + 36, 0x40000080));
        reject(x64, target, "PE anchor is not readable", image -> image.putInt(PE_ANCHOR + 36, 0x40));
        reject(x64, target, "writable PE section",
            image -> image.putInt(PE_ANCHOR + 36, PE_READ_ONLY_DATA | 0x80000000));
        reject(x64, target, "executable PE section",
            image -> image.putInt(PE_ANCHOR + 36, PE_READ_ONLY_DATA | 0x20000000));
        reject(x64, target, "PE header outside file", image -> image.putInt(60, Integer.MAX_VALUE));
        reject(x64, target, "invalid PE optional header length", image -> image.putShort(PE_HEADER + 20, (short) -1));
        reject(x64, target, "PE section table outside file", image -> image.putShort(PE_HEADER + 6, (short) -1));
        reject(x64, target, "PE payload outside file", image -> image.putInt(PE_ANCHOR + 20, -4));
        reject(Arrays.copyOf(x64, PE_ANCHOR + PE_SECTION_SIZE - 1), target, "truncated PE section table");
        reject(Arrays.copyOf(x64, PE_PAYLOAD + PE_RAW_SIZE - 1), target, "truncated PE raw section");
    }

    private static byte[] elf(int machine) {
        ByteBuffer image = ByteBuffer.allocate(ELF_NAMES + ELF_SECTION_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        image.putInt(0, 0x464c457f);
        image.put(4, (byte) 2); // ELF64
        image.put(5, (byte) 1); // Little endian
        image.put(6, (byte) 1); // ELF version
        image.putShort(16, (short) 3); // ET_DYN
        image.putShort(18, (short) machine);
        image.putInt(20, 1);
        image.putLong(32, ELF_PROGRAM);
        image.putLong(40, ELF_SECTIONS);
        image.putShort(52, (short) 64);
        image.putShort(54, (short) 56);
        image.putShort(56, (short) 1);
        image.putShort(58, (short) ELF_SECTION_SIZE);
        image.putShort(60, (short) 4);
        image.putShort(62, (short) 3);

        image.putInt(ELF_PROGRAM, 1); // PT_LOAD
        image.putInt(ELF_PROGRAM + 4, 4); // PF_R
        image.putLong(ELF_PROGRAM + 16, ELF_BASE);
        image.putLong(ELF_PROGRAM + 24, ELF_BASE);
        image.putLong(ELF_PROGRAM + 32, ELF_STRINGS);
        image.putLong(ELF_PROGRAM + 40, ELF_STRINGS);
        image.putLong(ELF_PROGRAM + 48, 0x1000);

        image.put(ELF_PAYLOAD, ANCHOR);
        image.put(ELF_OTHER_PAYLOAD, ANCHOR);
        byte[] strings = "\0.noab\0.rodata\0.shstrtab\0".getBytes(StandardCharsets.US_ASCII);
        image.put(ELF_STRINGS, strings);
        elfSection(image, ELF_ANCHOR, 1, 1, 2, ELF_BASE + ELF_PAYLOAD, ELF_PAYLOAD, ANCHOR.length);
        elfSection(image, ELF_OTHER, 7, 1, 2, ELF_BASE + ELF_OTHER_PAYLOAD, ELF_OTHER_PAYLOAD, ANCHOR.length);
        elfSection(image, ELF_NAMES, 15, 3, 0, 0, ELF_STRINGS, strings.length);
        return image.array();
    }

    private static void elfSection(ByteBuffer image, int row, int name, int type, long flags, long address,
                                   long offset, long length) {
        image.putInt(row, name);
        image.putInt(row + 4, type);
        image.putLong(row + 8, flags);
        image.putLong(row + 16, address);
        image.putLong(row + 24, offset);
        image.putLong(row + 32, length);
        image.putLong(row + 48, 1);
    }

    private static byte[] pe(int machine) {
        ByteBuffer image = ByteBuffer.allocate(PE_OTHER_PAYLOAD + PE_RAW_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        image.putShort(0, (short) 0x5a4d);
        image.putInt(60, PE_HEADER);
        image.putInt(PE_HEADER, 0x4550);
        image.putShort(PE_HEADER + 4, (short) machine);
        image.putShort(PE_HEADER + 6, (short) 2);
        image.putShort(PE_HEADER + 20, (short) PE_OPTIONAL_SIZE);
        image.putShort(PE_HEADER + 22, (short) 0x2022); // DLL, executable image, large-address aware

        image.putShort(PE_OPTIONAL, (short) 0x20b); // PE32+
        image.putInt(PE_OPTIONAL + 8, 2 * PE_RAW_SIZE);
        image.putLong(PE_OPTIONAL + 24, 0x180000000L);
        image.putInt(PE_OPTIONAL + 32, 0x1000); // Section alignment
        image.putInt(PE_OPTIONAL + 36, PE_RAW_SIZE); // File alignment
        image.putInt(PE_OPTIONAL + 56, 0x3000); // Size of image
        image.putInt(PE_OPTIONAL + 60, PE_PAYLOAD); // Size of headers
        image.putShort(PE_OPTIONAL + 68, (short) 3); // Console subsystem
        image.putInt(PE_OPTIONAL + 108, 16); // Data directories

        peSection(image, PE_ANCHOR, SECTION_NAME, 0x1000, PE_PAYLOAD);
        peSection(image, PE_OTHER, ".rdata", 0x2000, PE_OTHER_PAYLOAD);
        return image.array();
    }

    private static void peSection(ByteBuffer image, int row, String name, int address, int offset) {
        peName(image, row, name);
        image.putInt(row + 8, ANCHOR.length);
        image.putInt(row + 12, address);
        image.putInt(row + 16, PE_RAW_SIZE);
        image.putInt(row + 20, offset);
        image.putInt(row + 36, PE_READ_ONLY_DATA);
        image.put(offset, ANCHOR);
    }

    private static void peName(ByteBuffer image, int row, String name) {
        image.put(row, Arrays.copyOf(name.getBytes(StandardCharsets.US_ASCII), 8));
    }

    private static void accept(byte[] image, NativeTarget target) throws IOException {
        NativeImage.requireReadOnlySection(image, target, SECTION_NAME, ANCHOR);
    }

    private static void reject(byte[] image, NativeTarget target, String description, Consumer<ByteBuffer> mutate) {
        byte[] changed = image.clone();
        mutate.accept(ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN));
        reject(changed, target, description);
    }

    private static void reject(byte[] image, NativeTarget target, String description) {
        try {
            accept(image, target);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Accepted " + description);
    }
}

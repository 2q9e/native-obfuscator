package dev.nativeobfuscator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class NativeImage {
    private NativeImage() {}

    static long executableBytes(byte[] binary, NativeTarget target) throws IOException {
        ByteBuffer image = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        try {
            long size = switch (target) {
                case LINUX_X64 -> elf(image, 62);
                case LINUX_ARM64 -> elf(image, 183);
                case WINDOWS_X64 -> pe(image, 0x8664);
                case WINDOWS_ARM64 -> pe(image, 0xaa64);
            };
            if (size <= 0 || size > binary.length)
                throw new IOException("Invalid executable section size for " + target.key);
            return size;
        } catch (IndexOutOfBoundsException | ArithmeticException e) {
            throw new IOException("Invalid native library format for " + target.key, e);
        }
    }

    private static void requireElfHeader(ByteBuffer image, int machine) throws IOException {
        requireRange(image, 0, 64);
        if (image.getInt(0) != 0x464c457f || image.get(4) != 2 || image.get(5) != 1
            || unsignedShort(image, 16) != 3 || unsignedShort(image, 18) != machine)
            throw new IOException("Expected a matching 64-bit ELF shared library");
    }

    private static long elf(ByteBuffer image, int machine) throws IOException {
        requireElfHeader(image, machine);
        int sectionTable = Math.toIntExact(image.getLong(40));
        int stride = unsignedShort(image, 58);
        int sections = unsignedShort(image, 60);
        if (sectionTable <= 0 || stride < 64 || sections == 0)
            throw new IOException("Invalid ELF section table");
        long size = 0;
        for (int i = 0; i < sections; i++) {
            int section = Math.toIntExact((long) sectionTable + (long) i * stride);
            if ((image.getLong(section + 8) & 4) != 0) {
                long bytes = image.getLong(section + 32);
                requireRange(image, image.getLong(section + 24), bytes);
                size = Math.addExact(size, bytes);
            }
        }
        return size;
    }

    private static int requirePeHeader(ByteBuffer image, int machine) throws IOException {
        if (unsignedShort(image, 0) != 0x5a4d) throw new IOException("Expected a Windows PE library");
        int header = image.getInt(60);
        requireRange(image, header, 24);
        if (image.getInt(header) != 0x4550 || unsignedShort(image, header + 4) != machine
            || (unsignedShort(image, header + 22) & 0x2000) == 0)
            throw new IOException("Expected a matching Windows DLL");
        int optionalSize = unsignedShort(image, header + 20);
        requireRange(image, header + 24L, optionalSize);
        if (optionalSize < 2 || unsignedShort(image, header + 24) != 0x20b)
            throw new IOException("Expected a 64-bit PE optional header");
        return header;
    }

    private static long pe(ByteBuffer image, int machine) throws IOException {
        int header = requirePeHeader(image, machine);
        int sections = unsignedShort(image, header + 6);
        int table = header + 24 + unsignedShort(image, header + 20);
        long size = 0;
        for (int i = 0; i < sections; i++) {
            int section = table + i * 40;
            int flags = image.getInt(section + 36);
            if ((flags & 0x20000000) != 0) {
                long logical = Integer.toUnsignedLong(image.getInt(section + 8));
                long raw = Integer.toUnsignedLong(image.getInt(section + 16));
                requireRange(image, Integer.toUnsignedLong(image.getInt(section + 20)), raw);
                size = Math.addExact(size, Math.min(logical, raw));
            }
        }
        return size;
    }

    static void requireReadOnlySection(byte[] binary, NativeTarget target, String name, byte[] expected)
            throws IOException {
        if (expected.length == 0) throw new IllegalArgumentException("Missing read-only section contents");
        requireSection(binary, target, name, expected);
    }

    static long requireCodeSection(byte[] binary, NativeTarget target, String name) throws IOException {
        return requireSection(binary, target, name, null);
    }

    private static long requireSection(byte[] binary, NativeTarget target, String name, byte[] expected)
            throws IOException {
        if (!name.matches("[.][a-z0-9]{1,6}"))
            throw new IllegalArgumentException("Invalid native section name");
        ByteBuffer image = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        byte[] sectionName = name.getBytes(StandardCharsets.US_ASCII);
        try {
            return switch (target) {
                case LINUX_X64 -> elfSection(image, 62, sectionName, expected);
                case LINUX_ARM64 -> elfSection(image, 183, sectionName, expected);
                case WINDOWS_X64 -> peSection(image, 0x8664, sectionName, expected);
                case WINDOWS_ARM64 -> peSection(image, 0xaa64, sectionName, expected);
            };
        } catch (IndexOutOfBoundsException | ArithmeticException failure) {
            throw new IOException("Invalid native section table for " + target.key, failure);
        }
    }

    private static long elfSection(ByteBuffer image, int machine, byte[] name, byte[] expected)
            throws IOException {
        requireElfHeader(image, machine);
        long table = image.getLong(40);
        int stride = unsignedShort(image, 58);
        int count = unsignedShort(image, 60);
        int namesIndex = unsignedShort(image, 62);
        if (table <= 0 || stride < 64 || namesIndex == 0 || namesIndex >= count)
            throw new IOException("Invalid ELF section name table");
        requireRange(image, table, (long) stride * count);
        int names = Math.toIntExact(table + (long) namesIndex * stride);
        long namesOffset = image.getLong(names + 24);
        long namesSize = image.getLong(names + 32);
        if (image.getInt(names + 4) != 3 || namesSize <= 0)
            throw new IOException("Invalid ELF section name table");
        requireRange(image, namesOffset, namesSize);
        boolean executable = expected == null;
        long sectionLength = 0;
        for (int i = 0; i < count; i++) {
            int row = Math.toIntExact(table + (long) i * stride);
            long nameIndex = Integer.toUnsignedLong(image.getInt(row));
            if (nameIndex >= namesSize) throw new IOException("ELF section name is out of bounds");
            if (name.length >= namesSize - nameIndex
                || !matchesName(image, Math.toIntExact(namesOffset + nameIndex), name, name.length + 1)) continue;
            if (sectionLength != 0) throw new IOException("Duplicate ELF section");
            long length = image.getLong(row + 32);
            if (image.getInt(row + 4) != 1 || (image.getLong(row + 8) & 7) != (executable ? 6 : 2)
                || length <= 0 || (!executable && length != expected.length))
                throw new IOException("ELF section has unexpected type, flags, or size");
            long offset = image.getLong(row + 24);
            requireRange(image, offset, length);
            requireElfLoad(image, image.getLong(row + 16), offset, (int) length, executable ? 5 : 4);
            if (!executable) requirePayload(image, (int) offset, expected);
            sectionLength = length;
        }
        if (sectionLength == 0) throw new IOException("Missing ELF section");
        return sectionLength;
    }

    private static void requireElfLoad(ByteBuffer image, long address, long offset, int length, int permissions)
            throws IOException {
        long table = image.getLong(32);
        int stride = unsignedShort(image, 54);
        int count = unsignedShort(image, 56);
        if (table <= 0 || stride < 56 || count == 0 || address < 0)
            throw new IOException("Invalid ELF load table");
        requireRange(image, table, (long) stride * count);
        int mappings = 0;
        for (int i = 0; i < count; i++) {
            int row = Math.toIntExact(table + (long) i * stride);
            if (image.getInt(row) != 1) continue;
            long file = image.getLong(row + 8);
            long virtual = image.getLong(row + 16);
            long fileSize = image.getLong(row + 32);
            long memorySize = image.getLong(row + 40);
            requireRange(image, file, fileSize);
            if (virtual < 0 || memorySize < fileSize)
                throw new IOException("Invalid ELF load segment range");
            long relative = address - virtual;
            if (relative >= memorySize || relative <= -length) continue;
            if (relative < 0 || length > fileSize - relative || offset - file != relative
                || (image.getInt(row + 4) & 7) != permissions)
                throw new IOException("ELF section is not mapped with the expected permissions");
            mappings++;
        }
        if (mappings != 1) throw new IOException("ELF section does not have a unique load segment");
    }

    private static long peSection(ByteBuffer image, int machine, byte[] name, byte[] expected)
            throws IOException {
        int header = requirePeHeader(image, machine);
        int count = unsignedShort(image, header + 6);
        int optionalSize = unsignedShort(image, header + 20);
        if (optionalSize < 64 || count == 0) throw new IOException("Invalid PE image layout");
        long table = (long) header + 24 + optionalSize;
        requireRange(image, table, (long) count * 40);
        long imageSize = Integer.toUnsignedLong(image.getInt(header + 24 + 56));
        long headers = Integer.toUnsignedLong(image.getInt(header + 24 + 60));
        boolean executable = expected == null;
        long sectionLength = 0;
        for (int i = 0; i < count; i++) {
            int row = Math.toIntExact(table + i * 40L);
            if (!matchesName(image, row, name, 8)) continue;
            if (sectionLength != 0) throw new IOException("Duplicate PE section");
            long logical = Integer.toUnsignedLong(image.getInt(row + 8));
            long address = Integer.toUnsignedLong(image.getInt(row + 12));
            long raw = Integer.toUnsignedLong(image.getInt(row + 16));
            long offset = Integer.toUnsignedLong(image.getInt(row + 20));
            int flags = image.getInt(row + 36);
            int expectedFlags = executable ? 0x60000020 : 0x40000040;
            if ((flags & 0xe00000e0) != expectedFlags || logical <= 0 || raw < logical
                || (!executable && logical != expected.length) || address < headers || logical > imageSize - address
                || offset < headers || offset < table + count * 40L)
                throw new IOException("PE section has unexpected mapped range, flags, or size");
            requireRange(image, offset, raw);
            if (!executable) {
                requirePayload(image, (int) offset, expected);
                for (long iPadding = logical; iPadding < raw; iPadding++)
                    if (image.get((int) (offset + iPadding)) != 0)
                        throw new IOException("PE analysis binding padding is not zero");
            }
            sectionLength = logical;
        }
        if (sectionLength == 0) throw new IOException("Missing PE section");
        return sectionLength;
    }

    private static boolean matchesName(ByteBuffer image, int offset, byte[] name, int paddedLength) {
        for (int i = 0; i < paddedLength; i++) {
            byte expected = i < name.length ? name[i] : 0;
            if (image.get(offset + i) != expected) return false;
        }
        return true;
    }

    private static void requirePayload(ByteBuffer image, int offset, byte[] expected) throws IOException {
        for (int i = 0; i < expected.length; i++)
            if (image.get(offset + i) != expected[i]) throw new IOException("Native analysis binding does not match manifest");
    }

    private static int unsignedShort(ByteBuffer image, int offset) {
        return Short.toUnsignedInt(image.getShort(offset));
    }

    private static void requireRange(ByteBuffer image, long start, long length) throws IOException {
        if (start < 0 || length < 0 || start > image.capacity() || length > image.capacity() - start)
            throw new IOException("Native section exceeds the library size");
    }
}

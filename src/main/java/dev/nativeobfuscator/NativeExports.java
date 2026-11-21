package dev.nativeobfuscator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class NativeExports {
    private static final int MAX_EXPORTS = 65_536;

    private NativeExports() {}

    static void requireFunction(byte[] binary, NativeTarget target, String name) throws IOException {
        switch (target) {
            case LINUX_X64 -> requireElfFunction(binary, 62, name);
            case LINUX_ARM64 -> requireElfFunction(binary, 183, name);
            case WINDOWS_X64 -> requirePeFunction(binary, 0x8664, name);
            case WINDOWS_ARM64 -> requirePeFunction(binary, 0xaa64, name);
        }
    }

    private static void requireElfFunction(byte[] binary, int machine, String name) throws IOException {
        var image = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        byte[] expected = name.getBytes(StandardCharsets.US_ASCII);
        try {
            if (image.getInt(0) != 0x464c457f || image.get(4) != 2 || image.get(5) != 1
                || u16(image, 16) != 3 || u16(image, 18) != machine)
                throw new IOException("Expected a matching ELF64 shared library");
            long sections = image.getLong(40);
            int stride = u16(image, 58);
            int count = u16(image, 60);
            if (stride < 64 || count == 0) throw new IOException("Invalid ELF export section table");
            range(image, sections, (long) stride * count);
            int matches = 0;
            for (int i = 1; i < count; i++) {
                int symbols = (int) (sections + (long) i * stride);
                if (image.getInt(symbols + 4) != 11) continue; // SHT_DYNSYM
                int stringsIndex = image.getInt(symbols + 40);
                if (stringsIndex <= 0 || stringsIndex >= count)
                    throw new IOException("Invalid ELF export string index");
                int strings = (int) (sections + (long) stringsIndex * stride);
                if (image.getInt(strings + 4) != 3)
                    throw new IOException("Invalid ELF export string section");
                long stringsOffset = image.getLong(strings + 24);
                long stringsLength = image.getLong(strings + 32);
                range(image, stringsOffset, stringsLength);
                long offset = image.getLong(symbols + 24);
                long length = image.getLong(symbols + 32);
                range(image, offset, length);
                if (image.getLong(symbols + 56) != 24 || length % 24 != 0 || length / 24 > MAX_EXPORTS)
                    throw new IOException("Invalid ELF dynamic symbol layout");
                for (int symbol = (int) offset; symbol < offset + length; symbol += 24) {
                    long index = u32(image, symbol);
                    if (index >= stringsLength) throw new IOException("Invalid ELF export name offset");
                    if (!matchesName(image, stringsOffset + index, stringsLength - index, expected)) continue;
                    int info = Byte.toUnsignedInt(image.get(symbol + 4));
                    int visibility = image.get(symbol + 5) & 3;
                    int indexSection = u16(image, symbol + 6);
                    if ((info & 15) != 2 || (info >>> 4) == 0 || (info >>> 4) > 2
                        || visibility != 0 && visibility != 3 || indexSection == 0 || indexSection >= count)
                        throw new IOException("ELF registration export is not a defined visible function");
                    int section = (int) (sections + (long) indexSection * stride);
                    long address = image.getLong(symbol + 8);
                    long sectionAddress = image.getLong(section + 16);
                    long sectionSize = image.getLong(section + 32);
                    long sectionOffset = image.getLong(section + 24);
                    range(image, sectionOffset, sectionSize);
                    if (address < 0 || sectionAddress < 0 || address < sectionAddress
                        || address - sectionAddress >= sectionSize || image.getInt(section + 4) != 1
                        || (image.getLong(section + 8) & 6) != 6)
                        throw new IOException("ELF registration export is outside executable code");
                    requireElfCodeLoad(image, address, sectionOffset + (address - sectionAddress));
                    matches++;
                }
            }
            if (matches != 1) throw new IOException("Expected exactly one native export " + name);
        } catch (IndexOutOfBoundsException | ArithmeticException failure) {
            throw new IOException("Invalid ELF export metadata", failure);
        }
    }

    private static void requireElfCodeLoad(ByteBuffer image, long address, long offset) throws IOException {
        long programs = image.getLong(32);
        int stride = u16(image, 54);
        int count = u16(image, 56);
        if (stride < 56 || count == 0) throw new IOException("Invalid ELF export load table");
        range(image, programs, (long) stride * count);
        for (int i = 0; i < count; i++) {
            int row = (int) (programs + (long) i * stride);
            if (image.getInt(row) != 1 || (image.getInt(row + 4) & 1) == 0) continue;
            long file = image.getLong(row + 8);
            long virtual = image.getLong(row + 16);
            long size = image.getLong(row + 32);
            range(image, file, size);
            if (virtual >= 0 && address >= virtual && address - virtual < size
                && address - virtual == offset - file && image.getLong(row + 40) >= size)
                return;
        }
        throw new IOException("ELF registration export is not in an executable load segment");
    }

    private static void requirePeFunction(byte[] binary, int machine, String name) throws IOException {
        var image = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        byte[] expected = name.getBytes(StandardCharsets.US_ASCII);
        try {
            int pe = image.getInt(60);
            range(image, pe, 24);
            int optional = pe + 24;
            int optionalSize = u16(image, pe + 20);
            int sections = u16(image, pe + 6);
            range(image, optional, optionalSize + sections * 40L);
            if (u16(image, 0) != 0x5a4d || image.getInt(pe) != 0x4550 || u16(image, pe + 4) != machine
                || (u16(image, pe + 22) & 0x2000) == 0 || optionalSize < 120 || u16(image, optional) != 0x20b
                || u32(image, optional + 108) < 1 || sections == 0 || sections > 96)
                throw new IOException("Expected a matching PE32+ DLL with exports");

            int table = optional + optionalSize;
            long exportRva = u32(image, optional + 112);
            long exportLength = u32(image, optional + 116);
            if (exportRva == 0 || exportLength < 40) throw new IOException("Missing PE export directory");
            var exports = peRange(image, table, sections, exportRva, exportLength);
            int directory = exports.offset;
            long functionCount = u32(image, directory + 20);
            long nameCount = u32(image, directory + 24);
            if (functionCount > MAX_EXPORTS || nameCount > MAX_EXPORTS || functionCount == 0 || nameCount == 0)
                throw new IOException("Invalid PE export count");
            int functions = peRange(image, table, sections, u32(image, directory + 28), functionCount * 4).offset;
            int names = peRange(image, table, sections, u32(image, directory + 32), nameCount * 4).offset;
            int ordinals = peRange(image, table, sections, u32(image, directory + 36), nameCount * 2).offset;
            int matches = 0;
            for (int i = 0; i < nameCount; i++) {
                var entry = peRange(image, table, sections, u32(image, names + i * 4), 1);
                if (!matchesName(image, entry.offset, entry.available, expected)) continue;
                int ordinal = u16(image, ordinals + i * 2);
                if (ordinal >= functionCount) throw new IOException("Invalid PE export ordinal");
                long rva = u32(image, functions + ordinal * 4);
                if (rva >= exportRva && rva - exportRva < exportLength)
                    throw new IOException("PE registration export is a forwarder");
                var code = peRange(image, table, sections, rva, 1);
                if ((code.flags & 0x20000020) != 0x20000020)
                    throw new IOException("PE registration export is outside executable code");
                matches++;
            }
            if (matches != 1) throw new IOException("Expected exactly one native export " + name);
        } catch (IndexOutOfBoundsException | ArithmeticException failure) {
            throw new IOException("Invalid PE export metadata", failure);
        }
    }

    private record PeRange(int offset, long available, int flags) {}

    private static PeRange peRange(ByteBuffer image, int table, int count, long rva, long length)
            throws IOException {
        PeRange match = null;
        for (int i = 0; i < count; i++) {
            int row = table + i * 40;
            long relative = rva - u32(image, row + 12);
            long raw = u32(image, row + 16);
            long virtual = u32(image, row + 8);
            long available = Math.min(raw, virtual) - relative;
            if (relative < 0 || available < length) continue;
            long offset = u32(image, row + 20) + relative;
            range(image, offset, available);
            if (match != null) throw new IOException("PE export address has multiple section mappings");
            match = new PeRange((int) offset, available, image.getInt(row + 36));
        }
        if (match == null) throw new IOException("PE export address is outside file-backed sections");
        return match;
    }

    private static boolean matchesName(ByteBuffer image, long offset, long length, byte[] name) {
        if (length <= name.length) return false;
        for (int i = 0; i < name.length; i++)
            if (image.get((int) offset + i) != name[i]) return false;
        return image.get((int) offset + name.length) == 0;
    }

    private static int u16(ByteBuffer image, int offset) {
        return Short.toUnsignedInt(image.getShort(offset));
    }

    private static long u32(ByteBuffer image, int offset) {
        return Integer.toUnsignedLong(image.getInt(offset));
    }

    private static void range(ByteBuffer image, long offset, long length) throws IOException {
        if (offset < 0 || length < 0 || offset > image.capacity() || length > image.capacity() - offset)
            throw new IOException("Native export table exceeds library bounds");
    }
}

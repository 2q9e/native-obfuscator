package dev.nativeobfuscator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class NativeExports {
    private static final int MAX_EXPORTS = 65_536;

    private NativeExports() {}

    static void requireElfFunction(byte[] binary, int machine, String name) throws IOException {
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

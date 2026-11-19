package dev.nativeobfuscator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Reads the linker-recorded sizes of generated functions before the library is stripped. */
final class ElfFunctionSizes {
    private static final int ELF64_SECTION_SIZE = 64;
    private static final int ELF64_SYMBOL_SIZE = 24;
    private static final int SHT_SYMTAB = 2;
    private static final int SHT_STRTAB = 3;
    private static final int STT_FUNC = 2;
    private static final String GENERATED_PREFIX = "method_";

    private ElfFunctionSizes() {}

    static Map<String, Long> read(byte[] binary) throws IOException {
        ByteBuffer image = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        try {
            if (image.getInt(0) != 0x464c457f || image.get(4) != 2 || image.get(5) != 1)
                throw new IOException("Expected a little-endian ELF64 library for native-size reporting");
            int stride = Short.toUnsignedInt(image.getShort(58));
            int count = Short.toUnsignedInt(image.getShort(60));
            int table = checkedOffset(binary, image.getLong(40), (long) stride * count);
            if (stride < ELF64_SECTION_SIZE || count == 0 || table == 0)
                throw new IOException("Invalid ELF section table");

            var sizes = new HashMap<String, Long>();
            for (int i = 1; i < count; i++) {
                int section = table + i * stride;
                if (image.getInt(section + 4) == SHT_SYMTAB)
                    readSymbols(image, binary, table, stride, count, section, sizes);
            }
            if (sizes.isEmpty()) throw new IOException("Native-size reporting found no generated ELF function symbols");
            return sizes;
        } catch (IndexOutOfBoundsException | ArithmeticException e) {
            throw new IOException("Invalid ELF symbol metadata", e);
        }
    }

    private static void readSymbols(ByteBuffer image, byte[] binary, int table, int stride, int sectionCount,
                                    int section, Map<String, Long> sizes) throws IOException {
        int stringIndex = image.getInt(section + 40);
        if (stringIndex <= 0 || stringIndex >= sectionCount) throw new IOException("Invalid ELF symbol string table");
        int strings = table + stringIndex * stride;
        if (image.getInt(strings + 4) != SHT_STRTAB) throw new IOException("Expected an ELF string table");

        int symbolBytes = Math.toIntExact(image.getLong(section + 32));
        int entrySize = Math.toIntExact(image.getLong(section + 56));
        if (entrySize < ELF64_SYMBOL_SIZE || symbolBytes < 0 || symbolBytes % entrySize != 0)
            throw new IOException("Invalid ELF symbol table size");
        int symbols = checkedOffset(binary, image.getLong(section + 24), symbolBytes);
        int stringBytes = Math.toIntExact(image.getLong(strings + 32));
        int stringStart = checkedOffset(binary, image.getLong(strings + 24), stringBytes);
        int stringEnd = stringStart + stringBytes;
        for (int offset = symbols; offset < symbols + symbolBytes; offset += entrySize) {
            if ((image.get(offset + 4) & 0xf) != STT_FUNC || image.getShort(offset + 6) == 0) continue;
            int nameOffset = image.getInt(offset);
            if (nameOffset < 0 || nameOffset >= stringBytes)
                throw new IOException("Invalid ELF symbol name offset");
            int start = stringStart + nameOffset;
            int end = start;
            while (end < stringEnd && binary[end] != 0) end++;
            if (end == stringEnd) throw new IOException("Unterminated ELF symbol name");
            String name = new String(binary, start, end - start, StandardCharsets.US_ASCII);
            if (name.startsWith(GENERATED_PREFIX)) sizes.put(name, image.getLong(offset + 16));
        }
    }

    private static int checkedOffset(byte[] data, long offset, long length) throws IOException {
        if (offset < 0 || length < 0 || offset > data.length || length > data.length - offset)
            throw new IOException("ELF symbol data exceeds the library size");
        return (int) offset;
    }
}

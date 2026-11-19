package dev.nativeobfuscator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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

    private static long elf(ByteBuffer image, int machine) throws IOException {
        if (image.getInt(0) != 0x464c457f || image.get(4) != 2 || image.get(5) != 1
            || unsignedShort(image, 16) != 3 || unsignedShort(image, 18) != machine)
            throw new IOException("Expected a matching 64-bit ELF shared library");
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

    private static long pe(ByteBuffer image, int machine) throws IOException {
        if (unsignedShort(image, 0) != 0x5a4d) throw new IOException("Expected a Windows PE library");
        int header = image.getInt(60);
        requireRange(image, header, 24);
        if (image.getInt(header) != 0x4550 || unsignedShort(image, header + 4) != machine
            || (unsignedShort(image, header + 22) & 0x2000) == 0)
            throw new IOException("Expected a matching Windows DLL");
        int sections = unsignedShort(image, header + 6);
        int optionalSize = unsignedShort(image, header + 20);
        if (optionalSize < 2 || unsignedShort(image, header + 24) != 0x20b)
            throw new IOException("Expected a 64-bit PE optional header");
        int table = header + 24 + optionalSize;
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

    private static int unsignedShort(ByteBuffer image, int offset) {
        return Short.toUnsignedInt(image.getShort(offset));
    }

    private static void requireRange(ByteBuffer image, long start, long length) throws IOException {
        if (start < 0 || length < 0 || start > image.capacity() || length > image.capacity() - start)
            throw new IOException("Native section exceeds the library size");
    }
}

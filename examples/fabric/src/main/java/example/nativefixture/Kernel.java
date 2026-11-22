package example.nativefixture;

import net.minecraft.util.Identifier;

final class Kernel {
    private Kernel() {}

    static int mix(int value, int salt) {
        int mixed = (value ^ salt) * 31 + 7;
        return mixed ^ (mixed >>> 16);
    }

    static int identifierScore(Identifier identifier, int base) {
        int namespaceLength = identifier.getNamespace().length();
        int pathLength = identifier.path.length();
        return mix(base, (namespaceLength << 16) | pathLength);
    }
}

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

    static int identifierPriority(Identifier current, Identifier candidate, int salt) {
        Comparable<Identifier> key = current;
        int order = key.compareTo(candidate);
        if (order == 0) return salt;
        Identifier first = order < 0 ? current : candidate;
        return mix(first.path.length(), salt);
    }

    static int identifierPriorityOrDefault(Identifier current, Identifier candidate, int salt, int fallback) {
        try { return identifierPriority(current, candidate, salt); }
        catch (NullPointerException missing) { return fallback; }
    }
}

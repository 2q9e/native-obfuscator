package dev.nativeobfuscator.translate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.objectweb.asm.Type;

/** Nominal relationships from selected archive headers, without loading application classes. */
public final class ReferenceHierarchy {
    public static final ReferenceHierarchy EMPTY = new ReferenceHierarchy(Map.of());
    private static final int CACHED_ASSIGNMENT_LIMIT = 4096;

    private record Assignment(String actual, String expected) {}

    private final Map<String, List<String>> parents;
    private final Map<Assignment, Boolean> answers = new HashMap<>();

    public ReferenceHierarchy(Map<String, List<String>> parents) {
        var copy = new HashMap<String, List<String>>();
        parents.forEach((type, supers) -> copy.put(type, List.copyOf(supers)));
        this.parents = Map.copyOf(copy);
    }

    public void updateDigest(MessageDigest digest) {
        digest.update("dependency hierarchy".getBytes(StandardCharsets.US_ASCII));
        var length = ByteBuffer.allocate(Integer.BYTES);
        digest.update(length.putInt(0, parents.size()).array());
        for (var definition : new TreeMap<>(parents).entrySet()) {
            updateString(digest, length, definition.getKey());
            var supers = definition.getValue();
            digest.update(length.putInt(0, supers.size()).array());
            for (String parent : supers) updateString(digest, length, parent);
        }
    }

    private static void updateString(MessageDigest digest, ByteBuffer length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(length.putInt(0, bytes.length).array());
        digest.update(bytes);
    }

    boolean proves(Type actual, Type expected) {
        if (actual.getSort() == Type.ARRAY && expected.getSort() == Type.ARRAY)
            return proves(Type.getType(actual.getDescriptor().substring(1)), Type.getType(expected.getDescriptor().substring(1)));
        if (actual.getSort() != Type.OBJECT || expected.getSort() != Type.OBJECT
            || !parents.containsKey(actual.getInternalName())) return false;
        var query = new Assignment(actual.getInternalName(), expected.getInternalName());
        if (answers.size() < CACHED_ASSIGNMENT_LIMIT) return answers.computeIfAbsent(query, this::search);
        Boolean cached = answers.get(query);
        return cached != null ? cached : search(query);
    }

    private boolean search(Assignment query) {
        var visited = new HashSet<String>();
        var pending = new ArrayDeque<String>();
        pending.add(query.actual);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (current.equals(query.expected)) return true;
            if (visited.add(current)) pending.addAll(parents.getOrDefault(current, List.of()));
        }
        return false;
    }
}

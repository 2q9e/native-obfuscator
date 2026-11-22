package dev.nativeobfuscator.translate;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

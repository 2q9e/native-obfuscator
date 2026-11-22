package dev.nativeobfuscator.translate;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.objectweb.asm.Opcodes.*;

/** Groups instructions by the ordered JVM handlers that cover them. */
final class ExceptionHandlers {
    private static final int ANALYSIS_EDGE_LIMIT = 262_144;
    private static final int HANDLER_COUNT_LIMIT = (65_535 - 2) / 5;

    static final class Route {
        final int start;
        final List<TryCatchBlockNode> handlers;
        boolean used;

        Route(int start, List<TryCatchBlockNode> handlers) {
            this.start = start;
            this.handlers = handlers;
        }

        String label() { return "exception_" + start; }
    }

    private final Route[] sites;
    private final List<Route> routes;
    private final boolean[] referenced;
    private final Set<LabelNode> discarded;
    private final boolean retainsThrowable;
    private int localReferences;

    private ExceptionHandlers(Route[] sites, List<Route> routes, Set<LabelNode> discarded, boolean retainsThrowable) {
        this.sites = sites;
        this.routes = routes;
        this.referenced = new boolean[sites.length];
        this.discarded = discarded;
        this.retainsThrowable = retainsThrowable;
    }

    static ExceptionHandlers plan(MethodNode method) throws MethodCompiler.Unsupported {
        if (method.tryCatchBlocks.isEmpty())
            return new ExceptionHandlers(new Route[0], List.of(), Set.of(), false);
        if (method.tryCatchBlocks.size() > HANDLER_COUNT_LIMIT)
            throw new MethodCompiler.Unsupported("exception table exceeds the JVM dispatch bridge size limit");

        int size = method.instructions.size();
        var active = new ArrayList<List<TryCatchBlockNode>>(Collections.nCopies(size, null));
        var loadedLocals = new BitSet();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof VarInsnNode load && load.getOpcode() == ALOAD) loadedLocals.set(load.var);
        }
        var discarded = new HashSet<LabelNode>();
        boolean retainsThrowable = false;
        int edges = 0;
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            int start = method.instructions.indexOf(block.start);
            int end = method.instructions.indexOf(block.end);
            int target = method.instructions.indexOf(block.handler);
            if (start < 0 || end <= start || target < 0)
                throw new MethodCompiler.Unsupported("invalid exception handler range");
            if (discardsThrowable(block.handler, loadedLocals)) discarded.add(block.handler);
            else retainsThrowable = true;

            edges += end - start;
            if (edges > ANALYSIS_EDGE_LIMIT)
                throw new MethodCompiler.Unsupported("exception table exceeds the translation edge limit");
            for (int i = start; i < end; i++) {
                List<TryCatchBlockNode> handlers = active.get(i);
                if (handlers == null) {
                    handlers = new ArrayList<>();
                    active.set(i, handlers);
                }
                handlers.add(block);
            }
        }

        var routes = new LinkedHashMap<List<TryCatchBlockNode>, Route>();
        var sites = new Route[size];
        for (int i = 0; i < size; i++) {
            if (active.get(i) == null) continue;
            var key = List.copyOf(active.get(i));
            Route route = routes.get(key);
            if (route == null) {
                route = new Route(i, key);
                routes.put(key, route);
            }
            sites[i] = route;
        }
        return new ExceptionHandlers(sites, List.copyOf(routes.values()), Set.copyOf(discarded), retainsThrowable);
    }

    private static boolean discardsThrowable(LabelNode target, BitSet loadedLocals) {
        AbstractInsnNode instruction = target.getNext();
        while (instruction != null && instruction.getOpcode() < 0) instruction = instruction.getNext();
        if (instruction == null) return false;
        if (instruction.getOpcode() == POP) return true;
        return instruction instanceof VarInsnNode store && store.getOpcode() == ASTORE && !loadedLocals.get(store.var);
    }

    Supplier<String> failure(int index, Supplier<String> uncaught) {
        Route route = sites.length == 0 ? null : sites[index];
        if (route == null) return uncaught;
        return () -> {
            route.used = true;
            if (!referenced[index]) {
                referenced[index] = true;
                localReferences++;
            }
            return "goto " + route.label() + ";";
        };
    }

    List<Route> routes() { return routes; }
    boolean discards(LabelNode handler) { return discarded.contains(handler); }
    boolean retainsThrowable() { return retainsThrowable; }
    int localReferences() { return retainsThrowable ? localReferences : Math.min(localReferences, 1); }
}

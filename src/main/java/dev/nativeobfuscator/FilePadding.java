package dev.nativeobfuscator;

import java.io.IOException;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.objectweb.asm.Opcodes.*;

/** Deterministic utility generation. The inventory is a build input, never a JAR resource. */
final class FilePadding {
    record Generation(Map<String, byte[]> classes, Map<String, String> nativeMethods) {}
    private record Reference(String name, int version, int bytes, int fields, int methods, int natives) {}
    private static final String CALIBRATION_LOADER = "dev/nativeobfuscator/runtime/Runtime0123456789abcdef0123456789abcdef";
    private static final String[] FIRST = {"Range", "Index", "Window", "Value", "Sample", "Batch", "Buffer", "Sequence", "Bucket", "Interval", "Segment", "Vector", "Stream", "Record", "Block", "Page", "Entry", "Offset", "Count", "Point", "Span", "Grid", "Frame", "Table", "Metric", "Limit", "Bound", "Slice", "Chunk", "Group", "Series", "Scale"};
    private static final String[] LAST = {"Math", "Support", "Operations", "Values", "Policy", "Rules", "Calculator", "Helper", "Summary", "Statistics", "Limits", "Bounds", "Accumulator", "Measure", "Layout", "Sizing", "Mapping", "Reduction", "Counter", "Analysis", "Transform", "Selection", "Search", "Checks", "Model", "State", "Options", "Format", "Ordering", "Projection", "Estimate", "Adapter"};
    private FilePadding() {}

    static Generation generate(Map<String, byte[]> input, int count, long seed) throws IOException {
        if (count < 0 || count > 1000) throw new IllegalArgumentException("padding count must be between 0 and 1000");
        return generate(input, count, seed, Set.of());
    }

    static Generation generate(Map<String, byte[]> input, int count, long seed, Set<String> plannedNativeMethods) throws IOException {
        if (count < 0 || count > 1000) throw new IllegalArgumentException("padding count must be between 0 and 1000");
        var classes = new LinkedHashMap<String, byte[]>();
        var selected = new LinkedHashMap<String, String>();
        if (count == 0) return new Generation(classes, selected);
        var occupied = new HashSet<String>();
        var references = new ArrayList<Reference>();
        for (var entry : input.entrySet()) {
            if (!entry.getKey().endsWith(".class")) continue;
            try {
                var node = new ClassNode();
                new ClassReader(entry.getValue()).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                occupied.add(node.name);
                if (entry.getKey().startsWith("META-INF/") || node.name.equals("module-info") || (node.access & (ACC_INTERFACE | ACC_ANNOTATION)) != 0) continue;
                int methods = (int) node.methods.stream().filter(m -> !m.name.startsWith("<")).count();
                int natives = 0;
                var planned = new HashSet<String>();
                for (MethodNode method : node.methods) {
                    if (plannedNativeMethods.contains(node.name + "#" + method.name + method.desc)) {
                        planned.add(method.name + method.desc);
                    }
                    if ((method.access & ACC_NATIVE) != 0 || planned.contains(method.name + method.desc)) natives++;
                }
                byte[] finalReference = planned.isEmpty() ? entry.getValue() : Protector.rewriteExpected(entry.getValue(), CALIBRATION_LOADER, 0, planned);
                references.add(new Reference(node.name, node.version, finalReference.length, node.fields.size(), methods, natives));
            } catch (RuntimeException failure) {
                throw new IOException("Cannot inspect padding size reference " + entry.getKey(), failure);
            }
        }
        if (references.isEmpty()) throw new IOException("Padding requires a genuine concrete input class as its version and structure reference");
        var nativeReferences = references.stream().filter(r -> r.natives > 0).toList();
        List<Reference> pool = nativeReferences.isEmpty() ? references : nativeReferences;
        var random = new Random(seed);
        var shapes = new HashSet<String>();
        var bodies = new HashSet<String>();
        var normalizedBodies = new HashSet<String>();
        // A shuffled traversal avoids a small sample repeatedly selecting the same class shape.
        var schedule = new ArrayList<>(pool);
        for (int index = 0; index < count; index++) {
            if (index % schedule.size() == 0) Collections.shuffle(schedule, random);
            Reference sample = schedule.get(index % schedule.size());
            String name;
            int attempts = 0;
            do {
                String base = sample.name.substring(0, Math.max(0, sample.name.lastIndexOf('/') + 1));
                String leaf = FIRST[random.nextInt(FIRST.length)] + LAST[random.nextInt(LAST.length)];
                if (random.nextInt(4) == 0) leaf = FIRST[random.nextInt(FIRST.length)] + leaf;
                name = base + leaf;
                if (++attempts > 100000) throw new IOException("Unable to allocate an unused utility class name");
            } while (occupied.contains(name) || input.containsKey(name + ".class"));
            occupied.add(name);
            int methodCount = Math.max(2, Math.min(64, sample.methods));
            // Preserve the observed Java/native mix where available; ordinary Java inputs need one conversion.
            int nativeCount = Math.max(1, Math.min(methodCount - 1, sample.natives));
            long classSeed = random.nextLong();
            Candidate best = null;
            // Size comparisons use stripped native bodies rather than the pre-conversion bytecode.
            // Use the production rewriter so registration initializer and constant-pool costs are included.
            for (int retry = 0; retry < 1000; retry++) {
                best = null;
                for (int candidateMethods = nativeCount + 1; candidateMethods <= Math.min(64, methodCount + 2); candidateMethods++) {
                    Candidate candidate = create(name, sample, candidateMethods, nativeCount, classSeed);
                    if (best == null || Math.abs(candidate.stubBytes - sample.bytes) < Math.abs(best.stubBytes - sample.bytes)) best = candidate;
                }
                var candidateBodies = bodies(best);
                var candidateNormalized = normalizedBodies(best);
                if (!shapes.contains(shape(best)) && candidateBodies.size() == substantiveMethodCount(best)
                    && candidateNormalized.size() == retainedMethodCount(best)
                    && Collections.disjoint(bodies, candidateBodies) && Collections.disjoint(normalizedBodies, candidateNormalized)) {
                    shapes.add(shape(best)); bodies.addAll(candidateBodies); normalizedBodies.addAll(candidateNormalized); break;
                }
                classSeed = random.nextLong();
                if (retry == 999) throw new IOException("Unable to diversify utility implementations at the requested structure budget");
            }
            classes.put(name + ".class", best.bytes);
            selected.putAll(best.selected);
        }
        return new Generation(Collections.unmodifiableMap(classes), Collections.unmodifiableMap(selected));
    }

    private record Candidate(byte[] bytes, int stubBytes, Map<String, String> selected) {}
    private static int substantiveMethodCount(Candidate candidate) {
        var node = new ClassNode(); new ClassReader(candidate.bytes).accept(node, 0);
        return (int) node.methods.stream().filter(m -> !m.name.startsWith("<") && !m.name.equals("translate")).count();
    }
    private static Set<String> bodies(Candidate candidate) {
        var node = new ClassNode(); new ClassReader(candidate.bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        var bodies = new HashSet<String>();
        for (MethodNode method : node.methods) {
            if (method.name.startsWith("<") || method.name.equals("translate")) continue;
            var body = new StringBuilder(method.desc).append(':');
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction.getOpcode() < 0) continue;
                body.append(instruction.getOpcode()).append(',');
                if (instruction instanceof VarInsnNode variable) body.append(variable.var);
                if (instruction instanceof LdcInsnNode constant) body.append(constant.cst);
                if (instruction instanceof IntInsnNode operand) body.append(operand.operand);
                if (instruction instanceof IincInsnNode increment) body.append(increment.var).append(':').append(increment.incr);
                if (instruction instanceof JumpInsnNode branch) body.append(method.instructions.indexOf(branch.label));
                body.append(';');
            }
            bodies.add(body.toString());
        }
        return bodies;
    }
    private static int retainedMethodCount(Candidate candidate) {
        var node = new ClassNode(); new ClassReader(candidate.bytes).accept(node, 0);
        return (int) node.methods.stream().filter(m -> !m.name.startsWith("<") && !m.name.equals("translate") && !candidate.selected.containsKey(node.name + "#" + m.name + m.desc)).count();
    }
    private static Set<String> normalizedBodies(Candidate candidate) {
        var node = new ClassNode(); new ClassReader(candidate.bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        var bodies = new HashSet<String>();
        for (MethodNode method : node.methods) {
            if (method.name.startsWith("<") || method.name.equals("translate") || candidate.selected.containsKey(node.name + "#" + method.name + method.desc)) continue;
            var body = new StringBuilder(method.desc).append(':');
            for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) body.append(instruction.getOpcode()).append(',');
            bodies.add(body.toString());
        }
        return bodies;
    }
    private static String shape(Candidate candidate) {
        var node = new ClassNode(); new ClassReader(candidate.bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        var shape = new StringBuilder();
        for (MethodNode method : node.methods) {
            if (method.name.startsWith("<")) continue;
            shape.append(method.desc).append(':');
            if (candidate.selected.containsKey(node.name + "#" + method.name + method.desc)) { shape.append("native;"); continue; }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction.getOpcode() < 0) continue;
                shape.append(instruction.getOpcode()).append(',');
                if (instruction instanceof VarInsnNode variable) shape.append(variable.var).append(',');
            }
            shape.append(';');
        }
        return shape.toString();
    }
    private static Candidate create(String name, Reference sample, int methods, int nativeCount, long seed) throws IOException {
        var random = new Random(seed);
        var node = new ClassNode();
        node.version = sample.version;
        node.access = ACC_PUBLIC | ACC_SUPER | (random.nextBoolean() ? ACC_FINAL : 0);
        node.name = name;
        node.superName = "java/lang/Object";
        int fieldCount = Math.max(0, Math.min(64, sample.fields + random.nextInt(3) - 1));
        for (int i = 0; i < fieldCount; i++) {
            int constant = 2 + random.nextInt(4095);
            String fieldName = FIRST[i % FIRST.length].toLowerCase(java.util.Locale.ROOT) + LAST[i / FIRST.length];
            int access = ACC_PUBLIC | ACC_STATIC | ACC_FINAL;
            if (i == 0 && random.nextBoolean()) {
                node.fields.add(new FieldNode(ACC_PRIVATE, "offset", "J", null, null));
                continue;
            }
            switch (random.nextInt(4)) {
                case 0 -> node.fields.add(new FieldNode(access, fieldName, "J", null, (long) constant));
                case 1 -> node.fields.add(new FieldNode(access, fieldName, "D", null, constant / 10.0));
                case 2 -> node.fields.add(new FieldNode(access, fieldName, "Z", null, constant & 1));
                default -> node.fields.add(new FieldNode(access, fieldName, "I", null, constant));
            }
        }
        MethodNode init = new MethodNode(random.nextBoolean() ? ACC_PUBLIC : ACC_PRIVATE, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(ALOAD, 0); init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); init.visitInsn(RETURN); init.visitMaxs(0, 0); init.visitEnd(); node.methods.add(init);
        var selected = new LinkedHashMap<String, String>();
        boolean state = node.fields.stream().anyMatch(f -> f.name.equals("offset"));
        if (state) {
            MethodNode constructor = new MethodNode(ACC_PUBLIC, "<init>", "(J)V", null, null);
            constructor.visitCode(); constructor.visitVarInsn(ALOAD, 0); constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            constructor.visitVarInsn(ALOAD, 0); constructor.visitVarInsn(LLOAD, 1); constructor.visitFieldInsn(PUTFIELD, name, "offset", "J"); constructor.visitInsn(RETURN); end(constructor); node.methods.add(constructor);
        }
        for (int i = 0; i < methods; i++) {
            int family = random.nextInt(3);
            MethodNode method;
            if (i < nativeCount) {
                method = random.nextInt(4) == 0 ? array(family, i, random, 8) : numeric(family, i, random);
            } else {
                if (state && i == nativeCount) {
                    method = new MethodNode(ACC_PUBLIC, "translate", "(J)J", null, null); method.visitCode();
                    method.visitVarInsn(ALOAD, 0); method.visitFieldInsn(GETFIELD, name, "offset", "J"); method.visitVarInsn(LLOAD, 1); method.visitInsn(LADD); method.visitInsn(LRETURN); end(method);
                } else method = random.nextBoolean() ? array(family, i, random, Math.max(4, Math.min(96, sample.bytes / methods / 4))) : otherArray(i, random);
            }
            String operation = method.name.replaceAll("[0-9]+$", "");
            String[] alternatives = switch (operation) {
                case "project" -> new String[]{"project", "constrain", "clamp", "normalize", "restrict"};
                case "capacity" -> new String[]{"capacity", "reserve", "grow", "allocate", "expand"};
                case "accumulate" -> new String[]{"accumulate", "combine", "aggregate", "reduce", "incorporate"};
                default -> new String[]{operation};
            };
            String baseName = alternatives[random.nextInt(alternatives.length)];
            if (!operation.equals("translate") && random.nextBoolean()) baseName += FIRST[random.nextInt(FIRST.length)];
            method.name = baseName;
            int suffix = 2;
            while (contains(node.methods, method.name, method.desc)) method.name = baseName + suffix++;
            if (i < nativeCount) selected.put(name + "#" + method.name + method.desc, switch (family) { case 0 -> "range"; case 1 -> "collection"; default -> "summary"; });
            node.methods.add(method);
        }
        byte[] original = write(node);
        var signatures = new HashSet<String>();
        for (MethodNode m : node.methods) if (selected.containsKey(name + "#" + m.name + m.desc)) signatures.add(m.name + m.desc);
        return new Candidate(original, Protector.rewriteExpected(original, CALIBRATION_LOADER, 0, signatures).length, selected);
    }
    private static boolean contains(List<MethodNode> methods, String name, String descriptor) {
        return methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(descriptor));
    }
    private static byte[] write(ClassNode node) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer); return writer.toByteArray();
    }
    private static MethodNode begin(String name, String desc) {
        var m = new MethodNode(ACC_PUBLIC | ACC_STATIC, name, desc, null, null); m.visitCode(); return m;
    }
    private static MethodNode end(MethodNode m) { m.visitMaxs(0, 0); m.visitEnd(); return m; }
    private static void number(MethodVisitor m, int n) { m.visitLdcInsn(n); }

    /** Range transformations, bounded growth policies, and streaming summary kernels. */
    private static MethodNode numeric(int family, int index, Random r) {
        int variants = 1 + r.nextInt(7);
        if (family == 0) {
            MethodNode m = begin("project" + index, "(III)I");
            // Repeated threshold operations are a piecewise affine range projection.
            for (int step = 0; step < variants; step++) {
                Label upper = new Label(), done = new Label();
                m.visitVarInsn(ILOAD, 0); m.visitVarInsn(ILOAD, 1); m.visitJumpInsn(IF_ICMPGE, upper);
                m.visitVarInsn(ILOAD, 1); m.visitVarInsn(ISTORE, 0); m.visitJumpInsn(GOTO, done);
                m.visitLabel(upper); m.visitVarInsn(ILOAD, 0); m.visitVarInsn(ILOAD, 2); m.visitJumpInsn(IF_ICMPLE, done);
                m.visitVarInsn(ILOAD, 2); m.visitVarInsn(ISTORE, 0); m.visitLabel(done);
                if (step + 1 < variants) {
                    m.visitVarInsn(ILOAD, 0); number(m, r.nextInt(31) - 15); m.visitInsn(IADD); m.visitVarInsn(ISTORE, 0);
                    if (r.nextBoolean()) { m.visitVarInsn(ILOAD, 0); number(m, 1 + r.nextInt(5)); m.visitInsn(IDIV); m.visitVarInsn(ISTORE, 0); }
                }
            }
            m.visitVarInsn(ILOAD, 0); m.visitInsn(IRETURN); return end(m);
        }
        if (family == 1) {
            MethodNode m = begin("capacity" + index, "(III)I");
            // Current capacity, required capacity, ceiling. Arithmetic uses long to avoid overflow.
            m.visitVarInsn(ILOAD, 0); m.visitInsn(I2L); m.visitVarInsn(LSTORE, 3);
            for (int step = 0; step < variants; step++) {
                Label done = new Label();
                m.visitVarInsn(LLOAD, 3); m.visitVarInsn(ILOAD, 1); m.visitInsn(I2L);
                m.visitInsn(LCMP); m.visitJumpInsn(IFGE, done);
                m.visitVarInsn(LLOAD, 3); m.visitVarInsn(LLOAD, 3); number(m, 1 + r.nextInt(3)); m.visitInsn(LSHR); m.visitInsn(LADD);
                m.visitLdcInsn((long) (1 + r.nextInt(16))); m.visitInsn(LADD); m.visitVarInsn(LSTORE, 3); m.visitLabel(done);
            }
            Label cap = new Label(), ret = new Label();
            m.visitVarInsn(LLOAD, 3); m.visitVarInsn(ILOAD, 1); m.visitInsn(I2L); m.visitInsn(LCMP); m.visitJumpInsn(IFGE, cap);
            m.visitVarInsn(ILOAD, 1); m.visitInsn(I2L); m.visitVarInsn(LSTORE, 3); m.visitLabel(cap);
            m.visitVarInsn(LLOAD, 3); m.visitVarInsn(ILOAD, 2); m.visitInsn(I2L); m.visitInsn(LCMP); m.visitJumpInsn(IFLE, ret);
            m.visitVarInsn(ILOAD, 2); m.visitInsn(IRETURN); m.visitLabel(ret); m.visitVarInsn(LLOAD, 3); m.visitInsn(L2I); m.visitInsn(IRETURN); return end(m);
        }
        MethodNode m = begin("accumulate" + index, "(JII)J");
        // Weighted combination of a prior summary, count, and next observed value.
        for (int step = 0; step < variants; step++) {
            m.visitVarInsn(LLOAD, 0); m.visitVarInsn(ILOAD, r.nextBoolean() ? 2 : 3); m.visitInsn(I2L);
            m.visitLdcInsn((long) (1 + r.nextInt(31))); m.visitInsn(LMUL);
            m.visitInsn(r.nextBoolean() ? LADD : LSUB); m.visitVarInsn(LSTORE, 0);
            if (r.nextBoolean()) { m.visitVarInsn(LLOAD, 0); number(m, 1 + r.nextInt(7)); m.visitInsn(LSHR); m.visitVarInsn(LSTORE, 0); }
        }
        m.visitVarInsn(LLOAD, 0); m.visitInsn(LRETURN); return end(m);
    }


    private static MethodNode otherArray(int index, Random r) {
        int kind = r.nextInt(4);
        String descriptor = switch (kind) { case 0 -> "([BI)I"; case 1 -> "([JI)J"; case 2 -> "([DD)D"; case 3 -> "([III)I"; default -> "(II)J"; };
        MethodNode m = begin(switch(kind) { case 0 -> "locate"; case 1 -> "reduce"; case 2 -> "distance"; case 3 -> "lowerBound"; default -> "greatestDivisor"; } + index, descriptor);
        // Compose scale, offset, quantization and saturation policies before the search/reduction.
        for (int stage = 0, stages = 6 + r.nextInt(13); stage < stages; stage++) {
            int operation = r.nextInt(7);
            if (kind == 2) {
                if (operation == 5) { m.visitVarInsn(DLOAD, 1); m.visitInsn(DNEG); m.visitVarInsn(DSTORE, 1); }
                else if (operation == 6) {
                    Label positive = new Label(); m.visitVarInsn(DLOAD, 1); m.visitInsn(DCONST_0); m.visitInsn(DCMPL); m.visitJumpInsn(IFGE, positive);
                    m.visitVarInsn(DLOAD, 1); m.visitInsn(DNEG); m.visitVarInsn(DSTORE, 1); m.visitLabel(positive);
                } else {
                    m.visitVarInsn(DLOAD, 1); m.visitLdcInsn((r.nextInt(10000) + 1) / 100.0);
                    m.visitInsn(switch(operation) { case 0 -> DMUL; case 1 -> DADD; case 2 -> DSUB; case 3 -> DDIV; default -> DREM; }); m.visitVarInsn(DSTORE, 1);
                }
            } else {
                if (operation == 5) { m.visitVarInsn(ILOAD, 1); m.visitInsn(INEG); m.visitVarInsn(ISTORE, 1); }
                else if (operation == 6) {
                    Label positive = new Label(); m.visitVarInsn(ILOAD, 1); m.visitJumpInsn(IFGE, positive);
                    m.visitVarInsn(ILOAD, 1); m.visitInsn(INEG); m.visitVarInsn(ISTORE, 1); m.visitLabel(positive);
                } else {
                    m.visitVarInsn(ILOAD, 1); number(m, 1 + r.nextInt(10000));
                    m.visitInsn(switch(operation) { case 0 -> IAND; case 1 -> IADD; case 2 -> ISUB; case 3 -> IDIV; default -> IREM; }); m.visitVarInsn(ISTORE, 1);
                }
            }
        }
        if (kind == 3) {
            // Lower bound in [from, length), with the key supplied independently.
            m.visitVarInsn(ALOAD, 0); m.visitInsn(ARRAYLENGTH); m.visitVarInsn(ISTORE, 3);
            Label loop = new Label(), done = new Label(), lower = new Label(); m.visitLabel(loop);
            m.visitVarInsn(ILOAD, 2); m.visitVarInsn(ILOAD, 3); m.visitJumpInsn(IF_ICMPGE, done);
            m.visitVarInsn(ILOAD, 2); m.visitVarInsn(ILOAD, 3); m.visitVarInsn(ILOAD, 2); m.visitInsn(ISUB); m.visitInsn(ICONST_2); m.visitInsn(IDIV); m.visitInsn(IADD); m.visitVarInsn(ISTORE, 4);
            m.visitVarInsn(ALOAD, 0); m.visitVarInsn(ILOAD, 4); m.visitInsn(IALOAD); m.visitVarInsn(ILOAD, 1); m.visitJumpInsn(IF_ICMPLT, lower);
            m.visitVarInsn(ILOAD, 4); m.visitVarInsn(ISTORE, 3); m.visitJumpInsn(GOTO, loop); m.visitLabel(lower); m.visitVarInsn(ILOAD, 4); m.visitInsn(ICONST_1); m.visitInsn(IADD); m.visitVarInsn(ISTORE, 2); m.visitJumpInsn(GOTO, loop);
            m.visitLabel(done); m.visitVarInsn(ILOAD, 2); m.visitInsn(IRETURN); return end(m);
        }
        int local = kind == 2 ? 3 : 2;
        m.visitInsn(ICONST_0); m.visitVarInsn(ISTORE, local);
        if (kind > 0) { m.visitInsn(kind == 2 ? DCONST_0 : LCONST_0); m.visitVarInsn(kind == 2 ? DSTORE : LSTORE, local + 1); }
        Label loop = new Label(), done = new Label(), next = new Label(); m.visitLabel(loop);
        m.visitVarInsn(ILOAD, local); m.visitVarInsn(ALOAD, 0); m.visitInsn(ARRAYLENGTH); m.visitJumpInsn(IF_ICMPGE, done);
        if (kind == 0) {
            m.visitVarInsn(ALOAD, 0); m.visitVarInsn(ILOAD, local); m.visitInsn(BALOAD); m.visitVarInsn(ILOAD, 1); m.visitJumpInsn(IF_ICMPNE, next);
            m.visitVarInsn(ILOAD, local); m.visitInsn(IRETURN);
        } else {
            m.visitVarInsn(kind == 2 ? DLOAD : LLOAD, local + 1);
            m.visitVarInsn(ALOAD, 0); m.visitVarInsn(ILOAD, local); m.visitInsn(kind == 2 ? DALOAD : LALOAD);
            if (kind == 2) { m.visitVarInsn(DLOAD, 1); m.visitInsn(DSUB); if (r.nextBoolean()) { m.visitInsn(DUP2); m.visitInsn(DMUL); } }
            else { m.visitVarInsn(ILOAD, 1); number(m, 63); m.visitInsn(IAND); m.visitInsn(LSHR); }
            m.visitInsn(kind == 2 ? DADD : LADD); m.visitVarInsn(kind == 2 ? DSTORE : LSTORE, local + 1);
        }
        m.visitLabel(next); m.visitIincInsn(local, 1 + r.nextInt(3)); m.visitJumpInsn(GOTO, loop); m.visitLabel(done);
        if (kind == 0) { m.visitInsn(ICONST_M1); m.visitInsn(IRETURN); }
        else { m.visitVarInsn(kind == 2 ? DLOAD : LLOAD, local + 1); m.visitInsn(kind == 2 ? DRETURN : LRETURN); }
        return end(m);
    }

    /** Retained Java array routines keep real executable behavior after JNI stubbing. */
    private static MethodNode array(int family, int index, Random r, int complexity) {
        int mode = r.nextInt(6), stride = 1 + r.nextInt(4), threshold = r.nextInt(257) - 128;
        MethodNode m = begin((family == 0 ? "measure" : family == 1 ? "count" : "summarize") + index, "([I)J");
        m.visitInsn(LCONST_0); m.visitVarInsn(LSTORE, 1); m.visitInsn(ICONST_0); m.visitVarInsn(ISTORE, 3);
        Label loop = new Label(), done = new Label(), skip = new Label(); m.visitLabel(loop);
        m.visitVarInsn(ILOAD, 3); m.visitVarInsn(ALOAD, 0); m.visitInsn(ARRAYLENGTH); m.visitJumpInsn(IF_ICMPGE, done);
        m.visitVarInsn(ALOAD, 0); m.visitVarInsn(ILOAD, 3); m.visitInsn(IALOAD); m.visitVarInsn(ISTORE, 4);
        // Compose real per-element projections, making the reduction useful for different domains.
        // Operation order changes bytecode structure, rather than only changing constants.
        for (int stage = 0, stages = 6 + r.nextInt(complexity); stage < stages; stage++) {
            switch (r.nextInt(7)) {
                case 0 -> { m.visitVarInsn(ILOAD, 4); number(m, 1 + r.nextInt(17)); m.visitInsn(IDIV); m.visitVarInsn(ISTORE, 4); }
                case 1 -> { m.visitVarInsn(ILOAD, 4); number(m, r.nextInt(31)); m.visitInsn(IADD); m.visitVarInsn(ISTORE, 4); }
                case 2 -> { m.visitVarInsn(ILOAD, 4); number(m, 1 + r.nextInt(31)); m.visitInsn(IAND); m.visitVarInsn(ISTORE, 4); }
                case 3 -> { Label positive = new Label(); m.visitVarInsn(ILOAD, 4); m.visitJumpInsn(IFGE, positive); m.visitVarInsn(ILOAD, 4); m.visitInsn(INEG); m.visitVarInsn(ISTORE, 4); m.visitLabel(positive); }
                case 4 -> { Label below = new Label(); m.visitVarInsn(ILOAD, 4); number(m, threshold); m.visitJumpInsn(IF_ICMPLE, below); number(m, threshold); m.visitVarInsn(ISTORE, 4); m.visitLabel(below); }
                case 5 -> { m.visitVarInsn(ILOAD, 4); m.visitVarInsn(ILOAD, 3); m.visitInsn(ISUB); m.visitVarInsn(ISTORE, 4); }
                default -> { m.visitVarInsn(ILOAD, 4); number(m, 1 + r.nextInt(5)); m.visitInsn(ISHR); m.visitVarInsn(ISTORE, 4); }
            }
        }
        if (mode == 0 || mode == 1) { m.visitVarInsn(ILOAD, 4); number(m, threshold); m.visitJumpInsn(mode == 0 ? IF_ICMPLT : IF_ICMPGT, skip); }
        m.visitVarInsn(LLOAD, 1);
        if (mode < 2) m.visitInsn(LCONST_1);
        else { m.visitVarInsn(ILOAD, 4); m.visitInsn(I2L); if (mode == 3) { m.visitVarInsn(ILOAD, 4); m.visitInsn(I2L); m.visitInsn(LMUL); } else if (mode == 4) { m.visitLdcInsn((long) (1 + r.nextInt(15))); m.visitInsn(LMUL); } else if (mode == 5) { m.visitVarInsn(ILOAD, 3); m.visitInsn(I2L); m.visitInsn(LMUL); } }
        m.visitInsn(LADD); m.visitVarInsn(LSTORE, 1); m.visitLabel(skip); m.visitIincInsn(3, stride); m.visitJumpInsn(GOTO, loop);
        m.visitLabel(done); m.visitVarInsn(LLOAD, 1); m.visitInsn(LRETURN); return end(m);
    }
}

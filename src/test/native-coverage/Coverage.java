import java.util.Random;

/** Differential fixture: helper targets remain Java unless explicitly selected. */
public class Coverage {
    private int count = 7;
    private long wide = Long.MIN_VALUE;
    private Object object;
    private static long total = 9;
    private static volatile int published;
    private static int publicationPayload;
    private int synchronizedCount;
    private static Object shared;
    private static int effects;
    private byte signedByte;
    private char unsignedChar;
    private short signedShort;
    private boolean flag;
    private float fraction;
    private double precise;

    public static long covSummary(int[] values) {
        long sum = 0;
        for (int i = 0; i < values.length; i++) sum += values[i];
        return sum;
    }
    public static int covInt(int[] a, int i, int v) { a[i] = v; return a[i]; }
    public static long covLong(long[] a, int i, long v) { return a[i] += v; }
    public static float covFloat(float[] a, int i, float v) { a[i] = v; return a[i]; }
    public static double covDouble(double[] a, int i, double v) { return a[i] += v; }
    public static int covByte(byte[] a, int i, int v) { a[i] = (byte)v; return a[i]; }
    public static int covBoolean(boolean[] a, int i, boolean v) { a[i] = v; return a[i] ? 1 : 0; }
    public static int covChar(char[] a, int i, int v) { a[i] = (char)v; return a[i]; }
    public static int covShort(short[] a, int i, int v) { a[i] = (short)v; return a[i]; }
    public static boolean covReference(Object[] a, int i, Object v) { a[i] = v; return a[i] == v; }
    public static boolean covNested(int[][] a, int i, int[] v) { a[i] = v; return a[i] == v; }
    public static int covLength(Object[] a) { return a.length; }
    public int covFields(Object v, int delta) { object = v; count += delta; wide += delta; return object == v ? count + (int)wide : -1; }
    public static boolean covStaticFields(Object v, long delta) { shared = v; total += delta; return shared == v && total != 0; }
    public static int covReceiverField(Coverage receiver) { return receiver.count; }
    public double covTypedFields(byte b, char c, short s, boolean z, float f, double d) {
        signedByte = b; unsignedChar = c; signedShort = s; flag = z; fraction = f; precise = d;
        return signedByte + unsignedChar + signedShort + (flag ? 1 : 0) + fraction + precise;
    }
    public static int covNarrowCalls(byte b, char c, short s, boolean z) { return byteCall(b) + charCall(c) + shortCall(s) + (booleanCall(z) ? 1 : 0); }
    private static byte byteCall(byte b) { return b; }
    private static char charCall(char c) { return c; }
    private static short shortCall(short s) { return s; }
    private static boolean booleanCall(boolean z) { return z; }
    public static float covFloatCall(float f) { return floatCall(f); }
    private static float floatCall(float f) { return f; }
    public static double covDoubleCall(double d) { return doubleCall(d); }
    private static double doubleCall(double d) { return d; }
    public static long covStaticCall(long a, double b, byte c, char d, short e, boolean f) { return helper(a, b, c, d, e, f); }
    private static long helper(long a, double b, byte c, char d, short e, boolean f) { return a + (long)b + c + d + e + (f ? 1 : 0); }
    public int covVirtualCall(Coverage receiver, int a) { return receiver.dispatch(a); }
    public int dispatch(int a) { return count + a; }
    public int covPrivateCall(int a) { return privateCall(a); }
    private int privateCall(int a) { return count - a; }
    public static int covCallArray(int[] v, int i) { return arrayIdentity(v)[i]; }
    private static int[] arrayIdentity(int[] v) { return v; }
    public static boolean covCallIdentity(Object v) { return identity(v) == v; }
    private static Object identity(Object v) { return v; }
    public Coverage covReturnThis() { return this; }
    public static String covReturnString(String value, boolean keep) { return keep ? value : null; }
    public static Object[][] covReturnCovariant(String[][] value) { return value; }
    public static int[] covReturnArray(int[][] value, int index) { return value[index]; }
    public static Object covReturnMerged(boolean chooseLeft, String left, Integer right) {
        return chooseLeft ? left : right;
    }
    public static Object covReturnCall(Object value, int denominator) { return StaticPeer.identity(value, denominator); }
    public static String[][] covCheckedArray(Object value) { return (String[][]) value; }
    public static boolean covInstanceOfArray(Object value) { return value instanceof String[][]; }
    public static byte[] covAllocatePrimitiveArray(int length) { return new byte[length]; }
    public static String[][] covAllocateReferenceArray(int length) { return new String[length][]; }
    public static long[][][] covAllocateDimensions(int a, int b) { return new long[a][b][]; }
    public static int rejectAllocationLoop(int count) {
        int size = 0;
        for (int i = 0; i < count; i++) size += new int[i].length;
        return size;
    }
    public static int covCastLoop(Object value, int count) {
        int nonNull = 0;
        for (int i = 0; i < count; i++) if ((String) value != null) nonNull++;
        return nonNull;
    }
    private static int linkState;
    private static class StaticBase {
        static { linkState += 1; }
        static int inherited(int value) { return value + 5; }
    }
    private static final class StaticChild extends StaticBase {
        static { linkState += 10; }
        static int own(int value) { return value - 3; }
    }
    private interface StaticInterface {
        static long combine(long value, double fraction, int[] data) { return value + (long)fraction + data[0]; }
    }
    private static class StaticPeer {
        private static Object identity(Object value, int denominator) {
            int ignored = 1 / denominator;
            return value;
        }
    }
    private static class StaticFail {
        static { fail(); }
        private static void fail() { linkState += 100; throw new IllegalStateException(); }
        static void run() {}
    }
    public static int covLinkageLazy(boolean take, int value) { return take ? StaticChild.inherited(value) : value; }
    public static int covLinkageOwn(int value) { return StaticChild.own(value); }
    public static long covLinkageInterface(long value, double fraction, int[] data) {
        return StaticInterface.combine(value, fraction, data);
    }
    public static boolean covLinkagePrivate(Object value, int denominator) {
        Object returned = StaticPeer.identity(value, denominator);
        effects++;
        return returned == value;
    }
    public static void covLinkageFailedInit() { StaticFail.run(); effects++; }
    public static int covPending(int denominator) { throwing(denominator); effects++; return effects; }
    private static void throwing(int denominator) { effects += 10 / denominator; }
    public static int covPendingArray(int[] a, int i) { int value = a[i]; effects++; return value; }

    // These must be retained, with explicit diagnostics.
    private static int fieldState;
    private static class FieldBase {
        static { fieldState += 1; }
        static int count = 12;
    }
    private static final class FieldChild extends FieldBase {
        static { fieldState += 10; }
    }
    private static final class FieldPeer {
        private Object reference;
        private long wide;
        private volatile int published;
    }
    public static int covExternalSystemField() { return System.out == null ? 0 : 1; }
    public static int covExternalStaticField(boolean read) {
        if (!read) return 0;
        FieldChild.count += 5;
        return FieldChild.count;
    }
    private static long covExternalInstanceFields(FieldPeer peer, Object token, long delta) {
        peer.reference = token;
        peer.wide += delta;
        effects++;
        return peer.reference == token ? peer.wide : 0;
    }
    private static int covExternalVolatile(FieldPeer peer, int value) {
        peer.published = value;
        return peer.published;
    }
    public static void covPublish(int payload) { publicationPayload = payload; published = 1; }
    public static long covReadPublished() { return ((long) published << 32) | (publicationPayload & 0xffffffffL); }
    public synchronized int covSynchronizedIncrement() {
        return Thread.holdsLock(this) ? ++synchronizedCount : -1;
    }
    private static long publication() throws InterruptedException {
        published = 0;
        publicationPayload = 0;
        Thread writer = Thread.ofPlatform().start(() -> covPublish(0x12345678));
        long observed;
        do {
            observed = covReadPublished();
            if ((observed >>> 32) == 0) Thread.onSpinWait();
        } while ((observed >>> 32) == 0 && writer.isAlive());
        writer.join();
        return (observed >>> 32) == 0 ? covReadPublished() : observed;
    }
    private static int synchronization(Coverage instance) throws InterruptedException {
        Runnable increment = () -> {
            for (int i = 0; i < 256; i++) instance.covSynchronizedIncrement();
        };
        Thread first = Thread.ofPlatform().start(increment);
        Thread second = Thread.ofPlatform().start(increment);
        first.join();
        second.join();
        return instance.synchronizedCount;
    }
    private static class VirtualPeer {
        int scale(int value) { return value * 2; }
    }
    private static final class VirtualChild extends VirtualPeer {
        @Override int scale(int value) { return value * 3; }
    }
    private interface DispatchPeer {
        default long fold(long value, double fraction) { return value + (long)fraction; }
    }
    private static final class DefaultDispatch implements DispatchPeer {}
    private static final class OverrideDispatch implements DispatchPeer {
        @Override public long fold(long value, double fraction) { return value - (long)fraction; }
    }
    public static int covExternalVirtual(String value) { return value.length(); }
    private static int covExternalOverride(VirtualPeer peer, int value) { return peer.scale(value); }
    private static long covInterfaceDispatch(DispatchPeer peer, long value, double fraction) {
        return peer.fold(value, fraction);
    }
    private static class AllocationPeer {
        final long amount;
        final String label;
        static { fieldState += 100; }
        AllocationPeer(long amount, String label) {
            if (amount == 0) throw new IllegalArgumentException();
            this.amount = amount;
            this.label = label;
        }
    }
    public static Object covAllocateObject() { return new Object(); }
    private static AllocationPeer covConstruct(long amount, String label, boolean allocate) {
        if (!allocate) return null;
        AllocationPeer built = new AllocationPeer(amount, label);
        effects++;
        return built;
    }
    public static Object rejectAllocation(int size) { return new StringBuilder(Math.abs(size)); }
    public static String covConcat(String name, int count, long offset) { return name + "=" + count + "/" + offset; }
    public static String covConcatObject(Object token, int value) {
        String result = token + ":" + value;
        effects++;
        return result;
    }
    public static String rejectConcatLoop(String name, int count) {
        for (int i = 0; i < count; i++) name = name + i;
        return name;
    }
    public static int rejectHandler(int[] a) { try { return a[0]; } catch (NullPointerException e) { return 3; } }
    public static int rejectReferenceLoop(Object[] a) { int n = 0; for (int i = 0; i < a.length; i++) if (a[i] != null) n++; return n; }

    interface Attempt { Object run() throws Throwable; }
    private static void show(Attempt attempt) {
        try { System.out.println("value:" + attempt.run() + ":effects=" + effects); }
        catch (Throwable error) { System.out.println("exception:" + error.getClass().getName() + ":effects=" + effects); }
    }
    public static void main(String[] args) {
        Object token = new Object();
        Coverage instance = new Coverage();
        Coverage child = new Coverage() {
            @Override public int dispatch(int a) { return 100 + a; }
            public int privateCall(int a) { return 200 + a; }
        };
        show(() -> covSummary(new int[]{Integer.MIN_VALUE, -7, 0, Integer.MAX_VALUE}));
        show(() -> covSummary(null));
        show(() -> covSummary(new int[0]));
        for (int index : new int[]{-1, 0, 1, Integer.MAX_VALUE}) {
            show(() -> covInt(new int[1], index, Integer.MIN_VALUE));
            show(() -> covLong(new long[]{Long.MAX_VALUE}, index, 1));
            show(() -> covFloat(new float[1], index, Float.NaN));
            show(() -> covDouble(new double[]{-0.0}, index, -0.0));
            show(() -> covByte(new byte[1], index, 255));
            show(() -> covBoolean(new boolean[1], index, true));
            show(() -> covChar(new char[1], index, -1));
            show(() -> covShort(new short[1], index, 65535));
            show(() -> covReference(new Object[1], index, token));
            show(() -> covNested(new int[1][], index, new int[]{1}));
        }
        show(() -> covInt(null, 0, 1));
        show(() -> covLong(null, 0, 1));
        show(() -> covFloat(null, 0, 1));
        show(() -> covDouble(null, 0, 1));
        show(() -> covByte(null, 0, 1));
        show(() -> covBoolean(null, 0, true));
        show(() -> covChar(null, 0, 1));
        show(() -> covShort(null, 0, 1));
        show(() -> covReference(null, 0, token));
        show(() -> covNested(null, 0, null));
        show(() -> covReference(new String[1], 0, token));
        show(() -> covReference(new String[0], 0, token));
        show(() -> covReference(new String[1], 0, null));
        show(() -> covLength(null));
        show(() -> covLength(new Object[4]));
        show(() -> instance.covFields(token, Integer.MAX_VALUE));
        show(() -> instance.covFields(null, 1));
        show(() -> covStaticFields(token, Long.MAX_VALUE));
        show(() -> covStaticFields(null, 1));
        show(() -> covReceiverField(null));
        show(() -> covReceiverField(instance));
        show(() -> covStaticCall(Long.MAX_VALUE, Double.NaN, (byte)-128, (char)65535, (short)-32768, true));
        show(() -> instance.covVirtualCall(child, 4));
        show(() -> instance.covVirtualCall(null, 4));
        show(() -> instance.covPrivateCall(10));
        show(() -> child.covPrivateCall(10));
        show(() -> RawOps.covReferenceCapacity(new Object[]{token}));
        show(() -> RawOps.covReferenceCapacity(null));
        show(() -> RawOps.covReferenceCapacity(new Object[0]));
        show(() -> new RawOps().covSpecial(Integer.MIN_VALUE));
        show(() -> RawOps.rejectRawCall(257));
        show(() -> covCallArray(new int[]{-17}, 0));
        show(() -> covCallArray(null, 0));
        show(() -> covCallArray(new int[0], 0));
        show(() -> covLinkageLazy(false, 42));
        System.out.println("linkage-skipped:" + linkState);
        show(() -> covLinkageLazy(true, 42));
        System.out.println("linkage-inherited:" + linkState);
        show(() -> covLinkageOwn(42));
        System.out.println("linkage-child:" + linkState);
        show(() -> covLinkageInterface(Long.MAX_VALUE, -12.0, new int[]{17}));
        show(() -> covLinkageInterface(5, 12.0, null));
        show(() -> covLinkagePrivate(token, 1));
        show(() -> covLinkagePrivate(null, 1));
        show(() -> covLinkagePrivate(token, 0));
        show(() -> { covLinkageFailedInit(); return null; });
        show(() -> { covLinkageFailedInit(); return null; });
        System.out.println("linkage-failed:" + linkState);
        show(() -> RawOps.covLinkageRaw(257));
        show(RawOps::covLinkageMissing);
        show(RawOps::covExternalNull);
        show(() -> RawOps.covExternalProtected(new RawOps()));
        show(() -> RawOps.covExternalProtected(null));
        show(() -> covExternalVirtual("native"));
        show(() -> covExternalVirtual(null));
        show(() -> covExternalOverride(new VirtualPeer(), 7));
        show(() -> covExternalOverride(new VirtualChild(), 7));
        show(() -> covExternalOverride(null, 7));
        show(() -> covInterfaceDispatch(new DefaultDispatch(), Long.MAX_VALUE, 12.0));
        show(() -> covInterfaceDispatch(new OverrideDispatch(), Long.MAX_VALUE, 12.0));
        show(() -> covInterfaceDispatch(null, 7, 12.0));
        show(() -> covExternalStaticField(false));
        System.out.println("field-skipped:" + fieldState);
        show(() -> covExternalStaticField(true));
        System.out.println("field-inherited:" + fieldState);
        show(Coverage::covExternalSystemField);
        FieldPeer fieldPeer = new FieldPeer();
        show(() -> covExternalInstanceFields(fieldPeer, token, Long.MAX_VALUE));
        show(() -> covExternalInstanceFields(fieldPeer, null, 1));
        show(() -> covExternalInstanceFields(null, token, 1));
        show(() -> covExternalVolatile(fieldPeer, Integer.MIN_VALUE));
        show(Coverage::publication);
        show(() -> synchronization(instance));
        show(RawOps::covExternalMissingField);
        show(() -> covCallIdentity(token));
        show(() -> covCallIdentity(null));
        show(() -> instance.covReturnThis() == instance);
        String sameString = new String("same");
        show(() -> covReturnString(sameString, true) == sameString);
        show(() -> covReturnString(sameString, false) == null);
        String[][] strings = {{"value"}};
        show(() -> covReturnCovariant(strings) == strings);
        int[] ints = {17};
        show(() -> covReturnArray(new int[][]{ints}, 0) == ints);
        show(() -> covReturnArray(new int[][]{null}, 0) == null);
        show(() -> covReturnArray(new int[0][], 0));
        show(() -> covReturnArray(null, 0));
        Integer sameNumber = 25;
        show(() -> covReturnMerged(true, sameString, sameNumber) == sameString);
        show(() -> covReturnMerged(false, sameString, sameNumber) == sameNumber);
        show(() -> covReturnCall(token, 1) == token);
        show(() -> covReturnCall(token, 0));
        show(() -> covCheckedArray(strings) == strings);
        show(() -> covCheckedArray(null) == null);
        show(() -> covCheckedArray(new Object[1][]));
        show(() -> covInstanceOfArray(strings));
        show(() -> covInstanceOfArray(new Object[1][]));
        show(() -> covInstanceOfArray(null));
        show(() -> covCastLoop(sameString, 4096));
        show(() -> covCastLoop(null, 9));
        show(() -> covCastLoop(token, 0));
        show(() -> covCastLoop(token, 1));
        show(() -> RawOps.covCastMissing(null));
        show(() -> RawOps.covCastMissing(token));
        show(() -> covAllocatePrimitiveArray(5).length);
        show(() -> covAllocatePrimitiveArray(5)[4]);
        show(() -> covAllocatePrimitiveArray(-1));
        show(() -> covAllocateReferenceArray(2)[1] == null);
        show(() -> covAllocateReferenceArray(-1));
        show(() -> covAllocateDimensions(2, 3)[1].length);
        show(() -> covAllocateDimensions(2, 3)[1][2] == null);
        show(() -> covAllocateDimensions(0, -1));
        show(() -> covAllocateDimensions(-1, 0));
        show(() -> covAllocateDimensions(0, 0).length);
        show(() -> rejectAllocationLoop(4));
        show(() -> RawOps.covAllocateMissingArray(1));
        show(() -> RawOps.covAllocateMissingArray(-1));
        show(() -> covAllocateObject() != covAllocateObject());
        show(() -> covConstruct(1, sameString, false) == null);
        System.out.println("allocation-skipped:" + fieldState);
        show(() -> covConstruct(0, sameString, true));
        System.out.println("allocation-started:" + fieldState);
        show(() -> covConstruct(Long.MIN_VALUE, sameString, true).amount);
        show(() -> covConstruct(Long.MAX_VALUE, sameString, true).label == sameString);
        show(() -> covConstruct(5, null, true).label == null);
        show(() -> rejectAllocation(-7).getClass().getSimpleName());
        show(() -> covConcat("score", Integer.MIN_VALUE, Long.MAX_VALUE));
        show(() -> covConcat(null, Integer.MAX_VALUE, Long.MIN_VALUE));
        show(() -> covConcatObject(null, 7));
        show(() -> covConcatObject(new Object() {
            @Override public String toString() { throw new IllegalStateException(); }
        }, 8));
        show(() -> RawOps.covPlainConcat("literal", -4));
        show(() -> rejectConcatLoop("loop:", 2));
        show(() -> covPending(0));
        show(() -> covPending(2));
        show(() -> covPendingArray(null, 0));
        show(() -> covPendingArray(new int[0], 0));
        show(() -> covPendingArray(new int[]{42}, 0));
        show(() -> instance.covTypedFields((byte)-128, (char)65535, (short)-32768, true, -0.0f, -0.0));
        show(() -> covNarrowCalls((byte)-128, (char)65535, (short)-32768, true));
        show(() -> covFloatCall(Float.NaN));
        show(() -> covDoubleCall(-0.0));
        for (int value : new int[]{0, 1, 2, 3, -1, 255, 65535, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            show(() -> RawOps.covRawZ(new boolean[1], 0, value));
            show(() -> RawOps.covRawB(new byte[1], 0, value));
            show(() -> RawOps.covRawC(new char[1], 0, value));
            show(() -> RawOps.covRawS(new short[1], 0, value));
        }
        Random random = new Random(9347351L);
        long digest = 0;
        for (int i = 0; i < 2000; i++) {
            int value = random.nextInt();
            long wideValue = random.nextLong();
            digest = digest * 31 + covInt(new int[1], 0, value);
            digest = digest * 31 + covLong(new long[]{wideValue}, 0, wideValue);
            digest = digest * 31 + RawOps.covRawZ(new boolean[1], 0, value);
            digest = digest * 31 + RawOps.covRawB(new byte[1], 0, value);
            digest = digest * 31 + RawOps.covRawC(new char[1], 0, value);
            digest = digest * 31 + RawOps.covRawS(new short[1], 0, value);
            float f = Float.intBitsToFloat(value);
            double d = Double.longBitsToDouble(wideValue);
            digest = digest * 31 + Float.floatToIntBits(covFloat(new float[1], 0, f));
            digest = digest * 31 + Double.doubleToLongBits(covDouble(new double[]{-0.0}, 0, d));
            digest = digest * 31 + (covReference(new Object[1], 0, token) ? 1 : 0);
            digest = digest * 31 + (covCallIdentity(token) ? 1 : 0);
        }
        System.out.println("random:" + Long.toHexString(digest));
        System.out.println("state:" + instance.count + ":" + instance.wide + ":" + total);
    }
}

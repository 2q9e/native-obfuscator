package example.nativefixture;

final class Kernel {
    private Kernel() {}

    static int mix(int value, int salt) {
        int mixed = (value ^ salt) * 31 + 7;
        return mixed ^ (mixed >>> 16);
    }
}

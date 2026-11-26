package sensitive.engine;

public final class HiddenLogic {
    private final int bias;

    public HiddenLogic(int bias) {
        this.bias = bias;
    }

    public int nativeLookup(int input) {
        return bias ^ input;
    }

    public static int calculate(int value, int steps) {
        for (int i = 0; i < steps; i++) {
            if ((value & 15) == 9) value = value * 13 + i;
            else value = (value ^ i) - 7;
        }
        return value;
    }

    public int runNested() {
        return new Member().value();
    }

    public Class<?>[] nestedTypes() {
        class Local {
            int value() { return bias + 1; }
        }
        var anonymous = new Object() {
            int value() { return bias + 2; }
        };
        if (new Local().value() + anonymous.value() != 2 * bias + 3) throw new AssertionError();
        return new Class<?>[] {Member.class, Local.class, anonymous.getClass()};
    }

    private final class Member {
        private int value() { return bias; }
    }
}

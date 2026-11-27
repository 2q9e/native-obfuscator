package fixture;

import java.util.List;
import java.util.function.IntBinaryOperator;
import sensitive.engine.HiddenLogic;

public final class Launcher {
    private record Container(HiddenLogic value) {}

    public static void main(String[] expectedNames) throws Exception {
        if (!Launcher.class.getMethod("main", String[].class).getParameters()[0].getName().equals("expectedNames"))
            throw new AssertionError("method parameter metadata");
        Container container = new Container(new HiddenLogic(19));
        HiddenLogic logic = container.value();
        if (logic.runNested() != 19) throw new AssertionError("private nestmate access");
        Class<?>[] types = logic.nestedTypes();
        for (Class<?> type : types) {
            if (type.getNestHost() != HiddenLogic.class || type.getEnclosingClass() != HiddenLogic.class)
                throw new AssertionError("nest host or enclosing class");
        }
        if (!types[0].isMemberClass() || !types[1].isLocalClass() || !types[2].isAnonymousClass()
            || !types[1].getEnclosingMethod().getName().equals("nestedTypes") || !types[2].getSimpleName().isEmpty())
            throw new AssertionError("inner class metadata");
        if (expectedNames.length != 0 && (!types[0].getSimpleName().equals(expectedNames[0])
            || !types[1].getSimpleName().equals(expectedNames[1]))) throw new AssertionError("remapped simple names");
        if (!Launcher.class.getDeclaredMethod("describe").getGenericReturnType().getTypeName().contains(HiddenLogic.class.getName()))
            throw new AssertionError("generic signature");
        IntBinaryOperator operation = HiddenLogic::calculate;
        int digest = 0;
        for (int i = 0; i < 512; i++) digest = 31 * digest + logic.nativeLookup(operation.applyAsInt(i, i % 29));
        System.out.println(digest);
    }

    private static List<HiddenLogic> describe() { return List.of(); }
}

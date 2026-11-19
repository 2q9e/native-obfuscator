package example.nativefixture;

import example.nativefixture.mixin.IdentifierAccessor;
import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.slf4j.LoggerFactory;

public final class NativeFixture implements ModInitializer {
    @Override
    public void onInitialize() {
        int result = Kernel.mix(0x7654_3210, 0x1357_9BDF);
        Identifier identifier = Identifier.of("native_fixture", "widened_path");
        IdentifierAccessor accessor = (IdentifierAccessor) (Object) identifier;
        if (result != 0x3B71_AB69
            || !"native_fixture".equals(accessor.nativeFixtureNamespace())
            || !"widened_path".equals(identifier.path)) {
            throw new IllegalStateException("Native fixture verification failed");
        }
        LoggerFactory.getLogger("native-fixture").info("Native fixture passed (kernel = {}, Mixin and access widener active)", result);
    }
}

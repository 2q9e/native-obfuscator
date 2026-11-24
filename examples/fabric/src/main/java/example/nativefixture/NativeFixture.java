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
        int score = Kernel.identifierScore(identifier, 41);
        Identifier alternate = Identifier.of("native_fixture", "aaa_path");
        int priority = Kernel.identifierPriority(identifier, alternate, 41);
        int fallback = Kernel.identifierPriorityOrDefault(null, alternate, 41, -1);
        int registry = Kernel.rankRegistryCandidates(
            new Identifier[]{identifier, null, identifier, alternate, identifier, null}, 41);
        if (result != 0x3B71_AB69 || score != 28_443_952 || priority != 1030 || fallback != -1
            || registry != 1_115_680 || Kernel.rankRegistryCandidates(new Identifier[0], 41) != 41
            || Kernel.identifierPriorityOrDefault(identifier, alternate, 41, -1) != priority
            || priority != Kernel.identifierPriority(alternate, identifier, 41)
            || Kernel.identifierPriority(identifier, identifier, 41) != 41
            || !"native_fixture".equals(accessor.nativeFixtureNamespace())
            || !"widened_path".equals(identifier.path)) {
            throw new IllegalStateException("Native fixture verification failed");
        }
        LoggerFactory.getLogger("native-fixture").info(
            "Native fixture passed (kernel = {}, identifier score = {}, priority = {}, fallback = {}, registry = {}, Mixin and access widener active)",
            result, score, priority, fallback, registry);
    }
}

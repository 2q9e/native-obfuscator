import java.io.File;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.game.minecraft.Hooks;
import net.fabricmc.loader.impl.launch.knot.Knot;

public final class BootstrapFixture {
    public static void main(String[] args) {
        new Knot(EnvType.SERVER).init(new String[0]);
        Hooks.startServer(new File("."), null);
    }
}

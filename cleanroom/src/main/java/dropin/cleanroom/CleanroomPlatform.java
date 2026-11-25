package dropin.cleanroom;

import com.cleanroommc.common.CleanroomEnvironment;
import dropin.tunnel.Agnos;
import dropin.tunnel.E4mcClient;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;

public class CleanroomPlatform implements Agnos.Platform {
    @Override
    public String loaderName() {
        return "cleanroom";
    }

    @Override
    public boolean isClient() {
        return FMLLaunchHandler.side().isClient();
    }

    @Override
    public Path configDir() {
        return Loader.instance().getConfigDir().toPath();
    }

    @Override
    public Path jarPath() {
        ModContainer container = Loader.instance().getIndexedModList().get(E4mcClient.MOD_ID);
        if (container != null && container.getSource() != null) {
            return container.getSource().toPath();
        }
        try {
            return new File(CleanroomPlatform.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toPath();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return CleanroomEnvironment.isDev();
    }
}

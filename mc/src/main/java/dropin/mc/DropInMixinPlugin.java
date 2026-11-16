package dropin.mc;

import dropin.tunnel.Agnos;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Keeps the development-only mixins ({@code dropin.mixin.mc.dev.*}) out of production:
 * outside a development environment they are never applied (DESIGN §4.12).
 */
public class DropInMixinPlugin implements IMixinConfigPlugin {
    private static final String DEV_ONLY_PACKAGE = "dropin.mixin.mc.dev.";

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith(DEV_ONLY_PACKAGE)) {
            return Agnos.isDevelopmentEnvironment();
        }
        return true;
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}

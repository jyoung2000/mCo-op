package dropin.mc.policy;

import dropin.core.mods.ModDiff;
import dropin.core.mods.ModManifest;
import dropin.core.policy.Policy;
import dropin.core.world.CoopWorld;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.menu.CoopWorldsScreen;
import dropin.mc.menu.ModConsentScreen;
import dropin.mc.menu.PolicyUpdateScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** The checks of DESIGN 4.13 and 4.14 that run in the launch flow, before the integrated server boots. */
public final class HostGate {
    private static final String K = "text.dropin_coop.";

    private HostGate() {
    }

    /**
     * The owner opens the world: the signed policy must describe this PC's mods and the world's datapacks.
     * The first policy is signed without asking; a change is asked about ("Update this world's mod list?").
     * Call off the client thread, with the world closed.
     *
     * @param retry    runs the launch flow again, after the owner said yes
     * @param onCancel where "No" leads
     * @return true if the flow may go on now; false if a screen took over
     */
    public static boolean ownerReady(Minecraft mc, CoopWorld world, Path folder, Runnable retry, Runnable onCancel) {
        if (!CoopWorlds.store().isOwner(world.worldId())) {
            return true;
        }
        try {
            WorldPolicy.Held held = WorldPolicy.load(world, folder);
            ModManifest manifest = WorldPolicy.ownerManifest();
            if (held == null) {
                PolicyActions.signFirst(mc, world, folder, manifest);
                return true;
            }
            Policy policy = held.policy();
            boolean modsSame = WorldPolicy.sameFiles(held.manifest(), manifest);
            boolean packsSame = policy.datapackFiles().equals(WorldPolicy.datapackFiles(folder))
                    && policy.datapacksEnabled().equals(WorldPolicy.datapacksEnabled(folder));
            boolean gameSame = policy.minecraftVersion().equals(WorldPolicy.minecraftVersion())
                    && (manifest.isEmpty() || policy.loader().equals(WorldPolicy.loader()));
            if (modsSame && packsSame && gameSame) {
                return true;
            }
            List<String> added = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            for (ModManifest.Entry file : manifest.files()) {
                if (held.manifest().files().stream().noneMatch(old -> old.path().equals(file.path()) && old.sha512().equals(file.sha512()))) {
                    added.add(file.path());
                }
            }
            for (ModManifest.Entry old : held.manifest().files()) {
                if (manifest.files().stream().noneMatch(file -> old.path().equals(file.path()) && old.sha512().equals(file.sha512()))) {
                    removed.add(old.path());
                }
            }
            DropIn.LOGGER.info("The owner's mods or datapacks of '{}' differ from policy version {}: added {}, removed {}",
                    world.name(), policy.version(), added, removed);
            mc.execute(() -> mc.setScreen(new PolicyUpdateScreen(added, removed, !packsSame, !gameSame, () -> {
                try {
                    PolicyActions.signUpdate(mc, world, folder, held, manifest);
                    retry.run();
                } catch (IOException | RuntimeException e) {
                    DropIn.LOGGER.error("Could not sign a new policy for '{}'", world.name(), e);
                    alert(mc, onCancel, Component.translatable(K + "policy.failed", String.valueOf(e.getMessage())));
                }
            }, onCancel)));
            return false;
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.error("Could not check the policy of '{}'", world.name(), e);
            alert(mc, onCancel, Component.translatable(K + "policy.failed", String.valueOf(e.getMessage())));
            return false;
        }
    }

    /**
     * A member is about to host: their copy must match the owner's policy (DESIGN 4.13 "What a non-owner
     * host does"). Call off the client thread, with the world closed.
     *
     * @return the screen that says why this PC cannot host, or null if it may
     */
    public static Screen memberProblem(Minecraft mc, CoopWorld world, Path folder, Runnable onCancel) {
        if (CoopWorlds.store().isOwner(world.worldId())) {
            return null;
        }
        WorldPolicy.Held held = WorldPolicy.load(world, folder);
        if (held == null) {
            return null;
        }
        try {
            Component game = WorldPolicy.environmentProblem(held);
            if (game != null) {
                return new AlertScreen(onCancel, CoopWorldsScreen.TITLE, game);
            }
            ModDiff diff = WorldPolicy.diff(held);
            if (!diff.mayJoin()) {
                return new ModConsentScreen(onCancel, world, held, diff, null, false);
            }
            if (!diff.keptExtras().isEmpty()) {
                String names = diff.keptExtras().stream().map(ModManifest.Entry::path).collect(Collectors.joining(", "));
                return new AlertScreen(onCancel, CoopWorldsScreen.TITLE, Component.translatable(K + "policy.kept_extras", names));
            }
            Policy policy = held.policy();
            if (!policy.datapackFiles().equals(WorldPolicy.datapackFiles(folder))
                    || !policy.datapacksEnabled().equals(WorldPolicy.datapacksEnabled(folder))) {
                return new AlertScreen(onCancel, CoopWorldsScreen.TITLE, Component.translatable(K + "policy.datapacks_mismatch"));
            }
            return null;
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.error("Could not check the policy of '{}'", world.name(), e);
            return new AlertScreen(onCancel, CoopWorldsScreen.TITLE,
                    Component.translatable(K + "policy.failed", String.valueOf(e.getMessage())));
        }
    }

    private static void alert(Minecraft mc, Runnable onCancel, Component message) {
        mc.execute(() -> mc.setScreen(new AlertScreen(onCancel, CoopWorldsScreen.TITLE, message)));
    }
}

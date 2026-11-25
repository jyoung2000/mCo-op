package dropin.mc.mods;

import dropin.core.mods.HelperLauncher;
import dropin.core.mods.ModDiff;
import dropin.core.mods.ModManifest;
import dropin.core.mods.ModStore;
import dropin.core.mods.SwapPlan;
import dropin.core.world.CoopWorld;
import dropin.mc.DropIn;
import dropin.mc.menu.CoopStatus;
import dropin.mc.policy.WorldPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Download and restart" (DESIGN 4.14): gets every missing file of the mod list into the content-addressed
 * store (Modrinth first, else from the live host over the sync channel), each checked against the SHA-512
 * in the owner-signed list, then writes the swap plan and starts the detached helper. The caller closes
 * the game; the helper changes {@code mods/} once the game process is gone.
 */
public final class ModInstall {
    private static final String K = "text.dropin_coop.";
    private static final long LINK_TIMEOUT_MS = 15_000;

    /** A failure the consent screen shows as it is. */
    public static final class InstallException extends IOException {
        private final ITextComponent shown;

        InstallException(ITextComponent shown) {
            super(shown.getUnformattedText());
            this.shown = shown;
        }

        public ITextComponent shown() {
            return shown;
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger MODRINTH_DOWNLOADS = new java.util.concurrent.atomic.AtomicInteger();

    private ModInstall() {
    }

    /** How many mod files this game downloaded from Modrinth since it started. Read by the self-tests. */
    public static int modrinthDownloads() {
        return MODRINTH_DOWNLOADS.get();
    }

    /**
     * Blocking; not on the client thread.
     *
     * @param address  the live host to fetch peer files from, or null to look one up when it is needed
     * @param progress told what is being downloaded
     */
    public static void downloadAndPlan(Minecraft mc, CoopWorld world, WorldPolicy.Held held, String address,
                                       Consumer<ITextComponent> progress) throws IOException, InterruptedException {
        ModManifest manifest = held.manifest();
        manifest.validate(WorldPolicy.limits());
        ModStore store = WorldPolicy.store();
        List<ModManifest.Entry> local = WorldPolicy.localMods();
        ModDiff diff = ModDiff.of(manifest, local, WorldPolicy.keep());
        Path mods = WorldPolicy.modsDir();
        PolicyLink link = null;
        try {
            int index = 0;
            for (ModManifest.Entry entry : diff.toDownload()) {
                index++;
                if (store.has(entry.sha512())) {
                    continue;
                }
                progress.accept(new TextComponentTranslation(K + "mods.consent.downloading", entry.path(), index, diff.toDownload().size()));
                // The same file under another name is already on this PC.
                ModManifest.Entry twin = local.stream().filter(have -> have.sha512().equals(entry.sha512())).findFirst().orElse(null);
                if (twin != null) {
                    store.put(mods.resolve(twin.path()), entry);
                    continue;
                }
                if (entry.source().equals(ModManifest.MODRINTH) && WorldPolicy.modrinthEnabled() && fromModrinth(entry, store)) {
                    continue;
                }
                if (link == null) {
                    link = openLink(mc, world, address);
                }
                try {
                    link.fetch(entry, store);
                    DropIn.LOGGER.info("Mod file {} fetched from the host and verified", entry.path());
                } catch (PolicyLink.FetchException e) {
                    DropIn.LOGGER.warn("Mod file {} could not be fetched from the host: {}", entry.path(), e.getMessage());
                    throw new InstallException(switch (e.kind()) {
                        case PATH -> new TextComponentTranslation(K + "mods.error.path", entry.path());
                        case ABSENT -> new TextComponentTranslation(K + "mods.error.absent", entry.path());
                        case HASH -> new TextComponentTranslation(K + "mods.error.hash", entry.path());
                        case LOST -> new TextComponentTranslation(K + "mods.error.failed", String.valueOf(e.getMessage()));
                    });
                }
            }
        } finally {
            if (link != null) {
                link.close();
            }
        }
        for (ModManifest.Entry entry : diff.toDownload()) {
            if (!store.has(entry.sha512())) {
                throw new InstallException(new TextComponentTranslation(K + "mods.error.hash", entry.path()));
            }
        }
        Path planFile = WorldPolicy.configDir().resolve(SwapPlan.FILE);
        SwapPlan.of(manifest, WorldPolicy.planKeep(), ProcessHandle.current().pid(), mods, store.dir(), Instant.now()).write(planFile);
        HelperLauncher.launch(planFile, WorldPolicy.configDir().resolve("modswap.log"));
        DropIn.LOGGER.info("Mod swap for '{}' planned (policy version {}): {} to install, {} to move away; the helper waits for the game to close",
                world.name(), held.version(), diff.toDownload().size(), diff.toRemove().size());
    }

    private static boolean fromModrinth(ModManifest.Entry entry, ModStore store) throws InterruptedException {
        try (ModStore.Incoming incoming = store.receive(entry)) {
            WorldPolicy.modrinth().download(entry.url(), incoming);
            incoming.commit();
            MODRINTH_DOWNLOADS.incrementAndGet();
            DropIn.LOGGER.info("Mod file {} downloaded from Modrinth and verified", entry.path());
            return true;
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.warn("Mod file {} could not be downloaded from Modrinth ({}); asking the host", entry.path(), e.toString());
            return false;
        }
    }

    private static PolicyLink openLink(Minecraft mc, CoopWorld world, String address) throws IOException, InterruptedException {
        String host = address;
        if (host == null) {
            CoopStatus.Status status = CoopStatus.lookup(world);
            if (status.kind() != CoopStatus.Kind.PLAYING) {
                throw new InstallException(new TextComponentTranslation(K + "mods.error.nobody"));
            }
            host = status.address();
        }
        try {
            PolicyLink link = PolicyLink.open(mc, world, host);
            // The host only serves files once the handshake is done; its policy answer is the sign of that.
            if (!link.await(LINK_TIMEOUT_MS)) {
                link.close();
                throw new InstallException(new TextComponentTranslation(K + "mods.error.failed", "the host did not answer"));
            }
            return link;
        } catch (IOException e) {
            throw new InstallException(new TextComponentTranslation(K + "mods.error.failed", String.valueOf(e.getMessage())));
        }
    }
}

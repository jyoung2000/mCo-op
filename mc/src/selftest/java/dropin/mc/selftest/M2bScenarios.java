package dropin.mc.selftest;

import dropin.core.mods.HelperLauncher;
import dropin.core.mods.SwapHelper;
import dropin.core.mods.SwapPlan;
import dropin.mc.DropIn;
import dropin.mc.selftest.Scenarios.Context;
import dropin.tunnel.Agnos;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static dropin.mc.selftest.M1Scenarios.require;
import static dropin.mc.selftest.Scenarios.STARTUP_TICKS;

/**
 * The M2b gate scenarios (docs/MILESTONES.md): owner policy and mod sync. Development only.
 * <p>
 * Test mods are tiny jars built by the test build ({@code :<loader>:testmodJars}): X and Y each add one item,
 * Z adds nothing and only sets the system property {@code dropin.testmod.z}.
 */
final class M2bScenarios {
    static final String ITEM_X = "dropin_testmod_x:item";
    static final String ITEM_Y = "dropin_testmod_y:item";
    static final String Z_PROPERTY = "dropin.testmod.z";

    private M2bScenarios() {
    }

    static StepRunner build(String key, Context ctx) {
        return switch (key) {
            case "swap-spike:a" -> spikeFirstRun(ctx);
            case "swap-spike:a2" -> spikeSecondRun(ctx);
            default -> null;
        };
    }

    // ---- building blocks --------------------------------------------------------------------------

    static Path configDir() {
        return Agnos.configDir().resolve("dropin");
    }

    static Path modsDir(Minecraft mc) {
        return mc.gameDirectory.toPath().resolve("mods");
    }

    /** A test mod jar of this loader, as built by {@code :<loader>:testmodJars}. */
    static Path testMod(String name) {
        Path dir = DropIn.switches().read("dropin.dev.testModsDir").map(Path::of)
                .orElseThrow(() -> new IllegalStateException("-Ddropin.dev.testModsDir is required"));
        Path jar = dir.resolve(Agnos.loaderName()).resolve("dropin-testmod-" + name + ".jar");
        require(Files.isRegularFile(jar), "test mod not built: " + jar);
        return jar;
    }

    static boolean itemExists(String id) {
        return BuiltInRegistries.ITEM.containsKey(Identifier.parse(id));
    }

    static boolean zLoaded() {
        return "loaded".equals(System.getProperty(Z_PROPERTY));
    }

    static List<String> jarsIn(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile).map(f -> folder.relativize(f).toString().replace('\\', '/')).sorted().toList();
        }
    }

    // ---- swap-spike: the helper changes mods/ while the game is closed ------------------------------

    /** Run 1 starts with test mod Z in mods/. It asks for X instead, starts the helper and quits. */
    private static StepRunner spikeFirstRun(Context ctx) {
        Minecraft mc = ctx.mc();
        return new StepRunner()
                .step("main menu", STARTUP_TICKS, () -> Scenarios.atMainMenu(mc))
                .action("Z is loaded from mods/, X is not there", () -> {
                    require(zLoaded(), "test mod Z in mods/ was not loaded by " + Agnos.loaderName());
                    require(!itemExists(ITEM_X), "test mod X is loaded before the swap");
                })
                .action("write the plan and start the helper", () -> {
                    Path store = configDir().resolve("modstore");
                    Path jar = testMod("x");
                    String hash = SwapHelper.sha512(jar);
                    Files.createDirectories(store);
                    Files.copy(jar, store.resolve(hash + ".jar"), StandardCopyOption.REPLACE_EXISTING);
                    Path mods = modsDir(mc);
                    SwapPlan plan = new SwapPlan(ProcessHandle.current().pid(), mods, store,
                            SwapPlan.disabledDir(mods, Instant.now()),
                            List.of(new SwapPlan.Install(jar.getFileName().toString(), hash)), List.of());
                    Path planFile = configDir().resolve(SwapPlan.FILE);
                    plan.write(planFile);
                    HelperLauncher.launch(planFile, configDir().resolve("modswap.log"));
                });
    }

    /** Run 2, started by the orchestrator after the helper reported: the new set is what the loader loaded. */
    private static StepRunner spikeSecondRun(Context ctx) {
        Minecraft mc = ctx.mc();
        return new StepRunner()
                .step("main menu", STARTUP_TICKS, () -> Scenarios.atMainMenu(mc))
                .action("the helper reported success", () -> {
                    String result = Files.readString(configDir().resolve(SwapHelper.RESULT_FILE), StandardCharsets.UTF_8);
                    require(result.startsWith("{\"ok\":true"), "helper result: " + result);
                })
                .action("X is loaded, Z is not", () -> {
                    require(itemExists(ITEM_X), "test mod X was not loaded after the swap on " + Agnos.loaderName());
                    require(!zLoaded(), "test mod Z is still loaded after the swap");
                })
                .action("Z was moved, not deleted", () -> {
                    List<String> jars = jarsIn(modsDir(mc));
                    require(jars.contains("dropin-testmod-x.jar"), "mods/ has " + jars);
                    require(!jars.contains("dropin-testmod-z.jar"), "Z is still directly in mods/: " + jars);
                    require(jars.stream().anyMatch(j -> j.startsWith(SwapPlan.DISABLED_DIR + "/") && j.endsWith("/dropin-testmod-z.jar")),
                            "Z is not under mods/.dropin-disabled/: " + jars);
                });
    }
}

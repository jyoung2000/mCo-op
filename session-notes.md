# Session notes

## Preflight (2026-11-16)

- `java -version`: bare PATH in the agent's Bash shell resolves to **17.0.20.1**; `JAVA_HOME` (Windows env and `.claude/settings.local.json`) is `C:\Program Files\Microsoft\jdk-25.0.5.21-hotspot`, and with `$JAVA_HOME/bin` first on PATH `java -version` reports **25.0.5**. Every command in this session is run with JAVA_HOME + PATH set to JDK 25. Treated as PASS with that workaround; the user PATH still lists JDK 17 first.
- Branch `dropin`, working tree clean at `b874fe1`: PASS.
- `CLAUDE.md` starts with `# DropIn Co-op`, no `AGENTS.md`: PASS.
- `docs/DESIGN.md`, `docs/MILESTONES.md` exist: PASS.

## M0 — DONE (2026-11-16)

Gate passed in this session: build, core tests, suite `m0` 5/5, online `tunnel-join`, jar check 9/9 (`docs/VERIFICATION.md`).
The owner ruled ("Yes, exempt them") that `io.netty.incubator.codec.quic` and `link.e4mc.iroh` are not relocated; DESIGN §5,
the gate wording in `docs/MILESTONES.md` and `scripts/jarcheck.ps1` now say so. The owner still has the M0 manual checklist to do
(real Fabric and NeoForge instances). **Next step: M1**, in a new session. The guesses listed below are still unconfirmed.

### Design change after M0 (2026-11-17, docs only, no code)

The owner asked for: world owner as admin of mods and datapacks, automatic mod download for members, a mod list and a player list
with skins per world. Ruling on mod sync: **"Own sync, MIT"** (no AutoModpack code; it is LGPL-3.0). Written into DESIGN §1, §2
(limits 5–7 new; the old limit 5 is now 8), §4.1 (invite carries the owner-key fingerprint), §4.2, §4.4, new §4.13–4.15, §6, §7,
§8 items 16–20. MILESTONES: M1 gained work items 7–8 and gates `owner-invite`, `players-tab`; new milestone **M2b** after M2.
The owner approved these sections on 2026-11-17 ("Yes, looks right"). Unproven and flagged in the text: the
swap-helper mechanism (M2b step 1 is a spike). **Next step is still M1.**

### History: state before the ruling

State at `b5c639a` (2026-11-16), details in `docs/VERIFICATION.md`:

- Work list steps 1–8 done and committed locally (`115d599`, `e9948e1`, `66989d9`, `ebb37d2`, `b5c639a`). Nothing pushed.
- Gate: build PASS, core tests PASS (10), suite `m0` PASS (5/5), online smoke `tunnel-join` PASS, jar check **FAIL** on
  "bundled libraries are relocated" for `io.netty.incubator.codec.quic` and `link.e4mc.iroh` (see the design conflict in the log below;
  now confirmed against the cached native DLLs, which contain those class names).
- **Next step (start of next session): get the owner's ruling.** Options: (a) amend DESIGN §5 and the gate to exempt the two JNI-bound
  libraries, then change `scripts/jarcheck.ps1` to expect them at their original names and re-run only the jar check; (b) anything else
  the owner decides. Do not mark M0 DONE and do not start M1 before that.
- Open guesses for the owner to confirm: copyright name `jyoung2000`, version `0.1.0`, `/e4mc` command and upstream class names kept,
  icon and README still upstream's, native cache folder is still upstream's (`%LOCALAPPDATA%\e4mc\e4mc\cache`, set inside `tunnel/`).
- Deviations to know about: preflight `java -version` is 17 on the bare PATH (JDK 25 used through `JAVA_HOME`); the "build as is"
  step needed placeholder publishing tokens.
- Files touched: everything under `build-logic/`, `core/`, `tunnel/` (moved from `common/`), `mc/`, `fabric/`, `neoforge/`, `scripts/`;
  root `build.gradle`, `settings.gradle`, `gradle.properties`, `gradle/wrapper`, `.gitignore`, `LICENSE`, `CLAUDE.md`,
  `docs/VERIFICATION.md`; `forge/` and `PoisonPill.java` deleted.

### DESIGN §8 items confirmed in the 26.3 sources (NeoForm `vanilla-26.3-1-sources.jar`)

- **Item 13 (where the integrated server turns authentication on).** `net.minecraft.client.server.IntegratedServer`:
  `protected boolean initServer()` calls `this.setUsesAuthentication(true);` as its second statement, before `this.initializeKeyPair(); this.loadLevel();`.
  The flag lives on `MinecraftServer`: `public boolean usesAuthentication()` / `public void setUsesAuthentication(boolean onlineMode)`.
  It is read at login in `ServerLoginPacketListenerImpl.handleHello`: `if (this.server.usesAuthentication() && !this.connection.isMemoryConnection())`.
  Matches the design (no contradiction).
- **Item 14 (create a world from code with a fixed seed and preset).** `net.minecraft.client.gui.screens.worldselection.WorldOpenFlows`:
  `public void createFreshLevel(String levelId, LevelSettings levelSettings, WorldOptions options, Function<HolderLookup.Provider, WorldDimensions> dimensionsProvider, Screen parentScreen)`.
  `public record LevelSettings(String levelName, GameType gameType, LevelSettings.DifficultySettings difficultySettings, boolean allowCommands, WorldDataConfiguration dataConfiguration)`,
  `public record DifficultySettings(Difficulty difficulty, boolean hardcore, boolean locked)`,
  `public WorldOptions(long seed, boolean generateStructures, boolean generateBonusChest)`,
  presets: `WorldPresets.FLAT` (`ResourceKey<WorldPreset>`), `WorldPreset.createWorldDimensions()`, `WorldPresets.createNormalWorldDimensions(HolderLookup.Provider)`.
  Vanilla callers: `TitleScreen.java:238`, `SelectWorldScreen.java:134`. Matches the design.
- Seen on the way (not an M0 item, relevant to §8 item 2 in M1): `IntegratedServer.publishServer(MinecraftServer.MultiplayerScope scope, boolean allowCommands, int port)` and
  `publishServer(MultiplayerScope scope, int port)`; both return `false` when already published or when `startTcpServerListener` throws `IOException`. `MultiplayerScope` is `OFF`, `LAN`.

### Log

- Step 1 (build as is): `./gradlew build` on `b874fe1` FAILED at configuration: `Could not get unknown property 'curseforgeApiToken' for project ':fabric'`
  (upstream's publishing block needs tokens that only exist on upstream's machine). Re-run with `-PcurseforgeApiToken=none -PmodrinthApiToken=none`
  and no repo change: `BUILD SUCCESSFUL in 5m 53s`. So the baseline code is healthy; the publishing block is removed in step 6 anyway.
  Logs: `build-logs/m0-step1-build-as-is.log`, `build-logs/m0-step1-build-as-is-2.log`.
- Step 2 route decision: **MultiLoader-Template 26.3** (Fabric Loom 1.18.1 + ModDevGradle 2.0.147, Gradle 9.7.1), not Architectury Loom.
  Why: the Architectury pieces the current build relies on (`@ExpectPlatform`, `transformProduction*`, `remapJar`, the intermediary→named remap for the 26.x Fabric jar)
  all have to go for a single non-remapped jar with runtime loader detection, so either route rewrites every build file; the template is a
  known-good 26.3 no-remap setup. Versions used (newest on the mavens today): Fabric Loader 0.20.1, Fabric API 0.166.2+26.3, NeoForge 26.3.0.103, NeoForm 26.3-1.
- **Design conflict found (needs the owner's ruling): relocation of bundled libraries.** DESIGN §5 and the gate's jar check say all four
  bundled libraries are shaded *and relocated*. Two of them cannot be: `iroh-java` (`link.e4mc.iroh.Native`, 27 `native` methods) and
  the netty-quic build (`io.netty.incubator.codec.quic.Quiche`, 79 `native` methods) bind prebuilt JNI libraries, downloaded at runtime
  from `natives.e4mc.link`, to those exact class names. Relocating the Java side breaks the binding, and CLAUDE.md forbids rebuilding
  `iroh-java`. Upstream itself only relocates `dev.dirs`. What the jar does now: `dev.dirs` → `dropin.shadow.dirs`,
  `folk.sisby.kaleido` → `dropin.shadow.kaleido`; `io.netty.incubator.codec.quic` and `link.e4mc.iroh` shaded at their original names.
  Consequence: a clash is only possible with a mod that ships the same two packages, i.e. e4mc itself, which is declared incompatible.
- Layout (DESIGN §6): Gradle modules `core` (plain Java + JUnit), `tunnel` (upstream code, package `dropin.tunnel`, NeoForm),
  `mc` (package `dropin.mc`, NeoForm, extra dev-only source set `selftest`), `fabric`, `neoforge`. The loader modules compile the sources
  of `core`, `tunnel` and `mc` (template mechanism), so dev runs need no special classpath wiring. Root task `modJar` = Fabric module's
  classes/resources + what only NeoForge has + shaded libraries, minus `dropin/mc/selftest/**`.
- One mixin config `dropin.mixins.json` (in `mc/src/main/resources`), package `dropin.mixin`: `tunnel.*` (upstream mixins, moved from
  `link.e4mc.mixin`) and `mc.dev.*` (dev-only; `DropInMixinPlugin.shouldApplyMixin` returns false for them outside a dev environment).
- Kept upstream names where the milestone did not ask for a rename: classes `E4mcClient`, `QuiclimeSession`…, the `/e4mc` command,
  `e4mc$` mixin member prefixes, thread names. Renamed: mod id, packages, config (`config/dropin/dropin.toml`), lang keys (`text.dropin.*`,
  assets namespace `dropin`), mixin config, icon file name (art is still upstream's).
- Owner's copyright line uses the repo's `git config user.name` (`jyoung2000`); version set to `0.1.0`. Both are guesses to confirm.
- Upstream source needed only 6 compile fixes for 26.3: `E4mcClient` (`src.hasPermission(4)` → `Commands.hasPermission(Commands.LEVEL_OWNERS).test(src)`),
  `Mirror` (`ClickEvent`/`HoverEvent` are interfaces with records `RunCommand`, `CopyToClipboard`, `ShowText`; chat is `Minecraft.gui.hud.getChat().addClientSystemMessage`),
  and the Fabric entry point (command API v1 fallback removed).

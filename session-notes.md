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

## M1 — IN PROGRESS (started 2026-11-17, same session as the M0 checklist, at the owner's request)

M0 manual checklist: items 1 and 2 done by the agent in real Prism instances (`DropIn-M0-Fabric`, `DropIn-M0-NeoForge`, both
added to the owner's Prism Launcher); item 3 (a real join in both directions) is still open, it needs two accounts.

Done and committed:

- **Work item 1** `core/crypto`, `core/invite` (`bbea437`): 14 tests, RFC 5869 and RFC 8032 vectors pass.
- **Work item 2** `core/pkarr`: `DnsPacket`, `SignedPacket`, `HttpPkarrClient`, `RendezvousRecord`, `PkarrRendezvous`,
  `FileRendezvous`, `HostClaim`; 21 unit tests against a fake relay, plus the opt-in `RealRelayOnlineTest`
  (`-Pdropin.online=true`), which passed against the real relays on 2026-11-17.

Decisions made in M1 so far (not spelled out in the design; tell the owner):

- Invite base32 is RFC 4648, upper case, no padding, 77 characters in groups of five; decoding ignores case, dashes, spaces.
- HKDF uses an empty salt; the AEAD for the rendezvous payload uses the world id as associated data and a random 12-byte nonce.
- Wire format taken from https://pubky.github.io/pkarr/design/relays.html: `sig(64) ‖ timestamp(8, BE, µs) ‖ DNS`, signature over
  `3:seqi<ts>e1:v<len>:<dns>`. **That page does not document `If-Match`**, which DESIGN §4.3 mentions for the claim; it is not sent.
  A relay's 409 for an older timestamp is surfaced as `StaleRecordException`.
- Live public relays found: `relay.pkarr.org`, `pkarr.pubky.org`, `pkarr.pubky.app` (default list in `HttpPkarrClient`; the config
  entry for it is still to be added in the `mc` module).
- `HostClaim` makes one reading of §4.3 explicit: someone else's fresh `starting` claim (≤ 60 s old) is waited for (≤ 45 s) instead
  of hosting next to it; and if our own claim took > 1.5 s to publish, the claim starts over.
- `core` compiles against Gson (`compileOnly`, 2.11.0); Minecraft ships Gson, so nothing is bundled.

DESIGN §8 items confirmed in the 26.3 sources for M1 (2026-11-17):

- **Item 1** `WorldOpenFlows.openWorld`: `public void openWorld(String levelId, Runnable onCancel)` (`WorldOpenFlows.java:306`). Three
  callers: `WorldSelectionList.WorldListEntry.joinWorld()` (`this.minecraft.createWorldOpenFlows().openWorld(this.summary.getLevelId(), this.list::returnToScreen)`),
  `QuickPlay.java:92`, `TitleScreen.java:234` (demo). Matches the design: one entry point to hook.
- **Item 2** (from M0): `IntegratedServer.publishServer(MinecraftServer.MultiplayerScope scope, boolean allowCommands, int port)`, false when
  already published or on `IOException`.
- **Item 3** `singleplayer_uuid`: read in `PrimaryLevelData` (`input.get("singleplayer_uuid")`, getter `public @Nullable UUID getSinglePlayerUUID()`,
  also on `WorldData`); written by `MinecraftServer.java:595`
  `this.storageSource.saveDataTag(this.worldData, singleplayerProfile == null ? null : singleplayerProfile.id())`. **The design's worry is real:**
  `PlayerList.loadPlayerData(NameAndId nameAndId)` does `if (this.server.isSingleplayerOwner(nameAndId) && lastSingleplayerOwnerUUID != null) return this.playerIo.load(new NameAndId(lastSingleplayerOwnerUUID, "<singleplayer owner>"))`,
  so a new host would load the previous host's player. `IntegratedServer.selectLevelLoadFocusPos()` uses the same UUID for the spawn-chunk focus.
  **Choice: a mixin at the two read sites** (redirect `getSinglePlayerUUID()` to the local player's UUID for co-op worlds), not a `level.dat`
  rewrite: the only public writer is `LevelStorageAccess.renameAndDropPlayer(String)`, which also renames the world.
- **Item 9** (from M0): `ServerStatusPinger.pingServer(ServerData, Runnable, Runnable, EventLoopGroupHolder)`.
- **Item 10** `TitleScreen`: `protected void init()` calls `private int createNormalMenuOptions(int topPos, int spacing)` (spacing 24), which adds
  Singleplayer, Multiplayer and Realms with `this.addRenderableWidget(Button.builder(...).bounds(this.width / 2 - 100, topPos, 200, 20).build())`.
- **Item 11** `LevelStorageSource`: `findLevelCandidates()`, `loadLevelSummaries(LevelCandidates)` → `CompletableFuture<List<LevelSummary>>`,
  `levelExists(String)`, `isNewLevelIdAcceptable(String)`, `getLevelPath(String)`, `getBaseDir()`, `validateAndCreateAccess(String)`, `createAccess(String)`;
  `LevelStorageAccess.makeWorldBackup()` → `long`; `LevelSummary.getLevelId()`, `levelVersion()`, `isDowngrade()`, `requiresManualConversion()`, `isLocked()`.
- **Item 12** `Screen.onFilesDrop(List<Path> files)` (`Screen.java:467`; `PackSelectionScreen` overrides it). A native file dialog is **not confirmed**
  for 26.3: only `lwjgl-tinyfd-3.3.2` jars from older versions are in the Gradle cache. Browse is therefore left out unless it is found on the classpath.
- **Item 15** `CreateWorldScreen`: `private void onCreate()` → `private boolean createNewWorld(LayeredRegistryAccess<RegistryLayer>, LevelDataAndDimensions.WorldDataAndGenSettings, Optional<GameRules>)`,
  which takes the folder from `this.uiState.getTargetFolder()` and calls `WorldOpenFlows.createLevelFromExistingSettings(newWorldAccess.get(), ...)`.
- **Item 18** skins: `ProfileResolver.fetchById(UUID)` → `Optional<GameProfile>`; `Minecraft.playerSkinRenderCache`
  (`PlayerSkinRenderCache.getOrDefault(ResolvableProfile)`, `createLookup(ResolvableProfile)`);
  `PlayerFaceExtractor.extractRenderState(GuiGraphicsExtractor graphics, ResolvableProfile skinProfile, int x, int y, int size)`;
  `SkinManager.get(GameProfile)` → `CompletableFuture<Optional<PlayerSkin>>`. Unknown profiles fall back to the default skin.

None of these contradicts the design.

Not started: work items 3–8 (everything in `mc/`: menu, import, launch flow, `singleplayer_uuid`, owner key file, Players tab),
the §8 source confirmations for them (items 1, 2, 3, 9, 10, 11, 12, 15, 18), the `m1` self-test scenarios and the gate.

### M1 state after the first gate run (2026-11-17)

**M1 DONE (2026-11-17).** Gate passed, see docs/VERIFICATION.md. Work items 1–8 are all written.

`players-tab`: the owner chose "Move check to M2b". B showing A as owner is now gate m2b `owner-known-to-members`; M2b must carry the
owner's uuid and name to members in the signed policy and make the Players tab use it.

**Next: M2** (sync channel, full download, live checkpoints). The owner then wants M2b, and asked (2026-11-17) that any player can
connect, disconnect and take over as host while the world keeps knowing its owner (admin of mods and datapacks). Host handoff itself
is a later milestone in MILESTONES; M2b's `owner-known-to-members` and `owner-rights` cover "still knows the admin when B hosts".
Start M2 by confirming DESIGN §8 items 4–8 in the sources. The M1 manual checklist is still the owner's to run.

## M3 — DONE (2026-11-17), gate passed on `19ea3fc` (see `docs/VERIFICATION.md`)

State: `./gradlew build`, `scripts/jarcheck.ps1` and `scripts/selftest.ps1 -Suite all` (39 of 39) passed in this session.
MILESTONES lists no online smoke for M3. Nothing is pushed.

**Next step: M4** (crash failover, stale-copy warning, status HUD), in its own session. `PEERS` (number 18) is still reserved and unused.

Manual checklist (MILESTONES M3), not done:
- [ ] Three real players: the host quits; time how long the others are out of the world.

Files touched in M3: `core/.../sync/{Successor,Wire,SyncServerSession,SyncClientSession}`, `core/.../pkarr/RendezvousRecord` and
their tests (`SuccessorTest`, `SyncTest`, `PkarrTest`); `mc/.../handoff/{HostHandoff,MemberHandoff,HostChangingScreen}`,
`mc/.../host/HostSync`, `mc/.../coop/{HostSession,CoopLauncher}`, `mc/.../member/SyncClient`, `dropin/mixin/mc/MinecraftDisconnectMixin`,
`dropin.mixins.json`, `en_us.json`; `mc/src/selftest/.../{M3Scenarios,M1Scenarios,M2Scenarios,Scenarios}`; `scripts/selftest.ps1`;
`docs/VERIFICATION.md`; `CLAUDE.md` (suites).


Graceful handoff (MILESTONES M3, DESIGN §4.9 graceful, §4.3).

### M3: what was built

- `core/sync/Successor`: `choose(peers, finalSeq, hostUuid)`: a peer that applied the final checkpoint, longest connected first,
  ties to the lowest UUID; never the host itself.
- `core/sync/Wire`: `HANDOFF = 19 {str successorUuid, str finalEntryJson}`, `PROGRESS = 27 {i32 have, i32 total}`, `PROTO = 3`.
  `SyncServerSession`: `handoff(...)`, `connectedAt()`, `peerPercent()`. `SyncClientSession`: sends `PROGRESS` (at most once per
  percent), checks the handoff entry's MAC and calls `Local.handoff(successorUuid, finalEntry, applied)`.
- `core/pkarr/RendezvousRecord.handoffTo(successorUuid, ts)`: `st:"handoff"`, `next`, no address.
- `mc/handoff/HostHandoff` + `MinecraftDisconnectMixin` (HEAD of `Minecraft.disconnectFromWorld`): the host's quit with members
  connected first runs `HostSync.prepareHandoff()` (checkpoint, wait up to 30 s for acks) behind "Handing the world over to your
  friends…", then quits, or shows "Nobody else has a full copy yet (Sam 62%). Quit anyway?" (cancel returns to the game).
- `mc/host/HostSync.serverStopping`: the true final checkpoint, the wait (30 s; 10 s after "quit anyway" or with the dev switch), then
  `HANDOFF` to every sync peer and the rendezvous record (`handoff` + `next`, else `idle`) through `HostSession.announceLast`.
- `mc/handoff/MemberHandoff`: the successor leaves the session, checks that its manifest root is the final entry's root, and
  `CoopLauncher.hostAfterHandoff` opens the local copy and starts a `HostSession` (new `sess`). Everyone else gets
  `HostChangingScreen` ("Host is changing…", Cancel), which polls the rendezvous once a second until a host other than the old one
  answers a ping, then joins through the normal join gate.
- Self-tests: `M3Scenarios` (`handoff-3`, `handoff-no-full-copy`), suite `m3` in `scripts/selftest.ps1` (three clients: slots a, b, c).

### M3: decisions

Confirmed by the owner on 2026-11-17 (asked before the code was written):
1. `HANDOFF = 19` carries `{text successorUuid, text finalEntryJson}` and is sent only when there is a successor.
2. New member-to-host message `PROGRESS = 27 {i32 have, i32 total}`; the sync protocol number is now 3 (an M2b build and an M3
   build refuse each other's sync connection).
3. "Host is changing…" has a Cancel button and no time limit in M3; the 45 s failover is M4.
4. The development-only switch `dropin.dev.noHandoff`: a host with it quits without handing over. Every m1, m2 and m2b scenario
   sets it (`M1Scenarios.base`), so those scenarios keep their steps and assertions (a plain quit drops the members); the m3
   scenarios set it back to `false`. It is read through `DevSwitches`, so a production jar ignores it.

Made while building, not asked (flow, no wire or file format; tell the owner):
- The quit is in two halves, because the design's order (final checkpoint, wait, then the confirmation) cannot be followed
  literally while "cancel keeps the session alive" holds: the checkpoint and wait before the confirmation happen with the world
  running, and the stopping server takes one more (final) checkpoint and waits again before it sends `HANDOFF`.
- A quit that does not go through `disconnectFromWorld` (closing the window) still hands over at server stop if someone is fully
  synced, but shows no confirmation.
- The successor does not run the claim of DESIGN 4.3 (it was named); it publishes `starting`/`host` like any host session.
- A follower accepts any live host other than the old one, not only the one named in `next`.
- If the named successor finds its copy is not the final checkpoint it does not host; it waits on "Host is changing…" like the others.
- A member's percentage in the confirmation is capped at 99; with no name known the text says "a friend".
- Lang keys `text.dropin_coop.handoff.{preparing,nobody,a_friend,changing,taking_over}`.

Found on the way:
- `Minecraft.disconnect` calls `dropAllTasks()`. The old host closes the game connection right after `HANDOFF`, so a task queued
  with `mc.execute` on a member can be dropped; `MemberHandoff.onClient` queues it again until it has run.
- A field named `dropin` in `SyncClient` shadows the `dropin.` package in fully qualified names (again).

26.3 signatures read for M3 (NeoForm `vanilla-26.3-1-sources.jar`; DESIGN §8 has no handoff item of its own, items 1 and 2
were confirmed in M1; none of this contradicts the design):
- The host's quit: `PauseScreen.createPauseMenu()` (private, line 101) builds the disconnect button with
  `CommonComponents.disconnectButtonLabel(this.minecraft.isLocalServer())` and the action
  `this.minecraft.getReportingContext().draftReportHandled(this.minecraft, this, () -> this.minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE), true)`.
- `Minecraft.java:2093` `public void disconnectFromWorld(Component message)`: `level.disconnect(message)`, then
  `disconnectWithSavingScreen()` for a local server (else `disconnectWithProgressScreen()`), then the title screen.
- `Minecraft.java:2132` `public void disconnect(Screen screen, boolean keepResourcePacks, boolean stopSound)`: closes the
  `ClientPacketListener`, sets `singleplayerServer = null`, `level = null`, and for a local server calls `server.halt(false)` and
  renders frames until `server.isShutdown()`, then `setScreenAndShow(screen)`.
- `IntegratedServer.java:399` `public void halt(boolean wait)`: on the server thread removes every player whose UUID is not the
  host's (`getPlayerList().remove(player)`, which saves them), then `super.halt(wait)`. So the members' player files are written
  before `stopServer` runs.
- `IntegratedServer.java:393` `public void stopServer()` = `teardownPublishedState(); super.stopServer();`
  `MinecraftServer.java:622` `protected void stopServer()`: `getConnection().stop()` (this is where the members' game
  connections are closed), `playerList.saveAll()`, `playerList.removeAll()`, then the levels are saved and closed.
- A member losing the host: `ClientCommonPacketListenerImpl.java:365` `public void onDisconnect(DisconnectionDetails details)`
  calls `this.minecraft.disconnect(this.createDisconnectScreen(details), this.isTransferring)`;
  `protected Screen createDisconnectScreen(DisconnectionDetails details)` builds a `DisconnectedScreen`.

## M2b — DONE (2026-11-17), gate passed on `f277e1c` (see `docs/VERIFICATION.md`)

State: `./gradlew build`, `scripts/jarcheck.ps1`, `scripts/selftest.ps1 -Suite all` (37 of 37) and the online smoke
(`-Suite m2b -Online`, FerriteCore 9.0.0 downloaded from Modrinth) all passed in this session. Nothing is pushed.

**Next step: M3** (not started; one milestone per session). The fifteen protocol and file-format choices listed below were made
without asking, against the brief's rule; the owner confirmed them on 2026-11-17.

Open items carried forward (details under "Not verified" in `docs/VERIFICATION.md`):
- Nothing reads `pv`/`mh` from the rendezvous record yet; a policy change is found when a join asks the host.
- A connected *member* being sent away by a newer policy is coded but has no scenario (needs a third client, or an owner who can
  change the list without restarting).
- The helper has only run from Gradle-started dev clients, never under a real launcher or with the production jar.
- The manual checklist of M2b (below) is for a person with a real modpack and a friend.

Manual checklist (MILESTONES M2b), not done:
- [ ] A real modpack of 50+ mods on the owner's PC, one loader: a friend with an empty `mods/` folder joins after one
      approve-and-restart, in the launcher they normally use.
- [ ] The owner adds a mod and removes one; the friend is kept out, updates, and gets back in.
- [ ] The title-screen button and the Co-op Worlds screen still work inside that modpack; note any mod that hides or breaks them.
- [ ] The friend's own mods are all in `mods/.dropin-disabled/`.

Files touched in M2b: `core/.../policy/Policy`, `core/.../mods/*`, `core/.../sync/{Wire,SyncServerSession,SyncClientSession,PolicyClientSession}`,
`core/.../pkarr/RendezvousRecord`, `core/.../world/CoopWorldStore` and their tests; `mc/.../policy/*`, `mc/.../mods/*`,
`mc/.../menu/{ModConsentScreen,PolicyUpdateScreen,ModsScreen,PlayersScreen,CoopWorldsScreen}`, `mc/.../host/HostSync`,
`mc/.../member/SyncClient`, `mc/.../coop/{CoopLauncher,HostSession}`, the two new mixins, `dropin.mixins.json`, `en_us.json`;
`mc/src/selftest/.../{M2bScenarios,M2Scenarios,Scenarios}`; `build-logic/.../multiloader-loader.gradle` (test mods, watchdog
property); `scripts/selftest.ps1`; `docs/VERIFICATION.md`; `CLAUDE.md` (commands).

Found on the way (one failure, fixed): the mod installer asked the host for a file before its connection had finished the
handshake, and such a request is dropped silently. It now waits for the host's policy answer first.

DESIGN §8 items confirmed for M2b (none contradicts the design):
- Item 16, Fabric (loader 0.20.1, `DirectoryModCandidateFinder`, read at tag 0.20.1 on GitHub): `findCandidates` walks the mods
  folder with `Files.walkFileTree(path, EnumSet.of(FileVisitOption.FOLLOW_LINKS), 1, ...)`, depth 1, and `isValidFile` takes only regular,
  non-hidden files whose name ends in `.jar` and does not start with `.`. A jar under `mods/.dropin-disabled/<ts>/` is not loaded.
  API: `FabricLoader.getInstance().getGameDir()`, `getAllMods()` → `ModContainer.getMetadata().getId()/getVersion()`, `getOrigin().getPaths()`.
- Item 16, NeoForge (FML `loader-12.0.12.jar`, `javap -c` of `net.neoforged.fml.loading.moddiscovery.locators.ModsFolderLocator`):
  `findCandidates(ILaunchContext, IDiscoveryPipeline)` uses `Files.list(modFolder)` (not recursive), a `.jar` suffix filter, sorted by lower-case
  name; the folder is `FMLPaths.MODSDIR.get()`. Loaded mods: `ModList.get().getMods()` → `IModInfo.getModId()/getVersion()`.
  DropIn does not call either loader API: the mods folder is `<game dir>/mods` and mod id/version are read from each jar's
  `fabric.mod.json` / `META-INF/neoforge.mods.toml`, so `core/mods` stays loader-free.
  **Proven by the spike** (`swap-spike.fabric`, `swap-spike.neoforge`): a jar the running game had loaded from `mods/` was moved to
  `mods/.dropin-disabled/<ts>/` by the helper after the game exited, a new jar was installed, and the next start loaded the new set only.
- Item 17: `PlayerList.java:590` `public boolean isOp(NameAndId nameAndId)` = `ops.contains(...)` or
  `server.isSingleplayerOwner(nameAndId) ? server.getWorldData().isAllowCommands() : false`. `IntegratedServer.java:443`
  `public boolean isSingleplayerOwner(NameAndId nameAndId)` compares the name with `getSingleplayerProfile().name()`.
  `IntegratedServer.java:317` `public LevelBasedPermissionSet getProfilePermissions(NameAndId nameAndId)`: the singleplayer owner gets
  `LevelBasedPermissionSet.OWNER` if `isOp`, everyone else `getCustomPermissionLevel()` (`GAMEMASTER` when the world allows commands and
  `guestCommandAccess` is on, else `ALL`), falling back to `MinecraftServer.getProfilePermissions` (`MinecraftServer.java:1802`).
  So operator rights are decided in `IntegratedServer.getProfilePermissions`; that is where they follow the world's owner.
- Item 19: the enabled list is in `level.dat` (`PrimaryLevelData.java:149` `tag.store(WorldDataConfiguration.MAP_CODEC, settings.dataConfiguration())`,
  field `DataPacks` = `DataPackConfig{Enabled, Disabled}`), read without a server by
  `LevelStorageSource.readDataConfig(Dynamic<?> levelData)` (`LevelStorageSource.java:134`); the packs are the folders/zips under
  `LevelResource.DATAPACK_DIR` = `"datapacks"` (`ServerPacksSource.createPackRepository(LevelStorageAccess)`, line 79), applied in
  `WorldOpenFlows.loadWorldStem` through `WorldLoader.PackConfig`. Both can be read before the integrated server boots.
- Item 20 (docs.modrinth.com, read 2026-11-17): `POST https://api.modrinth.com/v2/version_files`, JSON body
  `{"hashes": ["<hash>", ...], "algorithm": "sha1" | "sha512"}`; 200 answers a map hash → version object (`id`, `project_id`,
  `version_number`, `loaders`, `game_versions`, `files[]` with `hashes{sha512, sha1}`, `url`, `filename`, `primary`, `size`); unknown hashes
  are simply absent. A uniquely identifying `User-Agent` is required. Rate limit 300 requests/minute per IP
  (`X-Ratelimit-Limit/-Remaining/-Reset`).

### M2b: what was built

Commits on `dropin`, local only: `b2dfb27` (swap-helper spike), `3b0264b` (core), `f5ab219` (mc side), `f277e1c` (scenarios, script).

- `core/policy/Policy`: the owner-signed record, canonical bytes, `sign`, `rejection(ownerFingerprint)`, `replaces` (version wins, then timestamp).
- `core/mods`: `ModManifest` (+ `Limits`, `safeName`), `ModScan`, `ModDiff`, `ModStore` (temp file, hash check, then atomic move),
  `ModrinthClient` (hash lookup, download), `SwapPlan`, `SwapHelper`, `HelperLauncher`.
- `core/sync`: messages `POLICY`, `POLICY_REQ`, `MEMBERS`, `MOD_GET`, `MOD_DATA`; `PolicyClientSession` (the short connection a member
  makes before joining). Mod bytes are only served where records may go (`bulkAllowed`), so they obey the path policy.
- `core/pkarr/RendezvousRecord`: `pv` (policy version) and `mh` (mod manifest hash), written only when `pv > 0`.
- `core/world/CoopWorldStore`: `membersJson`, `parseMembers` (roles from peers are ignored), `mergeMembers`, `learnOwnerKey`.
- `mc/policy`: `WorldPolicy` (stored policy, keep list, datapack reading, signing), `PolicyActions` (owner's changes), `HostGate`
  (checks before the integrated server boots), `DevHooks` (faults for the self-tests, development only).
- `mc/mods`: `PolicyLink`, `JoinGate` (policy and mod check before every join), `ModInstall` (download, verify, plan, start the helper).
- Screens: `ModConsentScreen`, `PolicyUpdateScreen`, `ModsScreen` (Mods tab, "keep" marks), owner controls on `PlayersScreen`
  (members' commands on/off, ban/unban), a "Mods" button on the Co-op Worlds screen.
- Mixins: `IntegratedServerPermissionsMixin` (`getProfilePermissions`: owner → `OWNER`; others `GAMEMASTER` or `ALL` by
  `membersMayUseCommands`), `PlayerListBanMixin` (`canPlayerLogin` refuses banned UUIDs).
- `HostSync`: holds the policy, serves it and the mod files, adopts a newer one mid-session (checkpoint, send to members, kick banned
  players, end its own session if this PC's mods are behind). `SyncClient`: stores a newer policy and leaves if its mods no longer match.
- Self-tests: `M2bScenarios` (suite `m2b`), relaunch lists and `Mods` per slot in `scripts/selftest.ps1`, the game's watchdog follows a
  scenario's `TimeoutSec` (`-Pdropin.selftestTimeoutSec`).

### M2b: decisions made where the design gives no detail — CONFIRMED BY THE OWNER (2026-11-17, "They stand")

The brief says to ask instead of inventing protocol or file-format details. These were chosen without asking during M2b; the owner
confirmed all fifteen after the gate. They are now fixed parts of the protocol and file formats:

1. Wire numbers: `POLICY=22 {policyJson, manifestJson}`, `POLICY_REQ=23 {i64 myVersion}`, `MEMBERS=24 {json}`, `MOD_GET=25 {sha512}`,
   `MOD_DATA=26 {sha512, u8 flags, bytes}` with flags `LAST=1, ABSENT=2, REFUSED=4`, 256 KB pieces. Sync protocol number is now 2.
2. Policy canonical bytes: text lines starting `dropin-policy 1`, fields in a fixed order. Policy JSON carries
   `datapackHashes: {files: {path: sha256}, enabled: [...]}`; "enabled" is only the `file/` entries of `level.dat`.
3. Files: `<world>/dropin/policy.json` and `<world>/dropin/modmanifest.json`. Manifest canonical bytes: `dropin-mods 1` plus
   tab-separated lines; `modManifestHash` is SHA-256 hex of them.
4. Limits (config keys in `config/dropin/coop.json`): `modMaxFileMB` 512, `modMaxTotalMB` 8192, `modMaxFiles` 1000.
5. Keep list: `config/dropin/keepmods.json` = `{"keep": ["file.jar", ...]}`. Mod store: `config/dropin/modstore/<sha512>.jar`.
6. Rendezvous fields `pv` and `mh` (see above).
7. The loader is enforced only when the mod list is not empty (a vanilla-only world can be joined from either loader).
8. The owner's first policy (version 1) is signed without asking at the first open; a later difference shows
   "Update this world's mod list?".
9. `membersMayUseCommands` starts as **false**; in self-tests it starts as true because the scenarios drive the game with commands.
10. A member's role comes from the policy's `ownerUuid`, never from what a peer says.
11. If the policy request to a host fails (network), the join goes on with the policy stored here; if the host's policy is
    invalid (wrong key, bad signature), the join is refused.
12. The first-join policy probe creates the save folder (marked as an unfinished download) to store the policy in.
13. Modrinth is asked only in a normal game or with `dropin.dev.online`; offline self-tests never contact it. User-Agent `dropin-coop/<version>`.
14. After "Download and restart" the game closes itself 60 ticks after the helper started. It does not restart the game.
15. A host whose own mods are behind a newer policy ends its session after a checkpoint and shows the consent screen. A member in
    that position disconnects itself (honest-client enforcement; a modified client is not stopped by this).

## M2 — DONE (2026-11-17), gate passed on `c3f94a3` (see `docs/VERIFICATION.md`)

**Next step: M2b** (owner policy and mod sync), in its own session. Start with the swap-helper spike (MILESTONES M2b step 1) and
confirm DESIGN §8 items 16, 17, 19, 20. The M2 manual checklist (two PCs over the internet) is the owner's to run.

What M2 built:
- `core/manifest` (`RecordKey`, `Manifest`), `core/ledger` (`LedgerEntry`, `Ledger`, `Deltas`), `core/sync` (`Wire`, `SecureChannel`,
  `SyncServerSession`, `SyncClientSession`, `SyncState`, `Transport`, `PathInfo`) with unit tests.
- `mc/net`: `ProtocolSniffer` (wraps the captured child handler through the new tunnel hook `E4mcClient.childHandlerWrapper`, the only
  `tunnel/` change: one field and helper in `E4mcClient`, one line in `ServerConnectionListenerMixin`), `SyncPipe`, `SyncConnector`, `PathPolicy`.
- `mc/host`: `ChunkStores` + `IOWorkerMixin` (dirty tracking), `ChunkBytes`, `HostSync` (checkpoints, serving), `MinecraftServerStopMixin`.
- `mc/member`: `SyncClient`, `RecordApplier`. Wiring in `DropIn.init`, `HostSession`, `CoopLauncher`, `CoopWorlds`, `CoopWorldsScreen`, lang.
- Self-tests: `M2Scenarios`, suite `m2` and kill-and-relaunch (`Relaunch`) in `scripts/selftest.ps1`. The sniffer was reliable; the
  second-session fallback was not needed.

Decisions the design did not spell out (for the owner to confirm or change):
- Chunk bytes are hashed as binary NBT with compound keys sorted (`CompoundTag` order differs between machines).
- The root hash covers keys as well as hashes. The manifest is one file, `dropin/manifest/current.tsv`; `deltas/<seq>.bin` is text.
- `state.json` also holds `have` and `total` (for the percentage). A copy is "unfinished" iff `state.json` says `complete: false`; a whole
  world brought over by hand counts as complete while it is being updated, so it can still be hosted if the host leaves mid-update
  (it is then a mix of two checkpoints until the next sync).
- An unfinished download cannot be opened or hosted: Play joins a live host or shows "Nobody is online and your copy is N% downloaded".
- A first join creates the save folder named after the world, with the marker, before any data arrives.
- A `CHECKPOINT` lists changed keys only on a bulk-allowed path and up to 2,000 keys; otherwise the member runs a diff.
- The host waits up to 10 s at quit for members to confirm the last checkpoint (M3 raises this for handoff). No checkpoint on pause.
- Leftover dirty keys at server stop go to `dropin/dirty.txt` and are folded into the next host session's first checkpoint.
- Rendezvous `head`/`seq` are not published yet; `PEERS`, `HANDOFF`, `BRANCH_PUSH` message numbers are reserved, not used.
- `config/dropin/coop.json` keys: `bulkSyncOverRelay` (false), `relayRateKBps` (128), `checkpointSeconds` (60, minimum 5).
- Development switches: `dropin.dev.forcePath=relayed|direct`, `dropin.dev.syncDelayMs`, `dropin.dev.checkpointSeconds`.
- LAN counts as direct when the connection is a plain TCP socket channel.

Open points found:
- A periodic checkpoint uses `saveEverything(true, false, false)`, which skips chunks saved in the last 10 s (vanilla cooldown); the quit
  checkpoint flushes everything.
- Online runs end with the "Client shutdown from post-main" watchdog report in B's log (also in M1's `drop-in.online`): slow exit after
  a Dialtone connection. Not investigated.
- The iroh `debugInfo` line was never logged in a real run; "direct" online is inferred from bulk records flowing.

M2 source confirmation (2026-11-17), finished:
- Item 4 confirmed: `IOWorker.store(ChunkPos pos, CompoundTag value)` and `store(ChunkPos, Supplier<CompoundTag>)` (the first calls the
  second: hook the Supplier overload); `loadAsync(ChunkPos)` → `CompletableFuture<Optional<CompoundTag>>`; `storageInfo()` returns
  `RegionStorageInfo(String level, ResourceKey<Level> dimension, String type)` with type `"chunk"` (ChunkMap:169), `"poi"` (ChunkMap:198),
  `"entities"` (ServerLevel:249). So `(dimension, kind)` comes from `storageInfo()`.
- Item 5 confirmed: `public RegionFileStorage(RegionStorageInfo info, Path folder, boolean sync)` with `read(ChunkPos)`,
  `write(ChunkPos, @Nullable CompoundTag)`, `flush()`, `close()`; `public RegionFile(RegionStorageInfo, Path path, Path externalFileDir, boolean sync)`.
  Neither needs a server.
  Also: `protected IOWorker(RegionStorageInfo info, Path dir, boolean sync)`, built only in `SimpleRegionStorage.java:29` and
  `RecreatingSimpleRegionStorage.java:28`; `store` and `loadAsync` both queue with `Priority.FOREGROUND`, and `loadAsync` returns the
  pending write's `copyData()` before it falls back to `storage.read(pos)`, so a read after `store` returned always sees that write.
- Item 6 confirmed: `ChunkMap.java:170` `levelStorage.getDimensionPath(level.dimension()).resolve("region")`; `ChunkMap.java:198-199`
  `new RegionStorageInfo(levelStorage.getLevelId(), level.dimension(), "poi"), storageFolder.resolve("poi")`; `ServerLevel.java:249-250`
  `"entities"` at `levelStorage.getDimensionPath(dimension).resolve("entities")`. `LevelStorageAccess.getDimensionPath(ResourceKey<Level> name)`
  returns `DimensionType.getStorageFolder(name, this.levelDirectory.path())`, and
  `DimensionType.getStorageFolder(ResourceKey<Level> name, Path baseFolder)` is `name.identifier().resolveAgainst(baseFolder.resolve("dimensions"))`.
  So every dimension, the overworld included, is `dimensions/<namespace>/<path>/{region,entities,poi}`; per-dimension `data` is beside them
  (`ServerChunkCache.java:92`). The record kind is taken from the folder name (the `RegionStorageInfo` type for region files is `"chunk"`).
- Item 7 confirmed: `ServerConnectionListener.startTcpServerListener(@Nullable InetAddress address, int port)` passes
  `new ChannelInitializer<Channel>() { protected void initChannel(Channel channel) { ... } }` to `ServerBootstrap.childHandler`. It only
  uses `pipeline.addLast` (`"timeout"` `ReadTimeoutHandler(30)`, `"legacy_query"`, `Connection.configureSerialization(pipeline, PacketFlow.SERVERBOUND, false, null)`,
  `connection.configurePacketHandler(pipeline)`, `connection.setListenerForServerboundHandshake(...)`), so it works when it is added behind
  the sniffer after the first bytes. `Connection.channelActive(ctx)` only calls super and stores `ctx.channel()` / `remoteAddress()`, so
  the late `fireChannelActive` is harmless. The tunnel hands the same captured handler to `QuiclimeSession` (`.streamHandler(handler)`
  line 270 for relay streams, `.childHandler(handler)` line 361 for `DialtoneServerChannel`), so wrapping it once in
  `ServerConnectionListenerMixin.interceptHandler` covers LAN, relay and Dialtone.
- Item 8 confirmed: `e4mc$connInfo()` returns `DialtoneChannel.debugInfo()` = iroh-java `Connection.debugInfo()`, else the channel's class
  name. Upstream `vgskye/iroh-java` branch `meower`, `rust/src/lib.rs:848-915`: one line per path,
  `"path {} active {} rtt {} local {local_addr} remote {remote_addr}\n"`, an address being `ip v4 priv|lloc|loop|glob`, `ip v6 priv|lloc|glob`,
  `ip unk unk`, `relay <url>`, `custom` or `unk`. Direct = the `active true` line whose remote starts with `ip ` (`core/sync/PathInfo`).
  Read at the branch head; the jar this build uses is 0.1.2, so the format is **unconfirmed against that exact version** until the online
  smoke shows a real line.
- None of items 4–8 contradicts the design.
- Other signatures used: `MinecraftServer.saveEverything(boolean silent, boolean flush, boolean force)`; `protected void stopServer()`
  (`IntegratedServer` overrides it as `public` and calls super); `getAverageTickTimeNanos()`; `BlockableEventLoop.submit(Runnable)`;
  `NbtIo.read(DataInput, NbtAccounter)`, `NbtAccounter.unlimitedHeap()`; `record ChunkPos(int x, int z)`;
  `ResourceKey.create(Registries.DIMENSION, Identifier.parse(...))`; `ServerNameResolver.DEFAULT.resolveAddress(ServerAddress)`;
  `ChatComponent.addClientSystemMessage(Component)`; `AlertScreen(Runnable, Component, Component)`.
- Found in M1: upstream `DialtoneAmbientSession.start()` is not thread-safe. `SyncClient` must not start it concurrently with a ping or a game connect.

Files added since the core half:
- `core/world/ImportCheck`, `WorldArchive` (+ tests); `CoopWorldStore.exportOwnerKey`.
- `mc/coop/`: `CoopWorlds` (store, `isCoopServer`, `rendezvous`, `coop.json`: `pkarrRelays`, `titleButton`), `StatusPing`, `CoopLauncher`, `HostSession`.
- `mc/menu/`: `CoopActions` (all menu logic, also used by the self-tests), `CoopStatus`, `CoopWorldsScreen`, `ImportScreen`, `InviteScreen`, `JoinInviteScreen`, `PlayersScreen`.
- `dropin/mixin/mc/`: `WorldOpenFlowsMixin`, `PlayerListOwnDataMixin`, `IntegratedServerFocusMixin`, `IntegratedServerNewCoopMixin`, `TitleScreenMixin`, `SelectWorldScreenMixin`, `PauseScreenMixin`, `WorldListEntryMixin`.
- `assets/dropin_coop/lang/en_us.json` (own namespace, so `tunnel/` resources stay untouched).
- `mc/src/selftest/.../M1Scenarios.java`; suite `m1` and online `drop-in.online` in `scripts/selftest.ps1` (which now also wipes `backups` and `config/dropin` per slot).

Decisions made while building (tell the owner; none changes a file format in DESIGN):
- §4.8 is a mixin at the two read sites of `singleplayer_uuid` (`PlayerList.loadPlayerData`, `IntegratedServer.selectLevelLoadFocusPos`), not a `level.dat` rewrite: no file of the world is touched and Remove needs no undo.
- The world list is rows of buttons with paging, not the vanilla list widget (less 26.x API surface).
- Title button: right of Singleplayer, shrinks to a 20px "Co" button with a tooltip when the window is too narrow.
- "Make co-op" imports in place without a backup (the world is open) and starts hosting at once.
- "New" sets a flag; `IntegratedServer.initServer` HEAD registers the world before its level loads.
- Export owner key writes `<game dir>/dropin-owner-key-<8 chars>.key` (no native file dialog; Browse is likewise left out).
- A dropped folder/zip that is a copy of a world this PC has the invite for is listed as is ("copy attached").
- A joined world is named "Co-op world <6 chars>": the invite carries no name.
- Host publishes `starting` (not `host`) until the e4mc domain arrives, for at most 30 s, so a remote friend waits instead of starting a second session. With the tunnel disabled it publishes `host` at once.
- `dropin.dev.online` (dev only) drops the LAN hint so the online smoke must use the relay.
- LAN is opened with commands on only in self-tests.
- `StatusPing.answers` is synchronized: upstream's `DialtoneAmbientSession.start()` is not thread-safe. A vanilla connect racing our first ping could still hit it; a guard there would be a `tunnel/` change outside the named hooks, so it was not made.

More 26.3 signatures used (all read in the sources):
`TitleScreen.createNormalMenuOptions(int topPos, int spacing)` (private, returns int); `SelectWorldScreen.init()` / `repositionElements()`;
`PauseScreen.init()`, field `private final boolean showPauseMenu`; `WorldSelectionList.WorldListEntry.<init>` calls `summary.getLevelName()`;
`IntegratedServer.initServer()`; `Screen.extractRenderState(GuiGraphicsExtractor, int, int, float)`, `onFilesDrop(List<Path>)`, `rebuildWidgets()`;
`GuiGraphicsExtractor.text/centeredText(Font, Component, int, int, int)`; `Checkbox.builder(Component, Font).pos().selected().onValueChange()`;
`LevelStorageSource.loadLevelSummaries(LevelCandidates)` (background executor), `LevelCandidates(List<LevelDirectory>)`, `LevelDirectory(Path)`, `getBackupPath()`;
`LevelSummary.levelVersion().minecraftVersion().version()`, `isLocked()`; `SharedConstants.getCurrentVersion().dataVersion().version()`;
`Minecraft.disconnectFromWorld(Component)`, `gameDirectory`, `keyboardHandler.setClipboard(String)`; `Button.onPress(InputWithModifiers)`, `KeyEvent(int, int, int)`;
`AlertScreen(Runnable, Component, Component)`, `ConfirmScreen(BooleanConsumer, Component, Component)`; `CreateWorldScreen.openFresh(Minecraft, Runnable)`.
Vanilla opens Create World by itself when the Singleplayer list is empty (found by the `menu` self-test).

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

## After M3: performance test and online handoff (2026-11-17)

Asked by the owner in chat ("make it seamless and performance test"). Numbers and runs: docs/VERIFICATION.md.

- `HostSync.Perf` / `takePerf()`: per-checkpoint cost (server thread held by the save; work off it). The checkpoint log line now shows `save N ms`.
- `PerfScenarios` + `-Suite perf` (in `$measureSuites`: runs by name only, never by `all`).
- `handoff-3.online` in the online suites (`-Suite m3 -Online`). `handoff-3`: B takes its own LAN port before taking over; C joins after B has its copy (`joinB(..., go)`).
- `HostSession`: an announce refused as stale (`PkarrClient.StaleRecordException`) is tried again after 2 s, at most 15 times, then every 60 s. Not from the design (DESIGN 4.3 gives 60 s for relays that are down); told the owner.
- Signatures used (26.3): `MinecraftServer.getTickCount()` (1413), `getTickTimesNanos()` (1798), `saveEverything(boolean silent, boolean flush, boolean force)` (605), `ServerChunkCache.hasChunk(int x, int z)` (256), `CommandSourceStack.withSuppressedOutput()` (242), `WorldPresets.NORMAL`.
- Left alone: `HttpPkarrClient.put` and `get` wait for every relay, so one slow relay slows each announce and each lookup. Returning early would change behaviour `PkarrTest` asserts; needs the owner's word.

## M3b: save points (DESIGN 4.16), in progress

Decided by the owner on 2026-11-17: owner only; smallest storage; ten per world, the eleventh replaces the oldest; export, import and "make single-player world"; player data included; commands `/dropin savepoint create|list|restore <name>` plus menu buttons; folder buttons Backups, Datapacks and (owner only) Mods; members rejoin by themselves after a restore (HANDOFF naming the host itself).
Mine, told to the owner: storage layout, `.dropinsave` file, automatic `before-restore` save point, import only from the same world, save points only on the owner's PC and commands only while the owner hosts.

State when the session stopped (usage limit, 2026-11-17):
- Done and committed: `core/savepoint/SavePoints` + `SavePointsTest` (unit tests pass); `mc/savepoint/SavePointActions`, `SavePointCommands`, `CommandsMixin`, `menu/SavePointsScreen`, the four new buttons in `CoopWorldsScreen`, the return-to-same-host path (`HostSync.returning`, `HostHandoff.quitToReturn`, `HostChangingScreen` with `endedSess`), `dropin/rescan` marker. This mc code COMPILES ONLY: nothing of it was run in the game.
- Not done: self-test scenarios `savepoint-rollback` and `savepoint-files` (MILESTONES M3b), suite entry in `scripts/selftest.ps1`, the `menu` scenario re-run (the Co-op Worlds layout changed: a new button row), the gate, docs/VERIFICATION.md entry, DESIGN 4.2 line for `dropin/rescan` and the note that restoring `before-restore` makes no new automatic save point.

## M3b: save points, gate passed (2026-11-17)

- Gate: `./gradlew build` exit 0, `scripts/jarcheck.ps1` exit 0, `-Suite all` 42/42. Record in `docs/VERIFICATION.md`.
- New: `mc/src/selftest/java/dropin/mc/selftest/SavePointScenarios.java` (`savepoint-rollback` a/b, `savepoint-files` solo),
  suite `m3b` in `scripts/selftest.ps1` (part of `all`), wired in `Scenarios.build`. `M2Scenarios.quitAndReportRoot/verifyRoot`
  and `M3Scenarios.Mark/MARK_A/MARK_B/mark/marked/markedOnServer/players` are now package-private so the new scenarios reuse them.
- Docs: DESIGN §4.2 lists `dropin/rescan`; §4.16 says restoring `before-restore` makes no new automatic save point; CLAUDE.md
  suites line.
- Observed, not a fault: a restore reports about 1390 changed records in a 1000-record flat world, because every loaded chunk
  is rewritten by the game at each save (time fields), so nearly all chunk records differ from the save point.
- Choices that were mine and wait for the owner's word: announce retry of 2 s up to 15 times after a refused announce; the store
  layout and the `.dropinsave` file; the automatic `before-restore`; import only from the same world; save points only on the
  owner's PC and commands only while the owner hosts; HANDOFF naming the host itself for the rejoin; the `dropin/rescan` marker;
  `HttpPkarrClient` still waits for all relays.
- Manual checklist (not run): open Co-op Worlds as owner and as member (Mods folder button only for the owner); Backups,
  Datapacks, Mods folder open the right folders; Save points screen: create, restore, delete, export, import, Solo world.
- Next: M4 only when the owner asks.

## Menu rework, admins, online heads, chat (2026-11-17, in progress, gate NOT run)

- Owner's decisions: admins get commands + kick/ban; green heads for everyone connected (player list in the announcement);
  an info button per world for everyone and a wrench for the owner; chat keeps errors only.
- Done and unit-tested: `Policy.admins` (signed `admin <uuid>` lines), `RendezvousRecord.pl` (12 players, fits one packet).
- Done, compiled: `HostSync.permissions` (admin level), `PolicyActions.setAdmin`, `HostSession` announces connected players,
  `CoopStatus.Status.isOnline`, new `CoopWorldsScreen` (Join/Create/Import, world rows with preview, heads, role sort),
  `WorldInfoScreen`, `WorldSettingsScreen`, admin button in `PlayersScreen`, tunnel notices to the log (`Mirror.addNotice`).
- Run alone: `-Suite ui` (`admin-online`) PASS 34.6 s. `menu` failed on my own new assertions (world joined by invite is not
  owned); assertions corrected, re-run of `menu` and `import-saves` was started but its result was not read.
- Left: read that result; look at the screenshots `fabric/runs/*/screenshots/dropin-coop-menu*.png` and adjust the look;
  gate (`build`, `jarcheck`, `-Suite all`; add `ui` to what `all` runs if wanted); VERIFICATION entry; MILESTONES entry.
- Network audit (grep): the mod sets no system properties, proxy, DNS, default SSL or authenticator; its only process launch
  is the mod-swap helper. Not tested at runtime.

# Verification

Proof of each gate run. One entry per run, newest last.

## M0 — 2026-11-16 — commit `b5c639a` — first run: 1 jar-check item open; passed after the owner's ruling (see follow-up)

Machine: Windows 11, JDK 25.0.5 (Microsoft), Gradle 9.7.1. Full logs: `build-logs/m0-gate-*.log`, `build/selftest-logs/`.

Commands, run once on this commit:

| Command | Result | Time |
| --- | --- | --- |
| `./gradlew clean build` | BUILD SUCCESSFUL | 20s |
| `./gradlew :core:test --rerun-tasks` | BUILD SUCCESSFUL, 10 tests, 0 failures | 9s |
| `powershell -File scripts/selftest.ps1 -Suite all` | exit 0 | 132s |
| `powershell -File scripts/jarcheck.ps1` | **exit 1** | <1s |
| `powershell -File scripts/selftest.ps1 -Suite m0 -Online` | exit 0 | 30s |

Scenarios (`m0`):

- PASS `lan-join.fabric-fabric` 29.9s
- PASS `lan-join.fabric-neoforge` 28.1s (Fabric host, NeoForge joiner)
- PASS `lan-join.neoforge-fabric` 29.7s (NeoForge host, Fabric joiner)
- PASS `status-ping` 27.8s (response `motd='DevA - dropin-selftest' players=1/8`)
- PASS `prod-guard` 8.1s (`dropin.core.dev.DevSwitchesTest`, 10 tests)
- PASS `tunnel-join` 30.0s (online smoke: host got a domain from the real e4mc relay, joiner connected by that domain)

Jar check (`build/libs/dropin-0.1.0+mc26.3.jar`):

- PASS exactly one jar
- PASS `fabric.mod.json`
- PASS `META-INF/neoforge.mods.toml`
- PASS one mixin config (`dropin.mixins.json`)
- PASS no class under `dropin/mc/selftest/`
- PASS `dev.dirs` relocated (`dropin/shadow/dirs/`)
- PASS `folk.sisby.kaleido` relocated (`dropin/shadow/kaleido/`)
- **FAIL** `io.netty.incubator.codec.quic` not relocated (155 classes at the original name)
- **FAIL** `link.e4mc.iroh` not relocated (10 classes at the original name)

Why the two failures are left open instead of fixed: both libraries bind prebuilt native libraries, downloaded at runtime from
`natives.e4mc.link`, by Java class name. The cached DLLs contain those names (`iroh_java_windows_x86_64_*.dll` matches
`link[/_]e4mc[/_]iroh`; `netty_quiche_windows_x86_64_74.dll` matches `io[/_]netty[/_]incubator[/_]codec[/_]quic`), so relocating
the Java side would break the binding, and rebuilding the natives is forbidden (CLAUDE.md, DESIGN §3 row 5). DESIGN §5 and the
gate say all bundled libraries are relocated. This needs the owner's ruling; M0 is not marked DONE until then.

### Follow-up, 2026-11-16 — M0 GATE PASSED

The owner ruled that the two JNI-bound libraries are exempt from relocation. DESIGN §5, the gate wording in `docs/MILESTONES.md`
and `scripts/jarcheck.ps1` were changed to say so (the check now requires them at their original names with no relocated copy).
No code or build file changed after `b5c639a`, so only the failed check was re-run, on the same jar:

- `powershell -File scripts/jarcheck.ps1` → exit 0, 9/9 items PASS (log: `build-logs/m0-gate-jarcheck2.log`), including
  PASS `unrelocated io.netty.incubator.codec.quic` (155 classes, 0 relocated copies) and PASS `unrelocated link.e4mc.iroh` (10 classes, 0 relocated copies).

### Manual checklist run by the agent, 2026-11-17

Production jar `dropin-0.1.0+mc26.3.jar` (built from `b5c639a`) in two new Prism Launcher 11.1.0 instances, signed-in account,
world = a copy of the self-test world, LAN opened by typing `/publish` in the game:

- PASS Fabric 26.3 (Loader 0.20.1, Fabric API 0.166.2+26.3): game started, mod listed, `Domain assigned: undamaged-moneybags.na.e4mc.link`.
- PASS NeoForge 26.3.0.103: game started, mod listed, `Domain assigned: macarena-flatbed.na.e4mc.link`.
- PASS (extra) a status ping from outside the game through each domain answered `26.3`, 1/8 players, the world's MOTD.
- PASS (extra) neither production log has a self-test, dev-mixin or offline-LAN line.
- **NOT DONE** one instance joining the other through the domain, in either direction: there is one account on this machine
  (a second login with the same account is refused by the server) and too little free memory to run two games safely.

Still unverified:

- The production jar in a real Fabric 26.3 or NeoForge 26.3 instance (all runs above are dev clients from Gradle; the merged jar
  itself was only inspected, never loaded by a launcher). This is the manual checklist.
- That the dev-only mixins and switches stay inert in a production instance, beyond the unit tests and the jar-content check.
- Real (authenticated) accounts: dev runs use offline `DevA`/`DevB` with `dropin.dev.offlineLan`.
- Two machines, NAT, or any network other than loopback plus the public relay.
- `tunnel-join` with a NeoForge client on either side (it ran Fabric host, Fabric joiner).
- The Dialtone direct path: the joiner's ticket lookup returned 404 and the join went through the relay.
- Dedicated server, macOS, Linux.
- The baseline "build as is" on upstream's commit only succeeded with placeholder `-PcurseforgeApiToken=none -PmodrinthApiToken=none`.

## M1 — gate run 2026-11-17 (passed after the follow-up below)

Commit `dfbf7ba` (branch `dropin`, local only). Windows 11, JDK 25.0.5, Gradle 9.7.1, loopback only.

Commands:

```
./gradlew build                                   # exit 0; 73 unit tests (core + mc), 0 failures
scripts/jarcheck.ps1                              # 9 PASS, 0 FAIL
scripts/selftest.ps1 -Suite all                   # log: build-logs/m1-gate-selftest.log
scripts/selftest.ps1 -Suite m1 -Only import-rejects   # re-run after its fix
scripts/selftest.ps1 -Suite m1 -Online            # online smoke, run twice (fail, fix, pass)
```

Suite `m0` (re-run as part of `all`): PASS `lan-join.fabric-fabric` 35.3s, PASS `lan-join.fabric-neoforge` 30.2s,
PASS `lan-join.neoforge-fabric` 29.3s, PASS `status-ping` 29.2s, PASS `prod-guard` 8.1s.

Suite `m1`:

- PASS `menu` 20.4s (Fabric): title button present, inside the screen, overlapping no other widget at the default window; opens the screen; lists a known world.
- PASS `import-saves` 26.6s (Fabric): `dropin/world.json`, key file, backup zip, invite decodes to the same world id; the Singleplayer list opens with the world marked co-op and has the Co-op Worlds button.
- PASS `import-zip` 22.0s (NeoForge): zip dropped on the screen, extracted to `saves/zipped-world`, imported; original untouched.
- PASS `import-rejects` 22.7s (Fabric), **after one fix**. First run FAILED: an open world was reported "could not be read" instead of "is open right now" (the summary of a world this game has open cannot be read). Fix: the open-here check now runs before the summary is read. Re-run alone: PASS. Covers open world, already co-op, path-escaping zip, folder without `level.dat`.
- PASS `remove` 23.1s (NeoForge): `dropin/` and both key files gone; world opens as plain singleplayer, not on LAN.
- PASS `drop-in.fabric-neoforge` 32.8s and PASS `drop-in.neoforge-fabric` 35.0s: A presses Play and hosts; B presses Play and lands in A's session with no address typed; B places a block A reads.
- PASS `host-swap-own-inventory` 37.9s (Fabric/Fabric): after the swap B hosts with B's item at B's position and without A's; A joins with A's item at A's position and without B's.
- PASS `race` 36.4s (Fabric/Fabric): start time agreed through a shared file; exactly one hosted, both ended in one session.
- PASS `owner-invite` 21.5s and PASS `owner-invite.unit` 7.1s.
- **FAIL `players-tab` 61.6s.** A's side passed its checks (both names, A owner) and then timed out waiting for B. B lists both names but fails "A is marked as owner": `Players tab: DevA owner=false`. Not a bug in the code: in M1 nothing carries the owner's identity to a member (member records travel in the M2 sync handshake, the policy in M2b, and DESIGN 4.3 fixes the rendezvous fields). The gate line asks for more than M1's design can deliver. Waiting for the owner's decision; the test was not changed.

Online smoke `drop-in.online` (real e4mc relay, real pkarr relays, Fabric/Fabric, both clients on this PC):

- First run FAILED after 366s: B found A through pkarr and the status ping answered, then the connect hung. Cause (thread dump in `build/selftest-logs`, first run's log since overwritten): two of DropIn's threads pinged the e4mc domain at once, upstream's `DialtoneAmbientSession.start()` ran twice, and the connect blocked on the first endpoint. Fix in `mc` only: `StatusPing.answers` is one-at-a-time. `tunnel/` untouched.
- Second run PASS 64.2s: A announced `starting`, then `host` with `attractor-large.na.e4mc.link`; B joined that domain; one Dialtone session started on B.

**Follow-up, same day: M1 GATE PASSED.** The owner decided "Move check to M2b": B showing A as owner is now gate `m2b`
`owner-known-to-members` (MILESTONES changed), and the B-side assertion left the M1 scenario for that reason only.
`scripts/selftest.ps1 -Suite m1 -Only players-tab` → PASS `players-tab` 36.7s (both sides list both names; A is owner on A's PC).

Not verified in this run:

- The full suite was not re-run after the two fixes; only the failed checks were (as the build prompt says).
- GUI scale 2 and 4, Mod Menu, any window size other than the default; the look of every screen (no screenshots were taken).
- The `[Co-op]` badge text in the Singleplayer list (only that the list opens with the mixin applied).
- New (Create World from the Co-op Worlds screen), "Make co-op" in the pause menu, Invite/Join/Players/Import screens as clicked by a person, `titleButton` and `pkarrRelays` in `config/dropin/coop.json`, skins of real accounts.
- A world locked by another game process, an older or newer world version (unit tests only).
- First join without a local copy (connects as a plain client): not run.
- Real accounts, two machines, NAT. The online smoke ran host and joiner on one PC.
- The five-minute heartbeat and the `idle` record on quit were not asserted directly (host-swap relies on B being able to host after A quit).

## M2 — 2026-11-17 — commit `c3f94a3` — GATE PASSED

Machine: this PC (Windows 11, JDK 25.0.5, 8 GB RAM), both clients on it. Commands, in this order, each run once on `c3f94a3`:

- `./gradlew build` → `BUILD SUCCESSFUL` (unit tests included; M2 added `ManifestTest` 10, `LedgerTest` 7, `SyncTest` 13). Log: `build/logs/gate-build.log`.
- `powershell -ExecutionPolicy Bypass -File scripts/jarcheck.ps1` → exit 0, all six lines PASS (one mixin config, no self-test classes, the two relocations, the two unrelocated JNI packages). Log: `build/logs/gate-jarcheck.log`.
- `powershell -ExecutionPolicy Bypass -File scripts/selftest.ps1 -Suite all` → exit 0, 22 of 22:
  - m0: PASS `lan-join.fabric-fabric` 30.6s, PASS `lan-join.fabric-neoforge` 30.2s, PASS `lan-join.neoforge-fabric` 33.0s, PASS `status-ping` 28.7s, PASS `prod-guard` 7.1s.
  - m1: PASS `menu` 17.9s, PASS `import-saves` 25.1s, PASS `import-zip` 19.9s, PASS `import-rejects` 20.5s, PASS `remove` 21.5s, PASS `drop-in.fabric-neoforge` 28.6s, PASS `drop-in.neoforge-fabric` 29.2s, PASS `host-swap-own-inventory` 34.3s, PASS `race` 33.7s, PASS `owner-invite` 20.4s, PASS `owner-invite.unit` 7.1s, PASS `players-tab` 32.2s.
  - m2:
    - PASS `vanilla-through-sniffer` 28.8s (A Fabric, B NeoForge): a status ping and an ordinary login by address both work; A counted at least two game connections through the sniffer and none taken for sync.
    - PASS `first-download-root-match` 38.0s (A Fabric, B NeoForge): B has only the invite, is in A's world while the copy downloads; after A quits, B's saved state is at A's last checkpoint, B's files re-read from the closed folder hash to the root A reported, and B hosts the copy alone and finds the block A placed.
    - PASS `live-checkpoint` 36.4s (A NeoForge, B Fabric), 5 s interval: a block A places is in the chunk in B's copy within 10 s.
    - PASS `resume` 129.9s (Fabric/Fabric): B's game process is killed by the script mid-download (60 ms per record on the host); started again, B's sync begins with the kept records counted as present and ends with the root A reported, proven from B's files. In the trial run before the gate the log line was "after the restart the sync starts at 192 of 1227 records; 192 were kept".
    - PASS `path-policy` 38.9s (Fabric/Fabric): path forced to "relayed", `bulkSyncOverRelay` absent: the host sent 0 record frames to its one sync peer; B stays in "waiting", wrote nothing, and the waiting line was put into chat and is the status line.
- Online smoke, once: `powershell -ExecutionPolicy Bypass -File scripts/selftest.ps1 -Suite m2 -Online` → PASS `first-download-root-match.online` 71.2s. Real e4mc relay and pkarr relays; A announced `spoon-customary.na.e4mc.link`; B joined it, downloaded 952 records over Dialtone connections and matched A's root `d298a62e06e6884250d685333da82a48`; B then hosted alone.

Failures before the gate run, and what was done (trial runs of single scenarios, not counted as the gate):

- `first-download-root-match` failed its last step: the block was not in B's copy although the roots matched. Cause: region workers are created with storage type `"chunk"`, and `ChunkStores` only accepted `region`/`entities`/`poi`, so no chunk was ever hashed or sent (the manifest held files only). Fix: the kind is the folder name. One fix, re-run passed.
- `resume` failed on an assertion of the test itself (written this session): it compared all record writes after the restart with the world size, but those writes include chunks the host changed again during the 70 s download. The assertion now reads what the reconnecting sync reports as already present. The gate line (continues, finishes, root matches) was not relaxed.

Not verified in this run:

- That the online path was direct is **inferred**: bulk records are only sent when the host's `PathInfo.direct(debugInfo)` is true, and they were sent. The raw `debugInfo` line was not logged, so its format is still unconfirmed against the iroh-java version in the jar. Both clients were on one PC, so this says nothing about NAT.
- A relayed real path (only the forced one), `bulkSyncOverRelay: true`, the relay rate cap, the TPS pause.
- A large world (the biggest here was about 1,200 records), other dimensions with data, a world with entities and POI of note.
- The 60 s default interval; the test interval was 5 s. A chunk the game saved less than 10 s ago is not re-saved by a periodic checkpoint (vanilla's own save cooldown), so a change can take one interval longer.
- A hand-copied folder being diffed instead of downloaded (it runs in the M1 scenarios, but nothing asserts on it).
- The "Downloading N%" and "Copy N% downloaded" status lines and the "nobody online, N% downloaded" screen as seen by a person.
- A diverged copy (unit tests only; it is left untouched until M5).
- Both online runs (`drop-in.online` in M1 and this one) end with vanilla's "Client shutdown from post-main" watchdog report in B's log after the result was written: a client that used a Dialtone connection takes more than 10 s to exit. Not looked into.

## M2b — 2026-11-17 — commit `f277e1c` — GATE PASSED

Machine: this PC (Windows 11, JDK 25.0.5, 8 GB RAM), both clients on it. Commands, in this order, each run once on `f277e1c`:

- `./gradlew build` → `BUILD SUCCESSFUL`. Log: `build/logs/gate-m2b-build.log`. The unit tests were up to date in that run, so they were run again uncached: `./gradlew :core:test --rerun :mc:test --rerun` → 133 tests, 0 failed, 1 skipped (the opt-in `RealRelayOnlineTest`). M2b added `PolicyTest` 7, `ModsTest` 9, `SwapHelperTest` 5, `PolicySyncTest` 6, `MembersMergeTest` 3. Log: `build/logs/gate-m2b-unit.log`.
- `powershell -ExecutionPolicy Bypass -File scripts/jarcheck.ps1` → exit 0, every line PASS (both loaders' metadata, one mixin config, no self-test classes, the two relocations, the two unrelocated JNI packages). Log: `build/logs/gate-m2b-jarcheck.log`.
- `powershell -ExecutionPolicy Bypass -File scripts/selftest.ps1 -Suite all` → exit 0, 37 of 37. Log: `build/logs/gate-m2b-selftest-all.log`.
  - m0: PASS `lan-join.fabric-fabric` 29.0s, PASS `lan-join.fabric-neoforge` 27.2s, PASS `lan-join.neoforge-fabric` 29.2s, PASS `status-ping` 27.1s, PASS `prod-guard` 7.1s.
  - m1: PASS `menu` 18.4s, PASS `import-saves` 26.0s, PASS `import-zip` 20.4s, PASS `import-rejects` 20.4s, PASS `remove` 21.9s, PASS `drop-in.fabric-neoforge` 30.8s, PASS `drop-in.neoforge-fabric` 29.7s, PASS `host-swap-own-inventory` 35.9s, PASS `race` 37.3s, PASS `owner-invite` 20.9s, PASS `owner-invite.unit` 7.1s, PASS `players-tab` 29.8s.
  - m2: PASS `vanilla-through-sniffer` 29.7s, PASS `first-download-root-match` 38.9s, PASS `live-checkpoint` 40.1s, PASS `resume` 139.7s, PASS `path-policy` 39.0s.
  - m2b (A is always the owner; test mods X and Y each add one item, Z only sets a system property):
    - PASS `policy-unit` 8.1s (`dropin.core.policy.*`), PASS `mods-unit` 10.1s (`dropin.core.mods.*`).
    - PASS `swap-spike.fabric` 36.6s, PASS `swap-spike.neoforge` 35.8s: the step-1 spike, kept in the suite.
    - PASS `mod-join.fabric` 51.4s, PASS `mod-join.neoforge` 49.2s: A hosts with X. B, with an empty `mods/`, presses Play and gets the consent screen listing X instead of a connection; nothing is in the store before the click. B approves; the file is fetched from A and verified, the swap is planned, B's game exits, the helper installs X. B is started again by the script, the loader has X's item registered, Play leads into A's session, and B holds the X item A gave it.
    - PASS `mod-decline` 39.5s: B cancels; five seconds later B has no connection, `mods/` and the mod store are empty, no swap is planned, and A never had a second player.
    - PASS `mod-change-live` 97.3s (two restarts): A (X) hosts, B (X) joins and gets a complete copy, A quits, B hosts. A's game is restarted with X and Y; Play shows "Update this world's mod list?" with Y as added; A says yes (policy version 2). A's join attempt hands version 2 to B's session: B stores it, its session ends and B sees the consent screen in its "mods changed" form listing Y. A then hosts with version 2. B presses Play without updating and gets the consent screen again, not a connection. B approves, is restarted, joins and holds the Y item.
    - PASS `mod-tamper` 36.9s: A first answers with a policy signed by another key: B shows "not signed by its owner", does not connect and stores no policy. A then serves X with one byte changed: B's download ends with "did not match the owner's mod list", `mods/` and the store stay empty, no swap is planned.
    - PASS `mod-keep-extra` 42.6s: B has X and Z and marks Z "keep": it joins without being asked to remove Z. After A quits, B's Play is refused with the kept-extras message and the world stays closed.
    - PASS `owner-known-to-members` 41.6s (A Fabric, B NeoForge): B's Players tab marks A, and only A, as owner while B is in A's session and still when B hosts after A quit. B has no owner key.
    - PASS `owner-rights` 44.6s (A NeoForge, B Fabric): while hosting, A turns member commands off and bans a UUID (versions 2 and 3 reach B over the sync connection). B then hosts and A joins: A's `/give` works, B's own `/give` does nothing, B's server reports `OWNER` for A and `ALL` for B, and `PlayerList.canPlayerLogin` refuses the banned UUID with the ban message.
    - PASS `datapack-mismatch` 42.1s: a pack folder the owner does not have is put into B's closed copy; B's Play is refused with the datapack message and the world stays closed.
    - PASS `mods-tab` 31.8s: before first contact B knows no mod list. With the consent screen up, "Show list" opens the Mods tab: X with its mod id and version, marked missing here; B's Z marked extra here; after "keep" Z is shown as kept and is no longer to be removed. On A the tab lists X without a mark.
    - PASS `swap-restores` 58.4s (NeoForge): B starts with its own Y and Z. After approve and restart, neither is loaded, neither is directly in `mods/`, and each is under `mods/.dropin-disabled/<time>/` with the same SHA-512 as the file B started with.
- Online smoke, once: `powershell -ExecutionPolicy Bypass -File scripts/selftest.ps1 -Suite m2b -Online` → exit 0, PASS `mod-join.online` 107.6s. Log: `build/logs/gate-m2b-online.log`. Real e4mc relay and Modrinth. A's only mod was FerriteCore 9.0.0 for Fabric (`ferritecore-9.0.0-fabric.jar`, 72,677 bytes, fetched by the script from Modrinth's CDN and checked against the published SHA-512). Modrinth's hash lookup knew it, so A's signed list names Modrinth as its source. A announced `spotted-unfair.na.e4mc.link`. B's log: "Mod file ferritecore-9.0.0-fabric.jar downloaded from Modrinth and verified"; the helper reported `{"ok":true,"moved":[],"installed":["ferritecore-9.0.0-fabric.jar"]}`; B was restarted and joined A through the relay address. The scenario asserts that every file B needed came from Modrinth.

Failures before the gate run, and what was done (trial runs of single scenarios, not counted as the gate):

- `mod-join.fabric` failed once: "the host stopped sending dropin-testmod-x.jar" after a two-minute wait. Cause: the installer opens its own connection to the host and asked for the file before the handshake was finished; a request sent before that is dropped. Fix: it now waits for the host's policy answer before asking, and sends the request on the connection's own thread. One fix, re-run passed. Every other m2b scenario passed on its first run.

Not verified in this run:

- A member that is connected to someone else's session when a newer policy with other mods arrives. The code path exists (the member stores the policy, leaves and shows the consent screen), but in `mod-change-live` the session that ends is B's own host session, because the owner has to restart to change mods and only two clients were run. What was seen on a connected member is the policy arriving and being stored (`owner-rights`), with matching mods.
- Reading `pv`/`mh` from the rendezvous record: the host publishes them, but nothing reads them yet. A changed policy is found by the request every join makes to the host.
- A banned player who is connected at the moment of the ban being kicked (the code is there; the scenario checks the login refusal).
- Mod files being refused over a relayed path with a real relay: covered by the unit test of the sync channel only. In the online smoke the file came from Modrinth, as intended.
- The buttons as clicked by a person (the scenarios call what the buttons call): Mods button, "keep", ban/unban, the commands toggle, Yes/No on the update screen. No screen was looked at for layout.
- Wrong loader, wrong Minecraft version, a different enabled-datapack list (only a different pack folder was tested), the mod size and count limits in the game (unit tests only), a mod list with many or large files, Modrinth's rate limit.
- The helper under a real launcher (the dev clients are started by Gradle) and with a production jar; the online smoke on NeoForge.
- Everything on the manual checklist (a real 50+ mod pack, a friend's own launcher).

## M3 — 2026-11-17 — commit `19ea3fc` — GATE PASSED

Machine: this PC (Windows 11, JDK 25.0.5), all clients on it (three for `handoff-3`). Commands, in this order, each run once on `19ea3fc`:

- `./gradlew build` → `BUILD SUCCESSFUL`. Log: `build/logs/m3-gate-build.log`. Unit test reports of `core` and `mc`: 146 tests, 0 failed, 1 skipped (13 new: `SuccessorTest` 8, four handoff/progress tests in `SyncTest`, one handoff-record test in `PkarrTest`).
- `powershell -ExecutionPolicy Bypass -File scripts/jarcheck.ps1` → exit 0, every line PASS. Log: `build/logs/m3-gate-jarcheck.log`.
- `powershell -ExecutionPolicy Bypass -File scripts/selftest.ps1 -Suite all` → exit 0, 39 of 39. Log: `build/logs/m3-gate-selftest-all.log`.
  - m0: PASS `lan-join.fabric-fabric` 52.6s, PASS `lan-join.fabric-neoforge` 44.4s, PASS `lan-join.neoforge-fabric` 29.2s, PASS `status-ping` 29.3s, PASS `prod-guard` 8.1s.
  - m1: PASS `menu` 19.9s, PASS `import-saves` 28.3s, PASS `import-zip` 22.6s, PASS `import-rejects` 22.0s, PASS `remove` 22.0s, PASS `drop-in.fabric-neoforge` 31.8s, PASS `drop-in.neoforge-fabric` 29.8s, PASS `host-swap-own-inventory` 36.9s, PASS `race` 34.3s, PASS `owner-invite` 20.5s, PASS `owner-invite.unit` 8.1s, PASS `players-tab` 30.3s.
  - m2: PASS `vanilla-through-sniffer` 35.4s, PASS `first-download-root-match` 38.8s, PASS `live-checkpoint` 38.0s, PASS `resume` 136.3s, PASS `path-policy` 40.9s.
  - m2b: PASS `policy-unit` 8.1s, PASS `mods-unit` 9.1s, PASS `swap-spike.fabric` 36.6s, PASS `swap-spike.neoforge` 36.1s, PASS `mod-join.fabric` 49.4s, PASS `mod-join.neoforge` 48.8s, PASS `mod-decline` 34.4s, PASS `mod-change-live` 87.6s, PASS `mod-tamper` 32.3s, PASS `mod-keep-extra` 38.4s, PASS `owner-known-to-members` 37.3s, PASS `owner-rights` 40.5s, PASS `datapack-mismatch` 38.9s, PASS `mods-tab` 29.8s, PASS `swap-restores` 48.4s.
  - m3 (all Fabric):
    - PASS `handoff-3` 44.7s: A hosts; B and C join by invite and get full copies; each of the three gives itself an item and moves to its own place. A quits through `disconnectFromWorld`. A names B as successor before quitting and in the `HANDOFF` it sends; the rendezvous record becomes `handoff` with `next` = B. B, with no input, ends up hosting; the root of its copy at takeover and its checkpoint number equal the last entry of the ledger of A. C, with no input, is back in the world on the session of B. A presses Play and joins B. On the server of B all three players have their item and are at their place, and each client sees the same for itself.
    - PASS `handoff-no-full-copy` 133.0s: A hosts a world of at least 450 records and serves B slowly (`dropin.dev.syncDelayMs=250`). With B mid-download A quits: after the checkpoint and the 30 s wait A is shown the confirmation (a `ConfirmScreen`); it names DevB with a percentage between 1 and 99, no successor, and the world is still running with B in it. A cancels: A is back in the game, still announced as host, B was never disconnected, received no handoff, and its download goes on. B then leaves by itself.
- Online smoke: MILESTONES lists none for M3; none was run.

Scenarios that run with the development-only switch `dropin.dev.noHandoff=true` (the decision of the owner, 2026-11-17): every client scenario of m1, m2 and m2b (it is set in `M1Scenarios.base`, which all of them start from). It only has an effect where a host quits while a member is connected (for example `first-download-root-match`, `resume` and `host-swap-own-inventory`, where A quits with B fully synced); which of the other scenarios reach that point was not listed one by one. Those scenarios keep proving a plain quit (the members are dropped; the copy equals the final root). The m0 scenarios do not host a co-op world. The two m3 scenarios set the switch to `false`.

Trial runs before the gate (single scenarios, not counted as the gate): `-Suite m3 -Only handoff-no-full-copy` PASS 136.9s (confirmation "DevB 19%"), `-Suite m3 -Only handoff-3` PASS 42.0s (C reconnected 2.1 s after the handoff). No scenario failed in this milestone.

Not verified in this run:

- Handoff over the internet: every client was on this PC and used the LAN hint, the e4mc tunnel was off, the rendezvous was the shared-folder one. No pkarr relay, no e4mc domain, no Dialtone connection took part in a handoff. The 10 to 30 s the design expects was not measured (here C was out for about 2 s).
- The successor being a NeoForge client, or a mixed-loader handoff: both m3 scenarios are Fabric only.
- The Save and Quit button as clicked by a person, and the Yes and Cancel buttons of the confirmation (the scenarios call what the buttons call). No screen was looked at for layout.
- "Quit anyway" (answering yes): only cancel is covered by a scenario.
- A quit by closing the window (handoff at server stop, without the confirmation): coded, no scenario.
- The Cancel button of "Host is changing…", and what a follower sees when the successor never appears (no time limit in M3; failover is M4).
- A successor that may not host (mods or datapacks differ from the policy), and a successor whose copy turns out not to be the final checkpoint: coded, no scenario.
- More than one fully synced member being ranked by connection time in the game: `handoff-3` asserts that B (who joined first) was chosen, and the order itself is unit-tested, but B and C start within seconds of each other.
- A member on a relayed path during a handoff (it gets `HANDOFF` but is never the successor): unit-tested in the sync channel only.
- The manual checklist (three real players, timing how long the others are out of the world).
- The open items of M2b are unchanged (nothing reads `pv`/`mh`; a member sent away by a newer policy has no scenario; the helper under a real launcher).

## Performance and online handoff: 2026-11-17 (after M3, before M3b)

Measurements and one fix. Run on this PC, working tree on top of `a2d81f5`.

**Performance** (`scripts/selftest.ps1 -Suite perf`, a measurement, not a gate; not part of `all`). PASS 287.2s. Log: `build/selftest-logs/perf-a.log`, lines `Self-test perf`.
Normal-terrain world, 512 force-loaded chunks (2606 records), commands change 8 chunks a second in every phase, checkpoints every 10 s (`dropin.dev.checkpointSeconds=10`), both clients on this PC.

| Phase | Ticks per s | Tick avg | Tick p99 | Tick max | Longest gap between ticks | Save pause per checkpoint | Work off the server thread |
|---|---|---|---|---|---|---|---|
| 1 alone, not hosting (60 s) | 20.0 | 4.07 ms | 5.28 ms | 12.9 ms | 68 ms | none (one game save: 31 ms) | none |
| 2 hosting, nobody joined (60 s) | 20.0 | 4.02 ms | 5.00 ms | 8.5 ms | 73 ms | avg 14 ms, max 16 ms | avg 51 ms, max 61 ms |
| 3 hosting, B downloading (20 s) | 20.0 | 4.67 ms | 11.08 ms | 13.2 ms | 68 ms | avg 21 ms, max 22 ms | avg 60 ms, max 60 ms |
| 4 hosting, B has its copy (60 s) | 20.0 | 4.42 ms | 5.82 ms | 16.6 ms | 68 ms | avg 18 ms, max 20 ms | avg 54 ms, max 57 ms |

First checkpoint (full scan of 2606 records): save 18 ms, 694 ms off the server thread. B had its full copy 19850 ms after it joined.
The "longest gap" is read once per client tick, so it cannot show less than about 50 ms.

**Handoff through the real relay** (`scripts/selftest.ps1 -Suite m3 -Online`, scenario `handoff-3.online`, three clients on this PC, real e4mc relay and real pkarr relays):
- Run 1: FAIL. Test setup: the clients of a run share one LAN port and A still held it when B opened to LAN. `handoff-3` now gives B its own port.
- Run 2: PASS 160.3s. C was back 71 s after the handoff: B's first announce was refused by the pkarr relays (HTTP 409/428, A's "handoff" record still being stored) and the next try was 60 s later.
- Fix: `HostSession` tries again 2 s after an announce that was refused as stale, at most 15 times in a row, then every 60 s as before.
- Run 3: FAIL in the scenario, not the handoff: through the relay C connected before B, the world correctly went to C, the scenario expects B. C hosted 5 s after the handoff and B found it after 6449 ms. `handoff-3` now lets C join after B has its copy.
- Run 4: PASS 78.4s. Handoff at 14:32:30, B announced as host at 14:32:34, C found B after 5051 ms and was in the world by 14:32:37.

**Gate after these changes:** `./gradlew build` exit 0 (`build/logs/gate-build.log`), `scripts/jarcheck.ps1` exit 0 (`build/logs/gate-jarcheck.log`), `scripts/selftest.ps1 -Suite all` exit 0, 39 of 39 (`build/logs/perf-gate-selftest-all.log`; `handoff-3` 48.8s, `handoff-no-full-copy` 134.8s).

Not verified:
- Performance with players on different PCs, with more than one member, with more loaded chunks than 512, with mobs (the world was peaceful), or at the default 60 s interval.
- The member's side (frame rate, disk and CPU while downloading) was not measured.
- The online handoff with players in different networks. All three clients were on one PC and one internet connection; relay speed varied between runs (an announce took 1 to 8 s).

## M3b: save points (DESIGN §4.16): 2026-11-17

Gate run on this machine, one pass, on the tree committed after this entry:

| Check | Result |
| --- | --- |
| `./gradlew build` (includes the `dropin.core.savepoint` unit tests) | exit 0 |
| `scripts/jarcheck.ps1` | exit 0 |
| `scripts/selftest.ps1 -Suite all` | 42 of 42 PASS (39 earlier scenarios, `savepoint-unit`, `savepoint-rollback`, `savepoint-files`) |

What the two new scenarios checked (logs: `build/selftest-logs/savepoint-*.log`):

- `savepoint-rollback` (45.0 s, two Fabric clients). A hosts, B joins and has a full copy. A runs `/dropin savepoint create base`
  (save point at checkpoint 3). A places a block, both players clear their inventory and teleport away, and the changes reach a
  checkpoint. A runs `/dropin savepoint restore base`. With no further input: A's world closes, is restored, and A hosts again;
  B shows "Host is changing" and is back in the world (in the run made alone before the gate: 5066 ms after the handoff). Then
  checked on the server: the later block is air, the earlier probe block is still there, both players have their item and place
  as at the save point; a `before-restore` save point exists; the `dropin/rescan` marker was taken. After A quit, B's closed copy
  was read back from disk and hashes to the root A ended on.
- `savepoint-files` (25.6 s, one client, world closed). Eleven save points made: ten are kept and the oldest is gone. One is
  exported (`.dropinsave`, 543929 bytes for 985 records in the run made alone), deleted, and imported back with the same root and
  record count. A single-player world made from a save point has a `level.dat`, no `dropin/` folder, opens, has the probe block,
  and is not hosted.
- `menu` passed with the new button row on the Co-op Worlds screen.

Not verified:
- The Save points screen and the four new buttons were not clicked: no test opens `SavePointsScreen`, presses Backups, Datapacks
  or Mods folder, or checks that the Mods folder button is hidden for a member. Only the code under them ran (through the
  scenarios above), and the `menu` scenario shows the screen still opens.
- `/dropin savepoint list`, the refusals (a member or a non-owner running the commands, a bad name, an unknown name), restoring
  from the menu with the world closed, deleting from the menu, importing a file of another world, and file drop onto the screen.
- Restore with more than one member, with a member on another PC or through the relay, on NeoForge, and of a large world.
- Restoring the `before-restore` save point (undo of a restore).
- How much disk the store really saves over time: only that identical records are stored once (unit test).

## Menu rework, admins, PvP and realtime checks: 2026-11-17

Gate run on this machine, one pass, on the tree committed with this entry:

| Check | Result |
| --- | --- |
| `./gradlew build` | exit 0 |
| `scripts/jarcheck.ps1` | exit 0 |
| `scripts/selftest.ps1 -Suite all` | 46 of 46 PASS |

New or changed scenarios (logs: `build/selftest-logs/`):

- `menu-flows`: top buttons in the order Create, Join, Import; the import picker by its buttons (choose a world, Import, invite shown, world marked, no longer offered); Export world (zip in the backups folder, extracts to a world with `level.dat` and no `dropin/`); Join screen (bad text keeps Join off, an invite turns it on, preview says nobody online, nothing saved before Join, then both worlds listed with the owned one first).
- `admin-online`: the join preview by invite names the host and both connected players; admin grant and removal; online heads.
- `pvp`: with the pvp game rule on, B's hit hurts A and A's hit hurts B (20 to 19 health), and the hurt animation (`hurtTime`) is seen on both clients for A, B and a pig; with the rule off neither hit does damage; B walked 8.85 blocks and the host and A's screen had B 0.000 blocks from where B said it was.
- `realtime`: host changes reached the other client in 22 ms (block), 84 ms (item), 50 ms (health and effect), 184 ms (mob moved); B's hit on a pig was on the host's screen within 48 ms.

Not verified:
- All clients were on one PC over loopback; delays over the internet or a relay were not measured. No test with three or more players fighting.
- The owner's report that the hit animation does not play was not reproduced: the test reads `hurtTime`, it does not look at the rendered picture.
- `pvp` failed four times while it was being written, each time from the test's own setup (knockback out of reach, walking into the other player, a one-tick race); one `admin-online` run found nobody on a single lookup and passed on rerun, cause not found, the check now asks again like the menu does.
- Export world of a large world, the Export button itself (the action under it ran), file drop on Import, the Join screen while a host is online (only its lookup was run), NeoForge for all new screens, the chat change, and other programs' networking at runtime (source audit only).

## PvP and realtime over the relay; status ping second try: 2026-11-18

Gate run on this machine, on the tree committed with this entry:

| Check | Result |
| --- | --- |
| `./gradlew build` | exit 0 |
| `scripts/jarcheck.ps1` | exit 0 |
| `scripts/selftest.ps1 -Suite all` | 46 of 46 PASS |
| `scripts/selftest.ps1 -Suite ui -Online` (before the status ping change) | `pvp.online` and `realtime.online` PASS, 3 of 3 runs each |

- Over the real e4mc relay (second client forced off the LAN shortcut): hits both ways with pvp on, none with it off, hurt animation (`hurtTime`) on both clients for both players and a pig, B walked 8.85 blocks with 0.000 blocks of difference on the host and on A's screen; realtime delays 34 to 210 ms (block, item, health and effect, mob moved), B's hit on a pig on the host's screen within 55 to 60 ms.
- Two gate runs before this one failed, 44 of 46 (`host-swap-own-inventory`, `owner-rights`) and 45 of 46 (`mod-change-live`). Logged cause in the second: "No status answer from 127.0.0.1:<port> within 5000 ms (the game's pinger says: Pinging...)" while the host was up, so the launcher took the world as not hosted. The failed scenarios passed 12 of 12 when run alone. `StatusPing` now opens a second status connection after 2 s within the same 5 s limit.

Not verified:
- Why a status connection is sometimes never answered. The gate that passed did not log a use of the second connection, so the change is not shown to be what made it pass.
- The owner's report (Fabric, relay: no hit animation on the player being hit) was not reproduced; the tests read `hurtTime`, not the picture, and both clients ran on one PC.
- Earlier `pvp` failures were test flaws, fixed in the test: pigs left in the kept self-test world made the two clients watch different pigs; a fed player healed the one point of damage before the check saw it; knockback out of reach.

## World integrity, large modpacks, other software, moving things: 2026-11-18

Gate run on this machine, on the tree committed with this entry:

| Check | Result |
| --- | --- |
| `./gradlew build` | exit 0 |
| `scripts/jarcheck.ps1` | exit 0 (`no-selftest-classes`: 0 entries) |
| `scripts/selftest.ps1 -Suite all` | 48 of 48 PASS (new: `integrity`, `motion`) |
| `scripts/selftest.ps1 -Suite ui -Online -Only motion.online` | PASS, 2 of 2 runs with the final measurement |
| `scripts/selftest.ps1 -Suite integrity -Online` (`integrity.pack`) | PASS (before the motion work; main sources unchanged since) |
| `scripts/selftest.ps1 -Suite m2 -Online -Only first-download-root-match.pack` | PASS (same) |
| `scripts/selftest.ps1 -Suite perf` | measurement, see below |

Jar: `build/libs/dropin-0.1.0+mc26.3.jar`, 1,134,414 bytes.

- `integrity` (A, B Fabric, C NeoForge): ores, a chest, a villager (place, inventory, trades), mob health and each player's health, effects and items, decoded from the host's records and from each member's copy at the same checkpoint, are equal on A, B and C (46 lines), again after A handed the world to B, and the closed copies of A and C read back in full equal checkpoint 11 of B's ledger.
- Found and fixed by it: a former host kept about 1,350 to 1,470 records its shutdown wrote after the final checkpoint (mostly half-generated border chunks). `SyncClient.reconcileLeftover` now hashes those again from disk before the first comparison.
- `motion` (A, B Fabric, C NeoForge; LAN and through the real e4mc relay): a minecart on powered rails (25.7 blocks), a cow led by wheat (6.9 blocks) and a cow fleeing after a hit (10.8 to 12.3 blocks) followed tick by tick on the host's server and on three screens. Farthest any screen was from the server's path: 0.15 blocks; screens 100 to 150 ms behind the server (the game draws between updates); at rest within 0.0001 blocks; health, baby, glow and name equal on every screen within 110 ms. No change to the mod was needed: these things run only on the host's server and reach every player by the game's own packets.
- Real mods, 15 Fabric mods from Modrinth in every game (107 mod entries loaded): lithium 0.26.3, sodium 0.9.3-beta.5, c2me-fabric 0.4.2-alpha.0.119, ferritecore 9.0.0, cloth-config 26.3.159, Jade 26.3.5, appleskin 3.0.10, entityculling 1.12.1, ImmediatelyFast 1.17.1, krypton 0.3.2, FarmersDelight 26.3-3.6.30 (refabricated), TerraBlender 26.3.0.0.9, architectury 22.0.6, geckolib 5.5.7, yacl 3.9.7.
- Found and fixed by them: with Krypton the host's world never finished loading (two `@Redirect`s of `Crypt.getCipher`; ours is now `@WrapOperation`, DESIGN §3 row 15, owner's decision). With C2ME the mod saw only 25 of the world's records, because C2ME stores chunks with its own class; DropIn now notices this, compares every chunk at every checkpoint and says so in the log once.
- `perf` (512 loaded chunks, 2,598 records, 8 chunk edits a second): a checkpoint holds the server thread 14 to 19 ms on average (most 23), its work off that thread 51 to 60 ms (10 to 15 ms CPU); first full scan 641 ms; first download 53,143,777 bytes in about 10 s on loopback; 5,300,728 bytes a minute to one member while editing; 20 ticks a second in every phase.
- Other software, measured on a host serving one member: one listening TCP port (the game's own, a free port), one UDP socket, two HTTPS connections to Mojang; DropIn threads: host, status, sync, sync-serve. Read in the code, not measured: no `System.setProperty`, proxy, DNS, firewall or `netsh` use; the only process started is the mod-swap helper; relayed traffic is capped at `relayRateKBps` (128).
- Test flaws on the way, fixed in the tests, no test weakened: the integrity decoder expected `Name` in palette entries; the motion track's braking rails were powered by their neighbours; the motion comparison measured to sample points and lacked the path's starting point (two relay runs failed with 0.89 and 0.93 blocks at 0 to 50 ms after the start, on the host's own screen too).

Not verified:
- Blocks, entities, items or dimensions added by mods: the pack runs used vanilla content in a flat world. NeoForge with real mods. More than 15 mods. Mods on Forge-only or other loaders.
- Minecarts with riders or chests, slopes, curves, the experimental minecart rules; boats; mobs other than a cow; mob fights; mobs in unloaded or far chunks; villagers' behaviour over time.
- Two PCs: every run had all games on this one PC, so real latency, packet loss and a slow link are not covered. The picture itself was not looked at; positions and status were read from the game.
- Other programs (browser, Steam, video) running beside it; only sockets and threads were counted.
- Open, not decided: records are sent uncompressed; folders that mods add to a world's root are not copied; no bandwidth cap on direct connections; with C2ME every checkpoint compares all chunks (330 ms for about 1,100 records); `SyncServerSession` closes without a log line on an error.

## Mod folders in the world root, sync error log line, direct rate cap, fastest player takes over: 2026-11-18

Gate run on this machine, on the tree committed with this entry:

| Check | Result |
| --- | --- |
| `./gradlew build` (unit tests included) | exit 0 |
| `scripts/jarcheck.ps1` | exit 0 |
| `scripts/selftest.ps1 -Suite all` | 49 of 49 PASS (new: `sync-extras`) |

Jar: `build/libs/dropin-0.1.0+mc26.3.jar`, 1,137,782 bytes.

- `sync-extras` (A, B Fabric, C NeoForge, LAN): `serverconfig/testmod-server.toml` and `testmod/deep/state.dat`, written into the host's world root, are in B's and C's copies with the host's content. With both members treated as "over the internet" (`dropin.dev.forcePath=internet`) and a cap of 256 KB/s, 18,080,056 bytes of records were sent and the most in any one second was 276,607 bytes. B connected first and answered the upload test 2 s late (`dropin.dev.speedDelayMs`): measured 126 KB/s, C 98,814 KB/s, and C is the one who would take over.
- Unit tests (core): the file rule (mod folders allowed; region files, `dropin/`, locks, temp files and paths that leave the folder refused; 64 MB limit for mod files only), the successor order (fastest step first, about-equal speeds and "fast enough" speeds do not decide, an unmeasured peer ranks last, no full copy is never chosen), the cap's delay, the upload test between a host and a member session, and that a frame the host cannot read is reported and ends the connection.
- Records are sent uncompressed: nothing was changed, DESIGN 4.6 now says so.
- A test was changed on purpose: `ManifestTest.filePolicyFollowsTheDesign` used to require that `mods/evil.jar`, `options.txt` and a top-level file `data` in a world's root are NOT replicated. By the owner's decision they now are (they stay inside the world folder).
- The cap used to round every record under about 2 KB down to no wait at all; waits are now carried over between records.

Not verified:
- The default cap (2048 KB/s) on a real internet connection, and that home-network players are recognised as such: the run forced "internet" on one PC. The cap is per connection, not for all members together.
- The upload test over a real link: on one PC it measures the PC. It is a single 256 KB burst and includes one round trip, so it is rough for fast links.
- A real handoff decided by speed (the test asked the host who would take over; `handoff-3` still hands over for real, to the longest connected of two equal players).
- The log line of `HostSync.sessionError` in a running game (the callback is unit-tested; nothing in a game run made a connection fail).
- Real mods' own folders (only test files were used), files that a mod keeps open or rewrites constantly, files over 64 MB.
- Crash failover is milestone M4 and not built; when the host's game dies without a handoff nobody takes over yet, by speed or otherwise.

## M4: crash failover, stale-copy warning, status line: 2026-11-18 — GATE PASSED

Gate run on this machine, on the tree committed with this entry:

| Check | Result |
| --- | --- |
| `./gradlew build` (unit tests included) | exit 0 |
| `scripts/jarcheck.ps1` | exit 0 |
| `scripts/selftest.ps1 -Suite all` | 52 of 52 PASS (new: `crash-failover`, `crash-fallthrough`, `stale-warning`) |

Jar: `build/libs/dropin-0.1.0+mc26.3.jar`, 1,163,659 bytes. Logs: `build/logs/m4-gate2-*`.

- `crash-failover` (A, B Fabric, C NeoForge, LAN): A placed a block; checkpoint 4 with it was on B and C; both had the host's list with the order B, C (C answers the upload test 2 s late). The script killed A's game process. B took over 4,609 ms after losing A, at checkpoint 4 with the root A had reported, and the block was in B's world. C was back in B's session 7,075 ms after losing A, with no input, and saw the block. The pause-menu line read "Host: DevA | Your copy: 100% | direct | Checkpoint: 0 s ago" before the kill; afterwards B's said it hosts and C's named DevB.
- `crash-fallthrough`: the script killed A and B (first in the order) together. C (NeoForge) took over 47,088 ms after losing A, that is after B's 45 s, at checkpoint 4, with the block.
- `stale-warning`: B's copy was at checkpoint 2; A's second session, without B, ended on 5; nobody online. B pressed Play and got the warning about DevA's session. Cancel: nothing opened, and all 38 files of B's copy had the same size, time and SHA-256 as before. Play anyway: B hosted its older copy, without the block of A's second session.
- Unit tests (core): the election order (highest `syncedSeq`, then upload step, then lowest UUID; the host, duplicates and members without a checkpoint left out), the 45 s per place, the `PEERS` message between a host and a member session and its limits, `Ledger.knows`, the record's `head`.
- A scenario was changed on purpose: `mod-change-live` (m2b). After the owner's mod change ends B's session, the owner's copy is behind B's session and nobody is online, so the owner now gets the new warning. The scenario requires the warning (about DevB) and answers "Play anyway"; nothing else in it changed. It failed in the first full run (51 of 52) for this reason; the table above is the second full run.

Not verified:
- The manual check of M4 (pull the network cable or kill the host on a second machine): everything ran on one PC over the LAN path.
- Failover through the e4mc relay or between machines; the 5 s ping of a dead address behind a router (here it is refused at once); a host that hangs but still answers the ping (no failover happens then, by design).
- More than two members, two members whose turns fall together (the claim of DESIGN 4.3 is relied on, not exercised in a crash), a member at an older checkpoint than another, a member with an incomplete copy.
- The `handoff` record case (host dies in the middle of a handoff), an unreachable rendezvous, and a failover over the real pkarr rendezvous: the self-tests use the file rendezvous.
- A crash between two checkpoints with changes in between: the test kills right after a checkpoint, so the loss of up to one interval was not measured.
- The stale warning after a crash: `head` is written at state changes and the heartbeat only, so it can be older than the last checkpoint. The warning's wording and "x hours ago" text were not looked at on screen.
- The pause-menu line was built from game state and the pause menu was opened without an error, but nobody looked at where it sits on screen; "relayed" and "connecting…" were not seen.
- A production jar in a real launcher: the mixin on `ClientCommonPacketListenerImpl` ran in the dev clients of both loaders only.

## M5: divergence and merge: 2026-11-19 — GATE PASSED

Gate run on this machine, on the tree committed with this entry:

| Check | Result |
| --- | --- |
| `./gradlew build` (unit tests included) | exit 0 |
| `scripts/jarcheck.ps1` | exit 0 |
| `scripts/selftest.ps1 -Suite all` | 55 of 55 PASS (new: `merge-clean`, `merge-conflict`, `merge-too-old`) |

Jar: `build/libs/dropin-0.1.0+mc26.3.jar`, 1,211,208 bytes. Logs: `build/logs/m5-gate-*`, single runs `build/logs/m5-clean-2.log`, `m5-conflict-1.log`, `m5-tooold-1.log`.

All three scenarios start alike (offline, file rendezvous): B gets a full copy from A and leaves; A hosts a session alone and places a block at spawn; B's copy is now behind, B gets the stale-copy warning and answers "Play anyway", so its copy forks, and places a block; then A hosts and B joins.
- `merge-clean` (A, B Fabric): B's block is 500 blocks away. On joining, B's copy kept its session as branch `<B's head>` (`SyncClient.lastSplit()`), followed A, and sent the branch; A received the same head. Nothing merged while A's world was open. A quit and pressed Play: one merge before the world booted, checkpoint 9 with `parents2` = B's head, "25 chunks applied, 0 kept"; both blocks on A's server; the report was put in A's chat once. B joined again, its copy completed, and after A quit all 1,046 records on B's disk hashed to A's root; B's ledger has the merge and no branch is left.
- `merge-conflict` (A Fabric, B NeoForge): both blocks in chunk 0,0. The merge kept A's chunk (A's block in the world, B's not), wrote B's `chunks/minecraft/overworld/region/c.0.0.nbt` (containing `minecraft:emerald_block`) under `dropin/backups/conflicts/<time>-DevB/`, and the chat line read "Merged DevB's offline session: 0 chunks applied, 1 kept from the main world, 3 files kept (backup saved)." Roots matched afterwards (1,052 records).
- `merge-too-old` (A NeoForge, B Fabric): B's delta files were deleted before meeting A (what the 30-day prune does). B saved its session as the separate world "Co-op world … (DevB's branch)" (a world folder with `level.dat`, no `dropin/` marker, its far chunk with the hash B's last checkpoint had), sent no branch, and followed A. A received nothing, ran no merge, its ledger has no merge entry, its block is there; roots matched (1,026 records).
- Found by the first `merge-clean` run and fixed: 81 chunks were "kept" although only B's far chunk was touched. They differed only in `LastUpdate` / `InhabitedTime`, which the game rewrites on every save. The merge now compares a content hash for chunk region records (DESIGN 4.10 "As built in M5").
- Unit tests (core): merge planner rules (branch-only, both changed, deletions, three records of a chunk together, player files together, world-level files stay, idempotent re-plan, content hashes), `changedSince` and "too old", branch bundle, delta file formats and prune, `BranchStore`, merge entry and `Ledger.truncate`; sync: a forked copy preserves its branch, sends it and follows the host; a copy ahead of a host that plays an older copy becomes a branch; nothing is cut or sent over a relayed path; a merged branch is not asked for; a tampered branch is refused.
- `Wire.PROTO` is 5: builds before M5 and M5 builds do not sync with each other (they refuse at the handshake).

Not verified:
- The manual check on two PCs, and anything over the e4mc relay: every run was on one PC over LAN. The relayed-path rule (no branch is cut or sent without a direct path) has a unit test only.
- The player's own offline inventory: in every merge the diverged player's own player files are "kept from the main world", because that player joined main before the merge (DESIGN 4.10 by the letter). Their offline inventory, stats and advancements end up under `backups/conflicts/`, not in the world. Open question for the owner. *Resolved 2026-11-19, see "M5 follow-up: the offline player's own files" below.*
- A fork whose checkpoints someone else hosted (this PC has no deltas for them): handled as "too old" in M5, not exercised.
- Two or more pending branches at once, a branch relayed by the host to a third member, a branch merged by a member that takes over after a handoff or crash, a merge cut short and planned again (unit test only), items moved between a conflicting and a clean chunk (the known rough edges of DESIGN 4.10), map ids allocated on both sides.
- Copies made before M5 (no `content.tsv`, two-column deltas): their region records fall back to record hashes or "too old"; not exercised in a game.
- The real 30-day prune (the scenario deletes the delta files; `Deltas.prune` has a unit test).
- The chat lines were checked as text in the log, not looked at on screen.

## M5 follow-up: the offline player's own files (2026-11-19)

Owner decision: the diverged player's offline inventory belongs in the world. `MergePlanner.plan(..., owner)` now always takes the files of the branch's player (the host of the branch head entry) from the branch; main's copy is replaced and saved under `backups/merged/`. Other players keep the old rule.

- `./gradlew :core:test --tests "dropin.core.merge.*" :mc:compileSelftestJava` (JDK 25): exit 0; `MergeTest` 24 tests, 0 failures, including the new `theBranchOwnersFilesComeFromTheBranchEvenIfMainChangedThem`.
- `selftest.ps1 -Suite m5 -Only merge-conflict`: PASS (71 s). Merge checkpoint 9: 0 chunks and 4 files applied, 1 chunk and 0 files kept; no `files/players` under the conflict backup (new check in the scenario). Log `build/logs/ownfiles-conflict.log`.
- `selftest.ps1 -Suite m5 -Only merge-clean`: PASS (63 s). Merge checkpoint 8: 25 chunks and 3 files applied, 0 kept. Log `build/logs/ownfiles-clean.log`.

Not verified: the full `-Suite all` and `jarcheck` were not rerun for this change; the restored inventory was checked through the merge report and the conflict folder, not by looking at B's items in game; whatever B did on main between joining and the merge is lost to the world (it is in `backups/merged/`).

## Robustness fixes from the post-M5 review (2026-11-19)

Four defects found by a code review, fixed:

1. **Merge cut short.** `ColdMerge` checks every branch record it will apply (hash) before touching the world, and leaves `dropin/rescan` while merging, so a merge that stops between writing the world and saving the manifest makes the next host compare every record (`HostSync.takeRescan`). A rescan marker left by a restore is kept.
2. **Member sync outliving the wait.** `RecordApplier` refuses all work after `close()` (a late frame can no longer reopen region files). `SyncClient.stopAndWait` now returns whether the sync let go (its thread and its connection's thread ended, applier closed), waits up to 60 s, and every caller refuses to open the copy otherwise (`CoopLauncher.syncReleased` shows "text.dropin_coop.sync_busy"; handoff and crash takeover fail like other takeover errors). A new sync waits for the old one to let go instead of 15 s.
3. **Case-insensitive file systems.** `RecordKey.fileAllowed` also checks the lower-cased path, refuses a game path in another case (`Level.dat`, `Players/...`), segments ending in a dot or space, and Windows device names.
4. **Torn ledger line.** `Ledger.load` drops an unfinished last line (no line end, does not parse or verify) and rewrites the file; appends are forced to disk; delta files are written through a temp file.

- `./gradlew :core:test :mc:test` and compile of every module: exit 0. New tests: `ManifestTest.filePolicyIgnoresCaseLikeWindowsDoes`, `LedgerTest.anAppendCutShortIsDroppedOnLoad` (12 and 9 tests in those classes, 0 failures).
- `./gradlew build`: exit 0; jar `build/libs/dropin-0.1.0+mc26.3.jar` (1,214,805 bytes). `scripts/jarcheck.ps1`: exit 0.
- `selftest.ps1 -Suite all`: 55/55 PASS (`build/logs/fix4-gate.log`), which also covers the offline-player-files change above.

Not verified: no scenario forces a merge to stop half way, a sync thread to hang past 60 s, a mixed-case path from a peer, or a torn ledger in a running game (each is covered by unit tests or code reading only); the "sync busy" screen was never shown; behaviour on a case-sensitive Linux file system.

## Two hosts at once, and the slow quit (2026-11-19)

5. **Two hosts after a false failover.** New `core/.../pkarr/HostRival` (unit-tested): a record of another session, `host` no older than 10 min or `starting` no older than 60 s, published *after* this session claimed the world, is a rival. A record from before this session started (a crashed host's last record that a slow relay still serves) never is, so crash takeovers are not affected. `HostSession` looks the record up before every publish; with a rival it does not publish, also not "idle"/"handoff" when it closes, logs once and tells its players in chat (`text.dropin_coop.host.rival`). It announces again once the rival's record is gone. It does not close the world by itself (my choice; the design does not say). *Changed the same day at the owner's request: see "A second host closes the first one's world" below.*
6. **Slow quit.** A background full scan stops when the server stops (what it drained is marked dirty again). The final checkpoint of a stopping server no longer does a full scan that is owed once (after a restore, a first host): it records what was written in this session, leaves `dropin/rescan` for the next host start on this PC, and does not hand the world over. When another mod hides its chunk writes (C2ME), the final checkpoint still does the full scan, because that is the only way the session's changes are found.

- `./gradlew :core:test :mc:test` and compile of every module: exit 0; `HostRivalTest` 3 tests, 0 failures.
- `./gradlew build`: exit 0; jar `build/libs/dropin-0.1.0+mc26.3.jar` (1,217,146 bytes). `scripts/jarcheck.ps1`: exit 0.
- `selftest.ps1 -Suite all`: 54/55 PASS (`build/logs/fix56-gate.log`). `crash-failover` FAILED because client C's Gradle could not acquire the fabric-loom cache lock while three clients started at once ("Exception acquiring lock Lock for cache=...fabric-loom"), so C never launched; not a test of the change. Rerun alone: `-Suite m4 -Only crash-failover` PASS (82.6 s, `build/logs/fix56-crash-rerun.log`).

Not verified: no scenario creates two hosts at once (the rival path was never taken in a game, and the chat line was never shown); no scenario quits during an owed full scan, so the deferral path and the "no handoff" consequence were not run; the world staying open next to a rival is a judgement call for the owner.

## A second host closes the first one's world (2026-11-19)

Owner decision: when a second host appears, the first one closes its world automatically. `HostSession` now also looks for a rival every 60 s (not only before a publish; `dropin.dev.rivalCheckSeconds` shortens it in development). On a rival it tells its players in chat and calls `HostHandoff.quitForRival` on the client thread: `HostSync.yieldToRival()` (no handoff, the short quit wait), the normal final checkpoint, `disconnectFromWorld`, then an alert ("… Your session was closed so the world does not split in two …") that leads to the Co-op Worlds screen. The other session's record is not overwritten. What was played is kept in this copy and reaches the other session's world through the M5 merge when this player joins it.

- New scenario `second-host` (suite `m4`, one client): A hosts; a "host" record of another session is written to the rendezvous; A closes its world, shows the alert, hands nothing over, and the record is still the other session's. PASS alone (31.6 s, `build/logs/rival-run1.log`).
- `./gradlew build`: exit 0. `scripts/jarcheck.ps1`: exit 0.
- `selftest.ps1 -Suite all`: 55/56 PASS (`build/logs/rival-gate.log`). `handoff-no-full-copy` FAILED: A "fell out of the world" in the void just as the quit confirmation appeared, so it was never "back in the game"; no rival line in its log. Rerun alone: `-Suite m3 -Only handoff-no-full-copy` PASS (132 s, `build/logs/rival-hnfc-rerun.log`). The void fall looks like a rare flake of that scenario's world, not of this change.

Not verified: members of the closed session were not part of the test (in a real game they lose the host and, finding the other session's live record, join it; with an unreachable rival they could start a takeover of their own); the merge of the closed session's play into the other world after a real second host; the alert was checked as a screen type, not looked at.

## Join code commands: 2026-11-23

| Check | Result |
|---|---|
| `./gradlew build` | exit 0; jar `build/libs/MCo-op-0.1.0.jar` |
| `scripts/jarcheck.ps1` | exit 0 |
| `scripts/selftest.ps1 -Suite ui -Only join-code` | PASS (41.7 s) |
| `scripts/selftest.ps1 -Suite m3b` | savepoint-unit, savepoint-rollback, savepoint-files PASS (`/dropin savepoint` still works beside `/dropin code`) |

- `join-code`: the host's `/coop code` puts the world's own join code in its chat as a click-to-copy part; B's `/dropin code` shows the same code; B's `/coop code share` reaches both players' chat ("DevB shared the join code for dropin-selftest: …").
- Earlier runs failed in the test only: B's local list names the joined world differently from the host, so the check now matches the phrase and the code, not the name.
- Not run: a guest without the mod (DESIGN 4.11) asking for or receiving the code. The rule is enforced by `HostSync.holdsWorldKey` (the host, or a sync session that passed the key proof); no self-test starts a vanilla client.

## Joining from another network without the long wait: 2026-11-23

Cause, from the 26.3 sources: `ServerStatusPinger.pingServer` calls `Connection.connectToServer`, which blocks on
`connect.syncUninterruptibly()` with no connect timeout of its own (Netty default 30 s). The record's `lan` address is tried
first; from another network nobody answers it, so every ping hung there before the e4mc domain was tried, and a launch pings
up to three times. Pkarr reads also waited for every relay (8 s timeout each).

| Check | Result |
|---|---|
| `./gradlew :core:test --tests "dropin.core.pkarr.*"` | 28 tests, 0 failures (1 online test skipped); new `httpClientDoesNotWaitForASlowRelayOnceAnotherHasAnswered` 1.2 s with one relay 6 s slow |
| `./gradlew build`, `scripts/jarcheck.ps1` | exit 0, exit 0 |
| `selftest.ps1 -Suite m1 -Online -Only drop-in.foreign-lan` (real e4mc relay, record's `lan` = 10.255.255.1, unanswered) | PASS 81.3 s; B: "10.255.255.1:56609 does not accept connections (Connect timed out); not pinging it" once in the launch, then "DevA is hosting, joining curtain-entomb.na.e4mc.link" about 2 s after the launch began |
| `selftest.ps1 -Suite m0` | 5 of 5 PASS (LAN join still works, both loaders) |
| `selftest.ps1 -Suite m1` | 8 of 8 PASS |

Not run: the old code against the unanswered address (the 30 s figure is Netty's default, not measured here); two real PCs on different home networks.

## "This world is not a co-op world" from /coop code: 2026-11-24

Found in the owner's own game log (Modrinth profile): `World sync of 'Co-op world ef7f47' could not start … ledger.jsonl is damaged at entry 11 … Ledger entry 6 rejected: does not follow the head`. The ledger held entries 1-10, then 6-10 again byte for byte, then 11-34; the world's `dropin/` folder also holds `ledger.sync-conflict-20261123-233225-RQTJNVI.jsonl` and the logs folder Syncthing temp files, so a file-sync tool wrote over the files of the world in use. With no sync, the command said "not a co-op world".

| Check | Result |
|---|---|
| `./gradlew build` (all unit tests) | exit 0; `LedgerTest` 10 tests, 0 failures, new `entriesWrittenTwiceAreDroppedOnLoad` |
| `scripts/jarcheck.ps1` | exit 0 |
| `selftest.ps1 -Suite ui -Only join-code` | PASS 41.4 s |
| The owner's ledger, read only (not changed): exact repeats dropped | 34 entries, seq 1-34 consecutive |

Not run in a game: the red "sync could not start" chat line, `/coop code` for a host without a sync, and the owner's world opening with the repaired load.

## Creative mode, nobody online, slow friend joins: 2026-11-24

- Fixes: tunnel Dialtone child activated on its event loop after registration (DESIGN §3 row 16, owner-approved); co-op host turns off forced game mode (DESIGN §4.4 "Game modes"); menu re-checks worlds without a player every 5 s.
- `./gradlew build`: BUILD SUCCESSFUL.
- Self-tests on this machine: `ui -Only pvp` PASS (60 s, includes new step "the host forces no game mode on members"); `ui -Online -Only pvp.online` PASS (85 s, real e4mc relay); `m1 -Online -Only drop-in.foreign-lan` PASS (68 s); `m1` 12/12 PASS.
- Unverified: a real friend join across networks with the new jar on both sides; the race itself was not reproduced on demand (cause inferred from the owner's log and Netty 4.2.16 code).

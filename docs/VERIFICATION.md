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

## M1 — gate run 2026-11-17 (NOT PASSED: one check open)

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

Not verified in this run:

- The full suite was not re-run after the two fixes; only the failed checks were (as the build prompt says).
- GUI scale 2 and 4, Mod Menu, any window size other than the default; the look of every screen (no screenshots were taken).
- The `[Co-op]` badge text in the Singleplayer list (only that the list opens with the mixin applied).
- New (Create World from the Co-op Worlds screen), "Make co-op" in the pause menu, Invite/Join/Players/Import screens as clicked by a person, `titleButton` and `pkarrRelays` in `config/dropin/coop.json`, skins of real accounts.
- A world locked by another game process, an older or newer world version (unit tests only).
- First join without a local copy (connects as a plain client): not run.
- Real accounts, two machines, NAT. The online smoke ran host and joiner on one PC.
- The five-minute heartbeat and the `idle` record on quit were not asserted directly (host-swap relies on B being able to host after A quit).

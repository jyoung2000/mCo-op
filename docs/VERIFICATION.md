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

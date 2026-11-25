# DropIn Co-op

Fork of e4mc (MIT, © 2024 Skye) that turns a singleplayer world into a drop-in co-op world shared between friends. Target: Minecraft 26.3, Fabric + NeoForge, one jar.

The design is in `docs/DESIGN.md`. It is the source of truth. If the code and the design disagree, stop and say so; do not silently pick one. Milestone work lists and gates are in `docs/MILESTONES.md`; proof of each passed gate goes in `docs/VERIFICATION.md`.

## Rules for this repo

- **Read the real 26.3 sources before coding against Minecraft.** 26.x is unobfuscated. Every Minecraft class or method name in `docs/DESIGN.md` §8 is unconfirmed until you have looked at it. Quote the signature you found in `session-notes.md`.
- **`core/` has no Minecraft, Fabric or NeoForge imports.** Everything in it has unit tests. Run only the tests for the package you changed until the final check.
- **`tunnel/` is upstream code.** Change it only where the design names a hook (DESIGN §3 rows 6–9, 14, 15 and 16). Keep diffs there small so upstream fixes can still be merged.
- **Do not fork or rebuild `iroh-java`.** Its native library is downloaded from upstream's host at runtime (DESIGN §3 row 5).
- **Bulk world data never goes over a relayed path unless `bulkSyncOverRelay` is on** (DESIGN §2 limit 3).
- **Never read or write `.mca` files of a world that is open on this machine.** Go through the game's storage objects (DESIGN §4.5).
- **Self-test code and every `dropin.dev.*` / `dropin.selftest` switch work only in a development environment** (DESIGN §4.12). A production jar must ignore them.
- **Never weaken, skip or delete a test to get a pass.** If a test is wrong, say why and ask.
- **"Works" means a gate passed on this machine in this session.** Do not claim behaviour you did not run; list what is unverified.
- **Commit locally. Do not push, publish or open pull requests unless asked.** Never open one upstream: upstream does not accept AI-assisted contributions.
- Keep `LICENSE` intact, including Skye's copyright line.

## Working style

- Grep/Glob first, then read only the matching range. Read each file once.
- Edit over Write. Never dump a whole file through the shell.
- Cap shell output: `2>&1 | Select-Object -Last 40`, or log to a file and search the log. Never print full Gradle output.
- One verification pass at the end of a session, with the smallest check that proves the change.
- One milestone per session. Read `session-notes.md` first if it exists; update it before stopping (decisions, state, next steps, files touched).
- If a step needs a decision the design does not cover, ask. Do not invent protocol or file-format details.

## Commands

```
./gradlew build                 # full build + unit tests, one jar: build/libs/MCo-op-<version>.jar
./gradlew :core:test            # core unit tests, no game needed (add --tests "dropin.core.<pkg>.*" for one package)
./gradlew :mc:test              # unit tests of the self-test step runner
./gradlew :fabric:runClient     # dev client (Fabric), game folder fabric/runs/client
./gradlew :neoforge:runClient   # dev client (NeoForge), game folder neoforge/runs/client
powershell -ExecutionPolicy Bypass -File scripts/selftest.ps1 -Suite m0          # multi-client self-test for one milestone
powershell -ExecutionPolicy Bypass -File scripts/selftest.ps1 -Suite all         # every suite up to the current milestone
powershell -ExecutionPolicy Bypass -File scripts/selftest.ps1 -Suite m0 -Online  # opt-in online smoke (real e4mc relay)
powershell -ExecutionPolicy Bypass -File scripts/jarcheck.ps1                    # gate jar check on build/libs (after a build)
```

Gradle must run on JDK 25 (`JAVA_HOME`); `scripts/selftest.ps1` finds one by itself. Self-test logs: `build/selftest-logs/`.
Suites: `m0`, `m1`, `m2`, `m2b`, `m3` (`handoff-3` starts three clients: slots a, b, c), `m3b` (save points), `m4` (crash failover: the script kills game processes; stale-copy warning; `second-host`: a second host appears, the first closes its world), `m5` (divergence and merge: `merge-clean`, `merge-conflict`, `merge-too-old`), `ui` (admins, who is online, pvp, realtime, `join-code`: `/coop code` and `/coop code share`, `motion`: minecart and mob movement on three screens), `integrity` (every copy equals the host's, three clients; `sync-extras`: mod folders of the world root, direct rate cap, fastest player takes over); `perf` is a measurement, not part of `all`. `-Suite integrity -Online` and `-Suite m2 -Online -Only first-download-root-match.pack` run with 15 real Fabric mods downloaded from Modrinth into `build/testmods/pack/` (`DROPIN_PACK_SKIP=name,name` leaves mods out). The m2b scenarios use test mods built by `:<loader>:testmodJars` (`build/testmods/`); `-Suite m2b -Online`
downloads one small real mod from Modrinth into `build/testmods/online/`. Add `-Only <scenario id>` to run a single scenario.
Modules: `core` (pure Java), `tunnel` (upstream e4mc), `mc` (Minecraft-facing, plus the dev-only `selftest` source set),
`fabric` and `neoforge` (entry points and run tasks). The loader modules compile the sources of the other three;
the root `modJar` task merges them into the one jar.

Keep this section current.

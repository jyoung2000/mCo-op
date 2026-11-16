# DropIn Co-op — design spec

Fork of `vgskye/e4mc-minecraft-architectury` at v6.2.3 (commit `93ce8a8`, 2026-10-01, MIT).
"DropIn" is a working name; mod id `dropin`. Rename freely.

Put this file at `docs/DESIGN.md` in the fork. `docs/MILESTONES.md` and `BUILD_PROMPT.md` refer to it by section number.

---

## 1. What the mod does

One mod jar per player, nothing else to run.

- A **Co-op Worlds** button on the title screen lists your co-op worlds and who is playing them.
- Any existing world can be **imported** as a co-op world, or a new one created. That produces one invite code.
- Every member keeps a **full local copy** of the world.
- A member clicks the world in the normal world list. If a friend is already playing it, they **join that friend**. If nobody is, the world **opens from their own copy and hosts itself** through e4mc's tunnel, so friends can drop in.
- While people are connected, the host **streams every world change** to each member's local copy, hash-checked.
- When the host quits, hosting **hands off** to another connected member and everyone reconnects.
- Each player's inventory, position, health, ender chest, advancements and stats follow their account, whoever is hosting.

### What it is not

This is not a blockchain and has no consensus. Minecraft can only be simulated by one machine at a time, so there is exactly one live host per session. The "ledger" is a hash chain of checkpoints that lets two copies tell which is newer, verify transfers, and find where they split.

---

## 2. Limits you should know before building

1. **Stale solo start.** If Alex and Sam play on Tuesday and Jo (who was not there) opens the world alone on Wednesday, nobody is online to give Jo Tuesday's changes. Jo's session forks the world. The mod detects this when Jo next meets the others and merges chunk by chunk (§4.10), but if both sides changed the same chunk, one side's version of that chunk loses (a backup is kept). No serverless design avoids this. The only real cure is a copy that is always online (§7, optional seed on Unraid).
2. **No fixed server address on the public e4mc relay.** The relay picks a random two-word domain per session (§3, row 3). The mod makes this irrelevant: members never type an address, the world list entry finds the current host by itself. A literal fixed address needs your own relay (§7).
3. **Bulk world data should not ride e4mc's free relay.** World sync can be gigabytes. By default it only flows over a direct path (LAN or a direct Dialtone/iroh connection). If two players can't get a direct path, gameplay still works through the relay but their copies won't sync until you opt in (throttled) or run your own relay.
4. **An invite is full trust.** Anyone holding it can join, host, and therefore change anything. Share it like a password.
5. **Nothing here has been compiled.** The spec was written from reading the source. The sandbox it was written in cannot reach the Fabric, NeoForge or Mojang Maven hosts. M0 is where the build gets proven.

---

## 3. Evidence from the code

Line numbers are from commit `93ce8a8`.

| # | Finding | Where | Consequence |
|---|---------|-------|-------------|
| 1 | License is MIT, © 2024 Skye | `LICENSE` | Fork allowed. Keep the notice, add yours. |
| 2 | Compiled against **1.20.2** with Architectury Loom; one codebase spans 1.18→26.3 through reflection | `gradle.properties`, `common/.../Mirror.java` | New co-op code needs real 26.3 APIs, so the build must be retargeted (§5). |
| 3 | Client asks the relay for a domain with no parameters; relay picks random words | `QuiclimeSession.java:58-60`; relay `e4mc-quiclime/src/routing.rs:81-98` | No stable address from the public relay. Need a rendezvous (§4.3). |
| 4 | iroh endpoint is built with `Endpoint::builder(presets::Minimal).relay_mode(..).bind()`: no secret key, no discovery | `iroh-java/rust/src/lib.rs:203-207` | A stable iroh node id per world would need a fork of the native binding. |
| 5 | The iroh native library is downloaded at runtime from `natives.e4mc.link` | `iroh-java/.../Native.java:47` | Forking the binding also means hosting natives for every OS. **Do not fork it.** Use upstream `iroh-java` unchanged. |
| 6 | LAN publish hook captures the vanilla `childHandler` and `EventLoopGroup`, then `new QuiclimeSession(childHandler, group)` | `mixin/ServerConnectionListenerMixin.java:25-45` | One place to add a side-channel that works over LAN TCP, the relay and Dialtone at once (§4.6). |
| 7 | Client connect is redirected to `DialtoneChannel` when the resolved address is a `SmugglersInetSocketAddress` carrying a ticket | `mixin/ConnectionMixin.java:48-95` | The sync client can open its own connection the same way. |
| 8 | "Domain assigned" handler | `QuiclimeSession.java:299-315` | Hook point: publish the rendezvous record here. |
| 9 | Per-connection path info is already exposed | `ConnectionMixin.java:41` (`e4mc$connInfo`), `iroh-java/rust/src/lib.rs:856` (`connection.paths()`) | Can tell direct from relayed, to gate bulk sync. |
| 10 | On Windows the mod warns in chat if the jar was downloaded from anywhere but Modrinth, CurseForge or upstream's Maven | `PoisonPill.java`, `QuiclimeSession.java:326` | A fork downloaded from GitHub trips this. Remove it. |
| 11 | Defaults point at upstream's infrastructure | `Config.java` (`broker.e4mc.link`, `natives.e4mc.link/relaymap.json`) | The fork uses someone else's servers. See limit 3. |
| 12 | The 26.x Fabric jar already depends on Fabric API | `fabric/src/main/resources-modern/fabric.mod.json` | On Fabric, players need Fabric API as well (already true upstream). NeoForge needs nothing extra. |
| 13 | Build lists 26.3 as a supported game version | `fabric/build.gradle:178-180`, `neoforge/build.gradle:115-117` | Upstream tunnel code is expected to work on 26.3 as is. |

From outside the repo:

- Since 26.1, `level.dat` no longer embeds the host's player. It holds a `singleplayer_uuid` that points at `players/data/<uuid>.dat`, and all default dimensions live under `dimensions/<namespace>/<name>/`. Source: https://minecraft.wiki/w/Java_Edition_26.1
- Pkarr relays accept `PUT`/`GET /<z-base32 public key>` with a body of 64-byte signature, 8-byte timestamp, then a DNS packet of at most 1000 bytes. Source: https://pubky.github.io/pkarr/design/relays.html
- Verified locally on JDK 21: `KeyPairGenerator.getInstance("Ed25519")` initialised with a `SecureRandom` that returns a fixed 32-byte seed produces the RFC 8032 test-vector public key and signature. So world keys can be derived from the invite seed with no extra library.

---

## 4. Architecture

### 4.1 Identity and invite

- `seed`: 32 random bytes, generated when a world is shared.
- Derived with HKDF-SHA256 (`javax.crypto.KDF` on Java 25, or 10 lines of HMAC):
  - `sig`: Ed25519 key pair, info `dropin/sig`. `worldId` = z-base32 of the public key (52 chars). This is the pkarr address.
  - `auth`: 32 bytes, info `dropin/auth`. HMAC key for the sync handshake and ledger entries.
  - `enc`: 32 bytes, info `dropin/enc`. AEAD key (ChaCha20-Poly1305 from the JDK) for the rendezvous payload and sync frames.
- Invite code: `dropin1-` + base32(seed), grouped in fives. It is the only thing a friend needs.
- Re-keying (kicking someone) = new seed, new invite for everyone who stays.

### 4.2 Files on disk

Secrets stay out of the world folder so zipping a world does not leak the invite.

```
.minecraft/config/dropin/worlds/<worldId>.json     seed, display name, known members
saves/<folder>/dropin/world.json                   worldId, format version
saves/<folder>/dropin/state.json                   head, syncedThrough, complete
saves/<folder>/dropin/ledger.jsonl                 one checkpoint per line
saves/<folder>/dropin/manifest/...                 current hashes (§4.7)
saves/<folder>/dropin/deltas/<seq>.bin             keys changed at that checkpoint
saves/<folder>/dropin/branches/<head>/...          preserved divergent records (§4.10)
saves/<folder>/dropin/backups/...                  pre-merge copies of overwritten records
```

`dropin/` is never replicated as files. The ledger travels through the protocol.

### 4.3 Rendezvous: "who is hosting this world right now?"

A pkarr record at `worldId`, signed with `sig`. One TXT record named `_dropin` whose value is base64url(nonce ‖ AEAD(`enc`, payload)).

Payload (compact JSON, about 150 bytes):

```
{ "v":1, "st":"starting|host|handoff|idle", "addr":"<e4mc domain>", "lan":"<ip:port, optional>", "host":"<uuid>",
  "name":"<player name>", "sess":"<8 random bytes hex>", "head":"<16-byte ledger head hex>",
  "seq":1234, "ts":<epoch seconds>, "next":"<uuid, handoff only>" }
```

Rules:

- **Liveness is a ping, not the record.** The record is a pointer. A host counts as live only if a Minecraft status ping to `addr` answers within 5 s (resolved through the normal `ServerNameResolver`, so e4mc's mixins apply).
- **Claim before hosting:** GET → if a live host exists, join it. Otherwise PUT `st:"starting"` with a fresh `sess` (send `If-Match` with the timestamp of the record just read, when there was one). Wait 2 s, GET again. If the record now carries someone else's `sess` and they ping, join them. Otherwise open the world, and once the e4mc domain arrives PUT `st:"host"` with `addr`.
- **Heartbeat:** re-PUT every 5 minutes with the current `head`/`seq`. This also keeps the record alive on the DHT. Back off on HTTP 429.
- **Clean exit:** PUT `st:"idle"` with the final `head`.
- **Relays** are a config list. Publish to all, read until one answers. Confirm which public relays are live when implementing (see the pkarr repo README); do not hard-code a single one.
- **Offline / relays down:** host solo from the local copy, mark the session `unannounced`, keep retrying the announce every 60 s. If a live host with a different lineage shows up, tell the player.
- **`lan` hint.** The host also publishes its LAN address. Clients try `lan` first (1 s timeout), then `addr`. Two players in the same house connect directly, and the self-tests use it to stay off the relay.
- Put this behind an interface (`Rendezvous`). Implementations: `PkarrRendezvous` (default), `FileRendezvous` (a shared folder, used by the self-tests in §4.12), and later a self-hosted HTTP one.

Verify the wire format against the pkarr spec and at least one known-good vector before trusting it. Signature input is the BEP44 form `3:seqi<timestamp>e1:v<len>:<dns packet>`.

### 4.4 Menu, import and launch flow

**Title screen.** A "Co-op Worlds" button beside Singleplayer and Multiplayer, added with a mixin on the title screen's init (no loader screen APIs, so common code stays loader-free). It must not overlap vanilla buttons at GUI scales 1–4.

**Co-op Worlds screen.** Lists every world that has `dropin/world.json`, each with a live status line: "Looking…", "Sam is playing", "Nobody online, you'll host", "Your copy is behind", "Downloading 63%".

| Button | Action |
|--------|--------|
| Play | Launch flow below. |
| New | Vanilla Create World screen; the new world is registered as co-op before its first load. |
| Import | Import flow below. |
| Join with invite | Paste an invite code. |
| Invite | Show and copy this world's invite code. |
| Remove | Stop treating the world as co-op on this PC: after a confirm, delete `dropin/` and the local key. The world stays as an ordinary singleplayer world. |

Co-op worlds also stay in the normal Singleplayer list with a badge, and opening one from there goes through the same launch flow. The pause menu of a non-co-op singleplayer world gets "Make co-op", which is Import in place.

**Import.** Turns an existing world into a co-op world.

- **From saves:** a picker of worlds in `saves/` that are not co-op yet.
- **From a folder or .zip:** drag-and-drop onto the screen (the way the resource-pack screen accepts files), plus a Browse button if a native file dialog is available. The world is copied or extracted into `saves/` first. Extraction rejects any entry that resolves outside the target folder, and requires `level.dat` at the root or inside a single top-level folder.
- **Checks, in order:**
  1. Not open right now (session lock).
  2. Not already co-op. If it has `dropin/world.json` but this PC has no key for it, ask for the invite instead.
  3. World version. Older than the running game → "Open it once in singleplayer to upgrade it, then import." Newer → refuse. Never convert world formats ourselves.
- **Back up first** checkbox, on by default, using the game's own world backup.
- **Then:** generate the seed, write the key file and `dropin/world.json`, show the invite. The first manifest is built in the background the first time the world is hosted (M2 onward).
- Import only adds the `dropin/` folder. Remove undoes it.

**Launch flow (Play).** Hook the single entry point the world list uses to open a world (expected: `WorldOpenFlows.openWorld(levelId, onCancel)`; confirm in 26.3 sources). If `saves/<levelId>/dropin/world.json` exists, cancel and run `CoopLauncher`:

1. "Looking for your friends…" (at most ~6 s): rendezvous lookup + ping.
2. **Live host found** → connect to `addr` through the normal connect path. After joining, start `SyncClient` in the background.
3. **No live host, local copy complete** → if the record's `head` is not in the local ledger, warn: "Your copy is behind the last session (Sam, 3 hours ago). Nobody is online to update you. Play anyway (changes are merged later) / Cancel." Then claim, call the original `openWorld`, and when the integrated server is up call `publishServer(...)` so e4mc's hook starts the tunnel.
4. **No live host, local copy incomplete** → "Nobody is online and your copy is 63% downloaded."

**Join for the first time.** From *Join with invite*. A first join needs a live host (there is nothing to open yet). The player connects and plays immediately; the world downloads in the background into `saves/<name>/`, `level.dat` first so the world appears in the list.

### 4.5 Host session

Starts when a co-op world's integrated server is up.

- **Dirty tracking.** Mixin at the chunk-storage write choke point (expected: `IOWorker.store(ChunkPos, CompoundTag)`; region, entities and POI each go through one with their own `RegionStorageInfo`; confirm in 26.3 sources). Record `(dimension, kind, chunkX, chunkZ)` in a concurrent set. Never read `.mca` files directly while the server runs.
- **Checkpoint** every 60 s (config) and on pause/quit: trigger a normal save, drain the dirty set, read each dirty chunk back through the same storage object (`loadAsync`), serialise the NBT uncompressed, hash it. Walk the non-chunk files and re-hash those whose size or mtime changed. Update the manifest, write `deltas/<seq>.bin`, append a ledger entry, broadcast.

### 4.6 Sync side-channel

A second connection through the same tunnel, separate from the game connection, so bulk transfer never sits in front of game packets.

- **Server side.** In `ServerConnectionListenerMixin`, wrap the captured `childHandler` before it is handed to `QuiclimeSession` and to the vanilla bootstrap. The wrapper installs a `ProtocolSniffer` first in each new channel's pipeline. The sniffer holds back `channelActive`, reads the first bytes, and:
  - if they start with the 8-byte magic `44 52 4F 50 49 4E 00 01` (`DROPIN\0\1`) → installs the sync pipeline (length-prefixed frames, AEAD, `SyncServerHandler`);
  - otherwise → adds the vanilla initializer, fires `channelActive`, replays the buffered bytes, removes itself.
  A vanilla handshake cannot collide: its second byte is packet id `0x00`, the magic's is `0x52`. Decide as soon as one byte differs.
- **Fallback if pipeline surgery is flaky or clashes with another mod:** start a second `QuiclimeSession(syncInitializer, group)` (the constructor takes any `ChannelHandler`). Simpler, but it doubles the load on the relay and allocates a second domain, so it is the fallback, not the default.
- **Client side.** `SyncClient.connect(addr)`: resolve like the game does. If the result is a `SmugglersInetSocketAddress`, build a Netty `Bootstrap` with `DialtoneChannel`, `DialtoneAmbientSession.INSTANCE.group` and `new DialtoneAddress(ticket)` (mirror `ConnectionMixin.java:48-95`). Otherwise plain NIO to host:port. Send the magic, then handshake.
- **Handshake.** C→S `HELLO{proto, worldId, playerUuid, nonceC}`; S→C `CHALLENGE{nonceS}`; C→S `HMAC(auth, nonceC‖nonceS‖"c")`; S→C `HMAC(auth, nonceC‖nonceS‖"s")`. Session key = HKDF(`enc`, nonceC‖nonceS). Every later frame is AEAD-sealed with a counter nonce.
- **Messages.** `HEAD`, `LEDGER_REQ/RESP`, `DIFF_REQ/RESP` (root → region hashes → chunk hashes), `GET(keys[])`, `RECORD(key, bytes)`, `CHECKPOINT(entry, changedKeys)`, `PEERS(list with syncedSeq)`, `HANDOFF`, `BRANCH_PUSH` (§4.10), `BYE`.
- **Flow control.** Receiver-driven `GET` windows (64 records outstanding). Host-side rate cap (config). Pause bulk when host TPS drops below 18.
- **Path policy.** `bulkSyncOverRelay=false` by default. On a non-direct path only `HEAD`, `CHECKPOINT` metadata and `PEERS` flow, and the UI says "Waiting for a direct connection to sync world data." Opt-in allows it with a cap (default 128 KB/s).

### 4.7 What is replicated, and how it is verified

- **Chunk record.** Key `(dimension id, kind ∈ region|entities|poi, cx, cz)`. Value = uncompressed NBT bytes exactly as the host serialised them. Hash = first 16 bytes of SHA-256.
- **File record.** Key = path relative to the world root. Covers `level.dat`, `data/**`, `players/**`, `dimensions/*/*/data/**`, `datapacks/**`, `resourcepacks/**`, `icon.png`. Excluded: `dropin/**`, `session.lock`, `*.tmp`, `level.dat_old`, and the three region-type folders (covered by chunk records).
- **Applying on a member.** The member's copy is not open (they are a remote client), so chunk records are written with the game's own region-file classes opened standalone, and file records with temp-file + atomic move. `state.json.syncedThrough` moves only after a whole checkpoint is applied. An interrupted transfer heals itself on the next connect because the diff is hash-based.
- **Manifest.** Two-level Merkle: region hash = H(its chunk hashes in index order); root = H(all region hashes in key order ‖ file hashes in path order). Diffing descends root → region → chunk.
- **Ledger entry** (one per checkpoint): `{seq, parent, host, name, sess, ts, root, changed, parents2?, mac}`. `mac` = HMAC(`auth`, canonical bytes). Entry hash = SHA-256 of the canonical bytes. Head = hash of the last entry.
- **Comparing two copies.** Their head is in my ledger → I am equal or ahead. My head is in theirs → I fast-forward. Neither → diverged; the common ancestor is the last shared hash.
- **Verification.** Every record is checked against the hash announced in its `CHECKPOINT`; every checkpoint is MAC'd and chained to its parent. A truncated or tampered transfer cannot be committed.

### 4.8 Player data

26.1+ already stores every player, host included, in `players/data/<uuid>.dat` (plus `players/advancements`, `players/stats`). Those are ordinary file records.

One fix is required: before a co-op world opens locally, make sure `level.dat`'s `singleplayer_uuid` is the **local** player's UUID, otherwise the new host may load the previous host's player. Decide between rewriting `level.dat` before open and a mixin at the read site after reading the 26.3 sources (`PlayerList`, `PrimaryLevelData`, `LevelStorageSource`).

UUIDs must be stable, so this assumes real (online-mode) accounts.

### 4.9 Handoff and failover

**Graceful (host clicks Save and Quit with others connected):**

1. Final checkpoint, pushed to every sync peer; wait for acks (30 s cap).
2. Successor = a fully synced peer, longest connected first. If none: "Nobody else has a full copy yet (Sam 62%). Quit anyway?"
3. Send `HANDOFF{successor, finalEntry}`; PUT rendezvous `st:"handoff", next:<uuid>`; stop.
4. Successor disconnects, opens its local copy (now identical), publishes, PUTs `st:"host"` with the new `addr`.
5. Everyone else shows "Host is changing…", polls the rendezvous until the new `addr` pings, reconnects. Expect 10–30 s.

**Crash (host vanishes):** the host broadcasts `PEERS` (with each peer's `syncedSeq`) every checkpoint. On unexpected disconnect, each client confirms the old `addr` no longer pings, then the peer with the highest `syncedSeq` (ties: lowest UUID) takes over from its last applied checkpoint. Others poll the rendezvous; if nothing appears in 45 s, the next peer in that order takes over. Up to one checkpoint interval of play is lost.

### 4.10 Divergence and merge

Happens when a member's head and the live host's head have a common ancestor but neither contains the other.

- **Main** = the lineage that has a live host when the two meet. Main wins conflicts. This keeps the rule deterministic.
- **Preserve.** The diverged member computes the keys it changed since the ancestor (union of its `deltas/` since then), copies those records into `branches/<its head>/`, then joins as a normal client and fast-forwards to main. It also sends the bundle to the host (`BRANCH_PUSH`), and the host passes it on as a pending branch, so it survives even if that member never hosts again.
- **Cold merge.** Pending branches are merged by whoever starts the world next, **before** the integrated server boots (no live-chunk surgery):
  - key changed on the branch only → apply the branch record;
  - key changed on both → keep main, save the branch record under `backups/conflicts/`, count it;
  - the three records of one chunk (region, entities, POI) always move together;
  - a player's own files come from the branch only if main did not change them since the ancestor;
  - world-level files (`level.dat`, `data/**`) → main.
- Append a merge entry with two parents and report in chat: "Merged Jo's offline session: 412 chunks applied, 3 kept from the main world (backup saved)."
- **Too old to merge.** If `deltas/` no longer reach the ancestor (kept 30 days by default), the branch is saved as a separate world, "<name> (Jo's branch)", for manual recovery.

Known rough edges: items moved between a conflicting chunk and a clean one can duplicate or vanish; map ids allocated on both sides can collide.

### 4.11 Guests

Players without the mod can still join through the session's e4mc domain, exactly as with upstream e4mc. They play as guests and hold no copy. Config `allowGuests` (default on, matching upstream behaviour).

### 4.12 Self-test harness (development only)

Purpose: let an agent prove behaviour on one PC, with no accounts and no public servers.

- **Guard.** Active only when the loader reports a development environment **and** `-Ddropin.selftest=<scenario>:<role>` is set. A production jar ignores every `dropin.selftest` and `dropin.dev.*` property.
- **`-Ddropin.dev.offlineLan=true`** lets the integrated server accept unauthenticated dev accounts, so two dev clients can meet on LAN.
- **`-Ddropin.rendezvous=file:<dir>`** selects `FileRendezvous`: same interface, a shared temp folder instead of pkarr.
- **Tunnel off by default.** Self-tests run with the e4mc tunnel disabled and connect through the record's `lan` field (`127.0.0.1:<port>`).
- **Orchestrator.** `scripts/selftest.ps1 -Suite <name>`: prepares game folders `run/a`, `run/b`, `run/c` (options: `pauseOnLostFocus:false`, small window, render distance 2, sound off), launches dev clients through the loader's Gradle run tasks with distinct `--username` values, enforces a per-scenario timeout, kills stragglers, collects `run/*/dropin-selftest/result.json`, prints one line per scenario, exits non-zero on any failure. Full logs go to files, never to the console. Phased scenarios may copy a closed world folder from one game folder to another between phases.
- **In-client driver.** A tick-driven step runner. World actions use commands (`/setblock`, `/give`, `/tp`; tests publish with cheats on). Assertions read game state, not screenshots.
- **Online smoke (opt-in, `-Online`).** The `drop-in` scenario through the real e4mc relay and real pkarr relays. Run once at the end of M0, M1 and M2. Not part of the default suites, to stay off public infrastructure.
- **What it cannot prove:** stable UUIDs with real accounts, NAT traversal between two homes, the production jar inside real launchers. Those stay on the manual checklists.

---

## 5. Build retarget and fork hygiene (M0)

**Today:** compiled against 1.20.2 with Architectury Loom 1.13; `Mirror` reflection covers version differences; the Fabric 26.x jar is made by remapping intermediary→named (`fabric/build.gradle:77-83`); there is a `forge/` module.

**Target:**

- Minecraft **26.3 only**, Java 25, non-remapping toolchain (26.x ships unobfuscated). Either Architectury Loom's no-remap variant or the `26.3` branch of `jaredlll08/MultiLoader-Template` (Fabric Loom + ModDevGradle). Pick whichever needs fewer changes after reading both.
- Delete `forge/`.
- **One output jar** carrying both `fabric.mod.json` and `META-INF/neoforge.mods.toml`, both entry points, one mixin config. Shade the libraries (`netty-incubator-codec-classes-quic`, `iroh-java`, `dev.dirs`, `kaleido-config`) rather than jar-in-jar, so one layout works on both loaders. Relocate `dev.dirs` and `kaleido-config`. Do not relocate `netty-incubator-codec-classes-quic` (`io.netty.incubator.codec.quic`) or `iroh-java` (`link.e4mc.iroh`): their prebuilt native libraries bind to those class names, so they stay at their original packages (owner's ruling, 2026-11-16). The only mod that ships the same packages is e4mc, which is declared incompatible. Replace the `Agnos` build-time stub with runtime loader detection.
- Fallback if a merged jar misbehaves on either loader: two jars, same code.
- Keep `Mirror` at first. Remove its legacy branches only after M0 passes.
- Loader versions seen in a public 26.3 multiloader project (re-check for newer): Fabric Loader 0.20.1, Fabric API 0.166.0+26.3, NeoForge 26.3.0.102.

**Hygiene:**

- Rename mod id, packages, config file and lang keys from `e4mc` to `dropin`. Declare the mod incompatible with `e4mc` (both would mixin the same targets and open two relay sessions).
- Keep `LICENSE` with Skye's copyright line; add your own line.
- Remove `PoisonPill` and its chat warning.
- Remove the Modrinth, CurseForge and Maven publishing blocks and ids.
- Replace upstream's `CLAUDE.md` and `AGENTS.md` with your own (do this by hand before the first Claude Code session).
- Upstream states it does not accept AI-assisted contributions. Do not open pull requests upstream from this fork.
- The fork talks to upstream's broker, relays and natives host. Keep limit 3 in force. If the mod gets real use, run your own relay (`vgskye/e4mc-quiclime`) and point the config at it.

---

## 6. Code layout

```
core/          pure Java, no Minecraft imports, fully unit-tested
  crypto/      HKDF, Ed25519 from seed, AEAD, HMAC
  invite/      encode / decode
  pkarr/       DNS TXT packet codec, signed-packet codec, HTTP client, Rendezvous interface
  ledger/      entries, chain compare, common ancestor
  manifest/    hashes, two-level Merkle, diff
  merge/       three-way planner (inputs: two delta sets + ancestor; output: actions)
  sync/        frame codec, handshake, client/server state machines over an abstract transport
mc/            Minecraft-facing code
  menu/        title-screen button, Co-op Worlds screen, import, join-by-invite
  launch/      CoopLauncher, openWorld hook
  host/        HostSession, dirty tracking mixin, checkpointing
  member/      SyncClient, record applier (region files), status HUD
  net/         ProtocolSniffer, Netty transports (NIO + Dialtone)
  handoff/     graceful handoff, failover election
  selftest/    dev-only scripted driver (§4.12)
tunnel/        upstream e4mc code, renamed, otherwise untouched
scripts/       selftest.ps1 orchestrator
```

Everything in `core/` must be testable with `./gradlew test` and no game. That is what lets an agent verify most of the work without launching Minecraft.

---

## 7. Milestones

Each one is a separate session and ends with a working jar. Work lists, automated gates and manual checklists are in `docs/MILESTONES.md`.

| # | Scope | Proves |
|---|-------|--------|
| **M0** | Retarget to 26.3, single jar, rename, hygiene, self-test harness skeleton. No co-op features. | Jar loads on Fabric 26.3 and NeoForge 26.3; Open to LAN prints a domain; a second client joins through it. |
| **M1** | `core/crypto`, `invite`, `pkarr`, `Rendezvous`; Co-op Worlds menu; import; join-by-invite; launch flow; `singleplayer_uuid` fix. No sync yet (tests copy the world folder between phases). | A plays → B clicks the world and lands in A's session. A quits → B clicks and hosts. Each keeps their own inventory and position. |
| **M2** | Side-channel, manifest, ledger, full download, live checkpoints, resume, path policy. | New member plays while the world downloads; after A quits, B's copy opens alone and matches (same root hash). Kill the transfer mid-way and it resumes. |
| **M3** | Graceful handoff. | A quits with B and C connected; B becomes host; C reconnects without touching anything. |
| **M4** | Crash failover, stale-copy warning, status HUD (host name, sync %, path direct/relayed). | Kill A's game process; B takes over from the last checkpoint. |
| **M5** | Branch preservation, cold merge, backups. | Two copies edited separately in different chunks merge with nothing lost; same-chunk edits keep main and back up the other. |
| **M6** (optional) | Headless seed peer as a Docker container for Unraid (always-on copy, never simulates); self-hosted relay; fixed address. | Removes limit 1 in practice. |

---

## 8. Things to confirm in the 26.3 sources before coding against them

Names below are from memory of earlier versions; 26.x is unobfuscated, so read the real sources (`genSources` or the IDE) instead of trusting them.

1. The method the world list calls to open a world (`WorldOpenFlows.openWorld`?), and its signature.
2. `IntegratedServer.publishServer(...)` signature, and what it returns when the port is taken.
3. Where `singleplayer_uuid` is read and written.
4. The chunk-storage write choke point (`IOWorker.store`?), whether region, entities and POI all pass through it, and how to get `(dimension, kind)` from it.
5. Whether the region-file classes can be instantiated without a running server, and their constructors.
6. Folder names for entities and POI under `dimensions/<ns>/<name>/`.
7. That the vanilla `childHandler` tolerates being added to the pipeline after the sniffer and receiving a re-fired `channelActive`.
8. How to read "direct vs relayed" from an `iroh-java` `Connection` in Java (what `e4mc$connInfo` uses).
9. A Minecraft status ping that can be issued from code against an e4mc domain.
10. The title screen's init method and how its buttons are laid out.
11. How the game lists worlds and reads a world's version without opening it, and its built-in world backup call.
12. The screen callback for files dropped onto the window (as used by the resource-pack screen), and whether a native file dialog (LWJGL tinyfd) is on the classpath.
13. Where the integrated server turns authentication on, for the dev-only offline-LAN switch.
14. How to create a world from code with a fixed seed and preset, for scripted tests.
15. How the Create World screen hands off to world loading, so a new world can be registered as co-op before its first load.

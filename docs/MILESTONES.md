# DropIn Co-op — milestones and gates

Put this file at `docs/MILESTONES.md`. One milestone per session. A milestone is DONE only when every item under **Gate** passes on this machine and the evidence is in `docs/VERIFICATION.md`.

Common gate for every milestone:

1. `./gradlew build` succeeds.
2. Core unit tests pass.
3. `scripts/selftest.ps1` passes for this milestone's suite **and every earlier suite**.
4. Jar check: exactly one mod jar; it contains `fabric.mod.json`, `META-INF/neoforge.mods.toml`, one mixin config; bundled libraries are relocated, except the two JNI-bound ones (`io.netty.incubator.codec.quic`, `link.e4mc.iroh`), which must be present at their original names (DESIGN §5); no self-test class is reachable outside a development environment.

Self-test scenarios run on Fabric dev clients unless marked *cross-loader*, which also runs Fabric host + NeoForge joiner and the reverse.

---

## M0 — retarget to 26.3, one jar, rename, harness

**Read:** DESIGN §3, §5, §4.12. **Confirm in sources:** §8 items 13, 14.

**Work, committing after each:**

1. Build as is (`./gradlew build`, output capped). Record the result. If it fails for a reason unrelated to our changes, stop and report.
2. Retarget to Minecraft 26.3 only, Java 25, non-remapping toolchain. Compare the two routes in DESIGN §5 by reading the current build files and the 26.3 branch of `jaredlll08/MultiLoader-Template`; pick the smaller diff and note why.
3. Delete the `forge` module.
4. Produce one jar with both loaders' metadata. Shade bundled libraries and relocate them, except the two JNI-bound ones (DESIGN §5). Replace the `Agnos` build-time stub with runtime loader detection. If a merged jar cannot work, stop and report what blocks it before falling back to two jars.
5. Rename mod id, packages, config file and lang keys to `dropin`; move upstream code under `tunnel`. Declare incompatibility with `e4mc`.
6. Remove `PoisonPill` and its chat warning, and the Modrinth, CurseForge and Maven publishing blocks.
7. Add the owner's copyright line to `LICENSE` under Skye's. Keep Skye's.
8. Self-test harness skeleton (DESIGN §4.12): dev-environment guard, `dropin.dev.offlineLan`, the step runner, `scripts/selftest.ps1`, result files.

**Out of scope:** any co-op feature; cleanup of `Mirror` beyond what the build needs.

**Gate suite `m0`:**

- `lan-join` *(cross-loader)*: A creates a world from code, publishes to LAN; B joins `127.0.0.1:<port>`; each sees the other; a block B places is read back by A.
- `status-ping`: B gets a status response from A's LAN port.
- `prod-guard`: with the dev-environment flag forced off, `dropin.selftest` and `dropin.dev.offlineLan` do nothing (unit-level).

**Online smoke (once):** `tunnel-join`: A publishes through the real e4mc relay, B joins by the assigned domain.

**Manual checklist:**

- [x] Jar in a real Fabric 26.3 instance (with Fabric API): game starts, Open to LAN prints a domain. *(2026-11-17, Prism Launcher, by the agent; see VERIFICATION.md)*
- [x] Jar in a real NeoForge 26.3 instance: same. *(2026-11-17, Prism Launcher, by the agent)*
- [ ] One instance joins the other through the domain, in both directions. *(Not done: needs two accounts. Only a status ping through each domain was done.)*

---

## M1 — identity, rendezvous, menu, import, launch flow, player data

**Read:** DESIGN §4.1–4.4, §4.8, §6, §4.13 (owner key and invite), §4.15. **Confirm in sources:** §8 items 1, 2, 3, 9, 10, 11, 12, 15, 18.

**Work:**

1. `core/crypto`, `core/invite`: HKDF-SHA256; Ed25519 key pair from a 32-byte seed (`KeyPairGenerator` with a `SecureRandom` that returns the seed; test against RFC 8032 test vector 1); AEAD; invite encode/decode.
2. `core/pkarr`: DNS packet codec for one TXT record, signed-packet codec, HTTP client, `Rendezvous` with the claim / heartbeat / idle rules and the `lan` hint (§4.3); `FileRendezvous`. Check the wire format against https://pubky.github.io/pkarr/design/relays.html and a known-good vector from the pkarr repo first. Find which public relays are live; make the list configurable. Unit tests against a fake relay; one opt-in integration test against a real relay.
3. `mc/menu`: title-screen button; Co-op Worlds screen with Play, New, Import, Join with invite, Invite, Remove; live status lines; badge in the Singleplayer list; "Make co-op" in the pause menu.
4. Import (§4.4): from saves, from folder, from .zip (drag-and-drop, Browse if available); the three checks in order; backup option; safe extraction.
5. `mc/launch`: the open-world hook, `CoopLauncher`, automatic LAN publish, rendezvous publish from the domain-assigned hook (DESIGN §3 row 8).
6. The `singleplayer_uuid` fix (§4.8). Choose rewrite-before-open or a mixin from what the sources show; note which and why.
7. Owner identity (§4.13, key and invite only): generate the owner key at import/create, store it, put its fingerprint in the invite, "Export owner key". No policy, no enforcement yet.
8. Players tab (§4.15) from the locally known members: names, skins, owner badge. A "Co-op Worlds" button on the Singleplayer world list, and the `titleButton` config. Confirm §8 item 18 first.

**Out of scope:** side-channel, manifest, ledger, handoff, merge. A first join with no local copy connects as a normal client and says world sync arrives in the next version.

**Gate suite `m1`:**

- `menu`: the title screen has the Co-op Worlds button; it opens the screen; the screen lists a known co-op world.
- `import-saves`: a plain world imported from saves appears in the co-op list, has `dropin/world.json`, a key file, a backup, and an invite that decodes to the same world id.
- `import-zip`: a zipped world dropped on the screen is extracted into `saves/` and imported.
- `import-rejects`: a zip with a path-escaping entry, a folder without `level.dat`, an open world, and an already-co-op world are each refused with the right message.
- `remove`: Remove deletes `dropin/` and the key; the world still opens as plain singleplayer.
- `drop-in` *(cross-loader)*: A presses Play and hosts; B presses Play on the same world and lands in A's session without typing an address.
- `host-swap-own-inventory`: A and B each `/give` themselves a different item and `/tp` to different coordinates; A quits; the world folder is copied to B; B presses Play and hosts with B's item at B's position; A joins and has A's item at A's position.
- `race`: A and B press Play within one second of each other; exactly one ends up hosting and the other joins it.
- `owner-invite`: the invite of an imported world decodes to the world id and the fingerprint of the owner key on disk; a world joined by invite has no owner key file. Unit-level too: an invite with a wrong-length payload is refused.
- `players-tab`: after `drop-in`, both A's and B's Players tab list both names; on A's PC (the owner's) A is marked as owner. That B also shows A as owner is checked in `m2b` (`owner-known-to-members`), because the owner's identity reaches members with the signed policy (owner's decision, 2026-11-17).

**Online smoke (once):** `drop-in` with the real e4mc relay and real pkarr relays.

**Manual checklist:**

- [ ] The Co-op Worlds button looks right on the title screen at GUI scales 2 and 4, and with Mod Menu installed on Fabric.
- [ ] Import one of your real worlds; it still opens normally from Singleplayer afterwards.
- [ ] Two PCs, two real accounts: send the invite, copy the world folder by hand, repeat `drop-in` and `host-swap-own-inventory` over the internet.

---

## M2 — sync channel, full download, live checkpoints

**Read:** DESIGN §4.5–4.7, §2 limit 3. **Confirm in sources:** §8 items 4–8.

**Work:**

1. `core/manifest`, `core/ledger`, `core/sync` (frame codec, handshake, client and server state machines over an abstract transport). Unit tests: manifest diff; chain compare (ahead, behind, diverged, common ancestor); full sync over an in-memory transport; a transfer cut mid-way that resumes; a tampered record that is rejected.
2. `mc/net`: `ProtocolSniffer` around the captured child handler (§4.6); client transports (NIO and Dialtone). If the sniffer cannot be made reliable, stop and report before using the second-session fallback.
3. `mc/host`: dirty tracking, checkpointing, serving diffs and records. Import and first host now build the first manifest in the background.
4. `mc/member`: `SyncClient`, record applier using the game's region-file classes on a closed world, `state.json`, progress in the Co-op Worlds status line.
5. Path policy: bulk only on LAN or a direct Dialtone path unless `bulkSyncOverRelay` is on.

**Out of scope:** handoff, failover, merge.

**Gate suite `m2`:**

- `vanilla-through-sniffer`: with the sniffer installed, an ordinary login and a status ping both still work.
- `first-download-root-match`: B joins with only the invite, plays while downloading; when B reports complete, A quits; B opens the world alone and reports the same root hash A reported; a block A placed is there.
- `live-checkpoint`: a block A places while B is connected reaches B's local copy within two checkpoint intervals.
- `resume`: B's process is killed mid-download; on rejoin the download continues and finishes with a matching root.
- `path-policy`: with the path forced to "relayed" and `bulkSyncOverRelay` off, no record frames are sent and the waiting message shows.

**Online smoke (once):** `first-download-root-match` on a small world through the real relay with a direct Dialtone path, if one can be established on this network.

**Manual checklist:**

- [ ] Two PCs over the internet: the HUD or status line shows whether the path is direct; a real world of a few hundred MB downloads and matches.
- [ ] If the path is relayed, confirm bulk sync stays off by default.

---

## M2b — owner policy and mod sync

Added 2026-11-17. **Read:** DESIGN §4.13–4.15, §2 limits 5–7, §4.6. **Confirm in sources:** §8 items 16, 17, 19, 20.

**Work:**

1. **Spike first: the swap helper** (§4.14). A dev client writes a plan, starts the detached helper and quits; the helper changes a `mods/` folder after the game process has exited; the next start loads the new set. Prove it on Fabric and on NeoForge, on Windows. If it cannot work, stop and report before trying loader-specific injection.
2. `core/policy`: owner-signed policy, canonical bytes, version compare. Unit tests: a valid policy; a wrong signature; a key that does not match the invite fingerprint; an older version never replaces a newer one.
3. `core/mods`: manifest build and hash, diff (to download, to remove, kept extras), file-name sanitising, size caps, swap plan, Modrinth lookup client against a fake server. Unit tests for each, including a path-escaping name and an over-cap manifest.
4. Sync messages `POLICY`, `POLICY_REQ`, `MEMBERS`, `MOD_GET`, `MOD_DATA`; policy version and manifest hash in the rendezvous payload. Peer mod transfer obeys the path policy.
5. `mc/mods`: scan the mods folder; the owner's "Update this world's mod list?" screen; the member's consent screen; download with hash check into the store; helper launch.
6. `mc/policy`: the check before the integrated server boots (mods, datapacks); refuse to host with kept extras; bans; owner operator rights; `membersMayUseCommands`; checkpoint-then-disconnect when a newer policy arrives mid-session.
7. Mods tab (§4.15); Players tab now fed by `MEMBERS`, so offline members are listed.

**Out of scope:** syncing `config/`, resource packs or shader packs; CurseForge lookups; installing a loader or Java; relaunching the game.

**Gate suite `m2b`** (test mods are tiny jars built by the test build, one per loader, each adding one item):

- `policy-unit`, `mods-unit`: the unit tests of steps 2 and 3.
- `mod-join`: the owner hosts with test mod X; B, without X, presses Play, sees the consent screen with X listed, approves; B's game exits; the helper puts X in B's `mods/`; B is started again, presses Play and lands in the session holding the item from X.
- `mod-decline`: B cancels the consent screen; nothing is downloaded, B's `mods/` is unchanged, B is not connected.
- `mod-change-live`: with B connected, the owner's policy gains test mod Y (owner restarts with Y and re-opens); B is disconnected with the "mods changed" screen and gets back in only after updating.
- `mod-tamper`: a peer serves a jar whose hash differs from the manifest; it is rejected and never reaches `mods/`. A policy signed by a different key is rejected.
- `mod-keep-extra`: B marks a client-only test jar "keep"; B can join; B is refused as host.
- `owner-known-to-members`: after B has joined A's world (A is the owner), B's Players tab marks A as owner, and still does after A quits and B hosts (moved here from M1's `players-tab`).
- `owner-rights`: with B hosting and the owner joined, the owner can run an operator command and B cannot (with `membersMayUseCommands` off); a banned UUID is refused.
- `datapack-mismatch`: B's `datapacks/` differs from the policy; B is refused as host with the right message.
- `mods-tab`: the Mods tab lists X and marks it "missing here" on a client without it.
- `swap-restores`: the member's own jars that were moved are all present under `mods/.dropin-disabled/`; nothing was deleted.

**Online smoke (once):** `mod-join` with a real, small mod that Modrinth's hash lookup knows, downloaded from Modrinth.

**Manual checklist:**

- [ ] A real modpack of 50+ mods on the owner's PC, one loader: a friend with an empty `mods/` folder joins after one approve-and-restart, in the launcher they normally use.
- [ ] The owner adds a mod and removes one; the friend is kept out, updates, and gets back in.
- [ ] The title-screen button and the Co-op Worlds screen still work inside that modpack; note any mod that hides or breaks them.
- [ ] The friend's own mods are all in `mods/.dropin-disabled/`.

---

## M3 — graceful handoff

**Read:** DESIGN §4.9 (graceful), §4.3.

**Work:**

1. `core`: handoff messages; successor choice as a pure, unit-tested function.
2. `mc/handoff`: intercept the host's quit; final checkpoint with acks; the "nobody has a full copy" confirmation; `HANDOFF` broadcast; rendezvous `st:"handoff"`.
3. Successor: disconnect, open local copy, publish. Others: "Host is changing…" screen, poll, reconnect.

**Gate suite `m3`:**

- `handoff-3`: A hosts, B and C fully synced; A quits; B becomes host; C is back in the world with no input; all three positions and inventories are as they were; the root B reports at takeover equals A's final root.
- `handoff-no-full-copy`: A quits while B is at partial sync; A sees the confirmation; cancelling keeps the session alive.

**Manual checklist:**

- [ ] Three real players: the host quits; time how long the others are out of the world.

---

## M4 — crash failover, stale-copy warning, status HUD

**Read:** DESIGN §4.9 (crash), §4.4 launch flow step 3, §4.3.

**Work:**

1. `PEERS` broadcast each checkpoint; election order as a pure, unit-tested function; the 45 s fallthrough.
2. Client-side failover after an unexpected disconnect.
3. The stale-copy warning in `CoopLauncher`.
4. HUD or pause-menu line: host name, sync %, path direct/relayed, last checkpoint age.

**Gate suite `m4`:**

- `crash-failover`: the orchestrator kills A's process; the peer the election picks takes over; the other reconnects; a block placed before the last checkpoint survives.
- `crash-fallthrough`: the elected peer is also killed; the next peer takes over after the timeout.
- `stale-warning`: B's copy is one session behind and nobody is online; B sees the warning; Cancel leaves B's copy untouched.

**Manual checklist:**

- [ ] Pull the network cable on the host PC mid-session; the others recover.

---

## M5 — divergence and merge

**Read:** DESIGN §4.10, §4.7.

**Work:**

1. `core/merge`: three-way planner as a pure function. Unit tests: branch-only change applied; both-changed keeps main and emits a backup action; a chunk's three records move together; the player-files rule; deltas too old → "save as separate world".
2. Branch preservation on the diverged member; `BRANCH_PUSH`; pending-branch relay.
3. Cold merge before the integrated server boots; merge entry with two parents; chat report; backups folder.

**Gate suite `m5`:**

- `merge-clean`: from a common copy, A edits near spawn and B edits 500 blocks away, each hosting alone with rendezvous unreachable; they meet; after the merge both edits exist on both machines and the roots match.
- `merge-conflict`: both edit the same chunk; the live host's version is in the world; the other is under `dropin/backups/conflicts/`; the chat report counts one conflict.
- `merge-too-old`: with deltas pruned past the ancestor, the branch is saved as a separate world and the main world is untouched.

**Manual checklist:**

- [ ] Repeat `merge-clean` with two real PCs, one of them offline for a session.

---

## M6 (optional) — always-on seed for Unraid

**Read:** DESIGN §2 limit 1, §7 M6.

Before any code: a one-page plan covering how the seed is reachable without the game's tunnel code, how members discover it (extra rendezvous field), and what it needs on Unraid (ports, volume). Wait for approval.

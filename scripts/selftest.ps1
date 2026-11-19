<#
.SYNOPSIS
  DropIn Co-op self-test orchestrator (DESIGN 4.12). Development only.

.DESCRIPTION
  Launches two or three dev clients through the loaders' Gradle run tasks, waits for each
  client's run/<slot>/dropin-selftest/result.json, prints one line per scenario and exits
  non-zero on any failure. Full logs go to build/selftest-logs/, never to the console.

.EXAMPLE
  scripts/selftest.ps1 -Suite m0
  scripts/selftest.ps1 -Suite all
  scripts/selftest.ps1 -Suite m0 -Online     # only the opt-in online smoke scenarios of m0
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Suite,
    [switch]$Online,
    [string]$Only = '',
    [int]$TimeoutSec = 600
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$gradlew = Join-Path $root 'gradlew.bat'
$logDir = Join-Path $root 'build\selftest-logs'
$sharedDir = Join-Path $root 'run\shared'

function New-Role([string]$role, [string]$loader, [string]$slot) {
    return @{ Role = $role; Loader = $loader; Slot = $slot }
}

# Suites in milestone order. "all" runs every suite listed here.
$suites = [ordered]@{
    m0 = @(
        @{ Id = 'lan-join.fabric-fabric'; Scenario = 'lan-join'
           Roles = @((New-Role 'host' 'fabric' 'a'), (New-Role 'joiner' 'fabric' 'b')) },
        @{ Id = 'lan-join.fabric-neoforge'; Scenario = 'lan-join'
           Roles = @((New-Role 'host' 'fabric' 'a'), (New-Role 'joiner' 'neoforge' 'b')) },
        @{ Id = 'lan-join.neoforge-fabric'; Scenario = 'lan-join'
           Roles = @((New-Role 'host' 'neoforge' 'a'), (New-Role 'joiner' 'fabric' 'b')) },
        @{ Id = 'status-ping'; Scenario = 'status-ping'
           Roles = @((New-Role 'host' 'fabric' 'a'), (New-Role 'pinger' 'fabric' 'b')) },
        @{ Id = 'prod-guard'; Unit = @(':core:test', '--tests', 'dropin.core.dev.DevSwitchesTest') }
    )
    m1 = @(
        @{ Id = 'menu'; Scenario = 'menu'; Roles = @((New-Role 'solo' 'fabric' 'a')) },
        @{ Id = 'import-saves'; Scenario = 'import-saves'; Roles = @((New-Role 'solo' 'fabric' 'a')) },
        @{ Id = 'import-zip'; Scenario = 'import-zip'; Roles = @((New-Role 'solo' 'neoforge' 'a')) },
        @{ Id = 'import-rejects'; Scenario = 'import-rejects'; Roles = @((New-Role 'solo' 'fabric' 'a')) },
        @{ Id = 'remove'; Scenario = 'remove'; Roles = @((New-Role 'solo' 'neoforge' 'a')) },
        @{ Id = 'drop-in.fabric-neoforge'; Scenario = 'drop-in'
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'neoforge' 'b')) },
        @{ Id = 'drop-in.neoforge-fabric'; Scenario = 'drop-in'
           Roles = @((New-Role 'a' 'neoforge' 'a'), (New-Role 'b' 'fabric' 'b')) },
        @{ Id = 'host-swap-own-inventory'; Scenario = 'host-swap-own-inventory'
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) },
        @{ Id = 'race'; Scenario = 'race'
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) },
        @{ Id = 'owner-invite'; Scenario = 'owner-invite'; Roles = @((New-Role 'solo' 'fabric' 'a')) },
        @{ Id = 'owner-invite.unit'; Unit = @(':core:test', '--tests', 'dropin.core.invite.InviteTest') },
        @{ Id = 'players-tab'; Scenario = 'players-tab'
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) }
    )
    m2 = @(
        @{ Id = 'vanilla-through-sniffer'; Scenario = 'vanilla-through-sniffer'
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'neoforge' 'b')) },
        @{ Id = 'first-download-root-match'; Scenario = 'first-download-root-match'
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'neoforge' 'b')) },
        @{ Id = 'live-checkpoint'; Scenario = 'live-checkpoint'
           Roles = @((New-Role 'a' 'neoforge' 'a'), (New-Role 'b' 'fabric' 'b')) },
        # B's game is killed when it writes run/shared/kill-b, then started again as role b2 on the same game folder.
        @{ Id = 'resume'; Scenario = 'resume'; TimeoutSec = 900
           Relaunch = @{ Marker = 'kill-b'; Index = 1; Role = 'b2' }
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) },
        @{ Id = 'path-policy'; Scenario = 'path-policy'
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) }
    )
    m2b = @(
        @{ Id = 'policy-unit'; Unit = @(':core:test', '--tests', 'dropin.core.policy.*') },
        @{ Id = 'mods-unit'; Unit = @(':core:test', '--tests', 'dropin.core.mods.*') },
        # The spike of M2b step 1: the game starts with test mod Z, asks the helper for X instead and quits; the
        # helper swaps mods/ once the game is gone; the game is started again as a2 and must have loaded X, not Z.
        @{ Id = 'swap-spike.fabric'; Scenario = 'swap-spike'; Mods = @{ a = @('z') }
           Relaunch = @{ GameFile = 'config\dropin\modswap.result.json'; Index = 0; Role = 'a2' }
           Roles = @((New-Role 'a' 'fabric' 'a')) },
        @{ Id = 'swap-spike.neoforge'; Scenario = 'swap-spike'; Mods = @{ a = @('z') }
           Relaunch = @{ GameFile = 'config\dropin\modswap.result.json'; Index = 0; Role = 'a2' }
           Roles = @((New-Role 'a' 'neoforge' 'a')) },
        # A is the owner and hosts with test mod X. B approves the consent screen, its game exits, the helper swaps
        # B's mods/, and B is started again as b2 on the same game folder.
        @{ Id = 'mod-join.fabric'; Scenario = 'mod-join'; TimeoutSec = 900; Mods = @{ a = @('x') }
           Relaunch = @{ GameFile = 'config\dropin\modswap.result.json'; Index = 1; Role = 'b2' }
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) },
        @{ Id = 'mod-join.neoforge'; Scenario = 'mod-join'; TimeoutSec = 900; Mods = @{ a = @('x') }
           Relaunch = @{ GameFile = 'config\dropin\modswap.result.json'; Index = 1; Role = 'b2' }
           Roles = @((New-Role 'a' 'neoforge' 'a'), (New-Role 'b' 'neoforge' 'b')) },
        @{ Id = 'mod-decline'; Scenario = 'mod-decline'; Mods = @{ a = @('x') }
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) },
        # The owner (X) restarts with X and Y while B (X) hosts; B's session ends, and B gets back in after its own restart.
        @{ Id = 'mod-change-live'; Scenario = 'mod-change-live'; TimeoutSec = 1500; Mods = @{ a = @('x'); b = @('x') }
           Relaunch = @(@{ Marker = 'restart-a'; Index = 0; Role = 'a2' },
                        @{ GameFile = 'config\dropin\modswap.result.json'; Index = 1; Role = 'b2' })
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) },
        @{ Id = 'mod-tamper'; Scenario = 'mod-tamper'; Mods = @{ a = @('x') }
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) },
        @{ Id = 'mod-keep-extra'; Scenario = 'mod-keep-extra'; TimeoutSec = 900; Mods = @{ a = @('x'); b = @('x', 'z') }
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) },
        @{ Id = 'owner-known-to-members'; Scenario = 'owner-known-to-members'; TimeoutSec = 900
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'neoforge' 'b')) },
        @{ Id = 'owner-rights'; Scenario = 'owner-rights'; TimeoutSec = 900
           Roles = @((New-Role 'a' 'neoforge' 'a'), (New-Role 'b' 'fabric' 'b')) },
        @{ Id = 'datapack-mismatch'; Scenario = 'datapack-mismatch'; TimeoutSec = 900
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) },
        @{ Id = 'mods-tab'; Scenario = 'mods-tab'; Mods = @{ a = @('x'); b = @('z') }
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) },
        # B starts with its own jars Y and Z; after the swap both must be under mods/.dropin-disabled/, unchanged.
        @{ Id = 'swap-restores'; Scenario = 'swap-restores'; TimeoutSec = 900; Mods = @{ a = @('x'); b = @('y', 'z') }
           Relaunch = @{ GameFile = 'config\dropin\modswap.result.json'; Index = 1; Role = 'b2' }
           Roles = @((New-Role 'a' 'neoforge' 'a'), (New-Role 'b' 'neoforge' 'b')) }
    )
    m3 = @(
        # A hosts, B and C are fully synced; A quits; B takes over; C is back with no input; A rejoins B.
        @{ Id = 'handoff-3'; Scenario = 'handoff-3'; TimeoutSec = 900
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b'), (New-Role 'c' 'fabric' 'c')) },
        # A quits while B is mid-download: A is asked first, and cancelling keeps the session alive.
        @{ Id = 'handoff-no-full-copy'; Scenario = 'handoff-no-full-copy'; TimeoutSec = 900
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) }
    )
}

# DESIGN 4.16 (save points). Added to $suites below so that "all" runs it after m3.
$savePointSuite = @(
    @{ Id = 'savepoint-unit'; Unit = @(':core:test', '--tests', 'dropin.core.savepoint.*') },
    # A makes a save point, A and B change the world and themselves, A restores it by command; B rejoins by itself.
    @{ Id = 'savepoint-rollback'; Scenario = 'savepoint-rollback'; TimeoutSec = 900
       Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) },
    # The limit of ten, export and import, and a single-player world made from a save point.
    @{ Id = 'savepoint-files'; Scenario = 'savepoint-files'; TimeoutSec = 900; Roles = @((New-Role 'solo' 'fabric' 'a')) }
)
$suites['m3b'] = $savePointSuite

# DESIGN 4.13 (admins) and 4.15 (who is online in the menu).
$suites['ui'] = @(
    # A makes B an admin; B's rights, B's own menu rows and the announced player list are checked on both sides.
    @{ Id = 'admin-online'; Scenario = 'admin-online'; TimeoutSec = 600
       Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) },
    # The import picker by its buttons, Export world and its way back in, and the Join screen with its preview.
    @{ Id = 'menu-flows'; Scenario = 'menu-flows'; Roles = @((New-Role 'solo' 'fabric' 'a')) },
    # Two players in one session: hits land both ways with the pvp game rule on, none with it off, and
    # the host sees the other player where that player says it is after walking.
    @{ Id = 'pvp'; Scenario = 'pvp'; TimeoutSec = 600
       Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) },
    # What the host's server does (a block, an item, health and an effect, a mob's place) is on the other
    # player's screen within a second, and the other player's hit on a mob is on the host's.
    @{ Id = 'realtime'; Scenario = 'realtime'; TimeoutSec = 600
       Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) },
    # Things that move by themselves: a minecart on powered rails, a cow led by wheat, a cow fleeing after a
    # hit. Each is followed tick by tick on the host's server and on three screens (C runs NeoForge); the paths,
    # the resting places and the mob's health, age, glow and name must be the same.
    @{ Id = 'motion'; Scenario = 'motion'; TimeoutSec = 900
       Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b'), (New-Role 'c' 'neoforge' 'c')) }
)

# World integrity (DESIGN 4.5 to 4.9): after play and after a handoff, every copy holds the host's data.
$suites['integrity'] = @(
    # A hosts; B and C have copies. Ores, a chest, a villager (inventory, trades), mob health and each player's
    # health, effects and items are decoded from the host's records and from each member's copy at the same
    # checkpoint and must be the same; again after A handed the world to B; then every closed copy is read back
    # from disk in full. C runs NeoForge.
    @{ Id = 'integrity'; Scenario = 'integrity'; TimeoutSec = 1200
       Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b'), (New-Role 'c' 'neoforge' 'c')) },
    # What a mod keeps in the world's root reaches B and C; the downloads stay under the direct rate cap; and of
    # two complete copies the player with the faster upload (C) is the one who would take over, not the one
    # connected longest (B). C runs NeoForge.
    @{ Id = 'sync-extras'; Scenario = 'sync-extras'; TimeoutSec = 900
       Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b'), (New-Role 'c' 'neoforge' 'c')) }
)

# Measurements, not gates: run by name only, never by "all".
$measureSuites = @{
    perf = @(
        # A plays alone, then hosts, then B downloads and stays; server tick times and checkpoint costs are logged.
        @{ Id = 'perf'; Scenario = 'perf'; TimeoutSec = 1800
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) }
    )
}

# Opt-in scenarios that touch public infrastructure (the real e4mc relay). Only run with -Online.
$onlineSuites = @{
    m0 = @(
        @{ Id = 'tunnel-join'; Scenario = 'tunnel-join'; Online = $true
           Roles = @((New-Role 'host' 'fabric' 'a'), (New-Role 'joiner' 'fabric' 'b')) }
    )
    m1 = @(
        @{ Id = 'drop-in.online'; Scenario = 'drop-in'; Online = $true
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) }
    )
    m2 = @(
        @{ Id = 'first-download-root-match.online'; Scenario = 'first-download-root-match'; Online = $true
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) },
        # The first download with the real mods of $packMods in both games, over the LAN (two clients: quick).
        @{ Id = 'first-download-root-match.pack'; Scenario = 'first-download-root-match'; TimeoutSec = 240
           Mods = @{ a = @('pack'); b = @('pack') }
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) }
    )
    m2b = @(
        # The owner's only mod is a real one that Modrinth's hash lookup knows; B downloads it from Modrinth.
        @{ Id = 'mod-join.online'; Scenario = 'mod-join'; Online = $true; TimeoutSec = 900; Mods = @{ a = @('online') }
           Relaunch = @{ GameFile = 'config\dropin\modswap.result.json'; Index = 1; Role = 'b2' }
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) }
    )
    m3 = @(
        # The handoff through the real relay: how long the members are out of the game.
        @{ Id = 'handoff-3.online'; Scenario = 'handoff-3'; Online = $true; TimeoutSec = 900
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b'), (New-Role 'c' 'fabric' 'c')) }
    )
    integrity = @(
        # The integrity scenario with the real mods of $packMods in every game (all Fabric). The games talk over
        # the LAN; "online" here is only the download of the mods from Modrinth.
        @{ Id = 'integrity.pack'; Scenario = 'integrity'; TimeoutSec = 1500
           Mods = @{ a = @('pack'); b = @('pack'); c = @('pack') }
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b'), (New-Role 'c' 'fabric' 'c')) }
    )
    ui = @(
        # Hits, hurt animations, movement and realtime changes with the second player connected through the real relay.
        @{ Id = 'pvp.online'; Scenario = 'pvp'; Online = $true; TimeoutSec = 900
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) },
        @{ Id = 'realtime.online'; Scenario = 'realtime'; Online = $true; TimeoutSec = 900
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b')) },
        @{ Id = 'motion.online'; Scenario = 'motion'; Online = $true; TimeoutSec = 1200
           Roles = @((New-Role 'a' 'fabric' 'a'), (New-Role 'b' 'fabric' 'b'), (New-Role 'c' 'neoforge' 'c')) }
    )
}

# The real mod of the online smoke (Fabric): small, no dependencies, published on Modrinth for Minecraft 26.3.
$onlineMod = @{
    File = 'ferritecore-9.0.0-fabric.jar'
    Url = 'https://cdn.modrinth.com/data/uXXizFIs/versions/d5ddUdiB/ferritecore-9.0.0-fabric.jar'
    Sha512 = 'd81fa97e11784c19d42f89c2f433831d007603dd7193cee45fa177e4a6a9c52b384b198586e04a0f7f63cd996fed713322578bde9a8db57e1188854ae5cbe584'
}

# Real mods (Fabric, published on Modrinth for Minecraft 26.3) for the modpack run of the integrity scenario:
# chunk storage and threading (C2ME), game logic (Lithium), the network stack (Krypton), rendering (Sodium,
# ImmediatelyFast, Entity Culling), memory (FerriteCore), content with blocks, items and block entities
# (Farmer's Delight), world generation hooks (TerraBlender) and common libraries. Fabric API comes from the dev runtime.
$packMods = @(
    @{ File = 'lithium-fabric-0.26.3+mc26.3.jar'
       Url = 'https://cdn.modrinth.com/data/gvQqBUqZ/versions/fWv5bnZk/lithium-fabric-0.26.3%2Bmc26.3.jar'
       Sha512 = '2ca92fb530135d2baddf0ba4cc4f93976234876a38c4aa02b487b2552400f005afcfbeebf5373fb4396fe2020537a42d7597b8da5a52b904355ef51d5fef07ad' },
    @{ File = 'sodium-fabric-0.9.3-beta.5+mc26.3.jar'
       Url = 'https://cdn.modrinth.com/data/AANobbMI/versions/v7Lw0h1m/sodium-fabric-0.9.3-beta.5%2Bmc26.3.jar'
       Sha512 = '343e63958e3867077ecbfa752dbc0ce079e1244d7a125309258ade62bc13aecb95c47378d4f42e76369c957c1f97c29dd751551151b65136830374d553cd275c' },
    @{ File = 'c2me-fabric-mc26.3-0.4.2-alpha.0.119.jar'
       Url = 'https://cdn.modrinth.com/data/VSNURh3q/versions/W9HllAiU/c2me-fabric-mc26.3-0.4.2-alpha.0.119.jar'
       Sha512 = 'ba50c585ac92588bbe40ed77d2bdac3c22bc2a543165feebe19684730479da52fa8e5bffaebe8801b90d8f7616fd57703b2f2b447807a65061919fb86f887821' },
    @{ File = 'ferritecore-9.0.0-fabric.jar'
       Url = 'https://cdn.modrinth.com/data/uXXizFIs/versions/d5ddUdiB/ferritecore-9.0.0-fabric.jar'
       Sha512 = 'd81fa97e11784c19d42f89c2f433831d007603dd7193cee45fa177e4a6a9c52b384b198586e04a0f7f63cd996fed713322578bde9a8db57e1188854ae5cbe584' },
    @{ File = 'cloth-config-fabric-26.3.159.jar'
       Url = 'https://cdn.modrinth.com/data/9s6osm5g/versions/fg2uyxOW/cloth-config-fabric-26.3.159.jar'
       Sha512 = '8924e19d41845096724fa10f3717bc51964a0edbbbcad0ff1e6c6187a398b08f356e49579a657c2dc44a7f92e3850771f3ca70bf8c1f654de60425163b91dbb1' },
    @{ File = 'Jade-mc26.3-Fabric-26.3.5.jar'
       Url = 'https://cdn.modrinth.com/data/nvQzSEkH/versions/71CTWqdE/Jade-mc26.3-Fabric-26.3.5.jar'
       Sha512 = '2e7331e62eb6658268ae92dc42de8b469f50dd6c6a9d2546a6f649bae97c2cdfd8d4c1560948cf46269175d2c79c6c47a36a02685e39d476768d18fbb2a28703' },
    @{ File = 'appleskin-fabric-mc26.3-3.0.10.jar'
       Url = 'https://cdn.modrinth.com/data/EsAfCjCV/versions/PHjDtQay/appleskin-fabric-mc26.3-3.0.10.jar'
       Sha512 = '17d257af419b7530aa8617c2e8c0e4bbfabcf888f26655790e0188e7957e0bcd931f85f9a4c69fd27e889dee5082cf7b2b8741a14a6ed4425e9ef4bfe13a8b8c' },
    @{ File = 'entityculling-fabric-1.12.1-mc26.3.jar'
       Url = 'https://cdn.modrinth.com/data/NNAgCjsB/versions/UWpkLRV5/entityculling-fabric-1.12.1-mc26.3.jar'
       Sha512 = 'c56e2a19fa98b8ee85d7f46b74966a272f03ec9a8edacd4a6eb629970f19fd4d2a9bddeacb8405f9abdb790a40895244927a28cf5dbbe4dcb65a0b307f493fcb' },
    @{ File = 'ImmediatelyFast-Fabric-1.17.1+26.3.jar'
       Url = 'https://cdn.modrinth.com/data/5ZwdcRci/versions/3MP9UR23/ImmediatelyFast-Fabric-1.17.1%2B26.3.jar'
       Sha512 = '8e139589fd995bce3d485a34d92a07243964212f7ed2e4fc2f6de5b9a74d0f59b03252e7ce40c64e006cf3e21bed5de27b830d33ef048b286e041f6178ad8428' },
    @{ File = 'krypton-0.3.2.jar'
       Url = 'https://cdn.modrinth.com/data/fQEb0iXm/versions/UugdIYJw/krypton-0.3.2.jar'
       Sha512 = 'd1d57ebd41395b75b01f130cd9503eb8d208212424a399ff9f367f50be8fbc1c6472442b33c686e777c3148f6a609520e8fb732c755157c358cb207fd4d1123a' },
    @{ File = 'FarmersDelight-26.3-3.6.30+refabricated.jar'
       Url = 'https://cdn.modrinth.com/data/7vxePowz/versions/dRScyUeE/FarmersDelight-26.3-3.6.30%2Brefabricated.jar'
       Sha512 = '099f724599022ac9232f265d62907fdde6ad48a73b246d45930d938a7f850be6a8b520244ffa218a59fdef96b0170fc0c04f00ff91f108b5a02bbc4cb2a013bc' },
    @{ File = 'TerraBlender-fabric-26.3-26.3.0.0.9.jar'
       Url = 'https://cdn.modrinth.com/data/kkmrDlKT/versions/xirJYe1V/TerraBlender-fabric-26.3-26.3.0.0.9.jar'
       Sha512 = '183f809fe992aaaaf4d84c594730b95b392338caf17b690debbb14434b23b46f5923cfcc5aecb81368ea06a299503a3738dcf942b7f179abd000f1312cf2cbdb' },
    @{ File = 'architectury-fabric-22.0.6.jar'
       Url = 'https://cdn.modrinth.com/data/lhGA9TYQ/versions/Njpa4lhR/architectury-fabric-22.0.6.jar'
       Sha512 = 'ba6c4facc8b6fd0bc2759de295b2db007471a7baa3523ca9163c2e17d7106ed1c6bf90dd54ba9da6235fba5a84141739b18e06be7fd0beea628cb67c7eaa2ec5' },
    @{ File = 'geckolib-fabric-26.3-5.5.7.jar'
       Url = 'https://cdn.modrinth.com/data/8BmcQJ2H/versions/kSxHvs99/geckolib-fabric-26.3-5.5.7.jar'
       Sha512 = 'c7c89bd6c5f2801ef1575e2e1212202f5a3eff480d73503beff12b1d60dfb751112fccf36e36c243b26b6b492261cef12d8b2c1a92a734bb0b621daa8c158fc5' },
    @{ File = 'yet_another_config_lib_v3-3.9.7+26.3-fabric.jar'
       Url = 'https://cdn.modrinth.com/data/1eAoo2KR/versions/s9SjoFu1/yet_another_config_lib_v3-3.9.7%2B26.3-fabric.jar'
       Sha512 = 'f3957028dd31cae78f08aee3e768f61bd12c4c8423257d357bc3c1a2172047c769d37901f99775c7ffff1e5ee794025dc72738b2869cab06f1c2811841d68b24' }
)

function Resolve-Jdk25 {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    foreach ($base in @("$env:ProgramFiles\Microsoft", "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java")) {
        if (Test-Path $base) {
            $candidates += Get-ChildItem $base -Directory -Filter 'jdk-25*' | ForEach-Object { $_.FullName }
        }
    }
    foreach ($dir in $candidates) {
        $release = Join-Path $dir 'release'
        if ((Test-Path $release) -and (Select-String -Path $release -Pattern 'JAVA_VERSION="25' -Quiet)) {
            return $dir
        }
    }
    throw 'JDK 25 not found. Set JAVA_HOME to a JDK 25 installation.'
}

function Get-FreePort {
    $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    return $port
}

function Get-GameDir([string]$slot) {
    return Join-Path $root "run\$slot"
}

function Get-TestMod([string]$loader, [string]$name) {
    if ($name -eq 'online') { return Get-OnlineMod }
    if ($name -eq 'pack') { return Get-PackMods }
    return Join-Path $root "build\testmods\$loader\dropin-testmod-$name.jar"
}

# Downloads the online smoke's mod once (build/testmods/online) and checks it against the published hash.
function Get-OnlineMod {
    $dir = Join-Path $root 'build\testmods\online'
    $file = Join-Path $dir $onlineMod.File
    if (-not (Test-Path $file)) {
        New-Item -ItemType Directory -Force $dir | Out-Null
        Invoke-WebRequest -Uri $onlineMod.Url -OutFile $file -UserAgent 'dropin-coop/selftest' -UseBasicParsing
    }
    $hash = (Get-FileHash -Algorithm SHA512 $file).Hash.ToLower()
    if ($hash -ne $onlineMod.Sha512) {
        Remove-Item -Force $file
        throw "The downloaded $($onlineMod.File) does not have the published hash."
    }
    return $file
}

# Downloads the pack's mods once (build/testmods/pack) and checks each against its published hash.
function Get-PackMods {
    $dir = Join-Path $root 'build\testmods\pack'
    New-Item -ItemType Directory -Force $dir | Out-Null
    $files = @()
    # DROPIN_PACK_SKIP=krypton,c2me leaves those mods out: for finding which mod a failure comes from.
    $skip = @()
    if ($env:DROPIN_PACK_SKIP) { $skip = $env:DROPIN_PACK_SKIP -split ',' }
    foreach ($mod in $packMods) {
        if ($skip | Where-Object { $mod.File -like "*$_*" }) { continue }
        $file = Join-Path $dir $mod.File
        if (-not (Test-Path $file)) {
            Invoke-WebRequest -Uri $mod.Url -OutFile $file -UserAgent 'dropin-coop/selftest' -UseBasicParsing
        }
        $hash = (Get-FileHash -Algorithm SHA512 $file).Hash.ToLower()
        if ($hash -ne $mod.Sha512) {
            Remove-Item -Force $file
            throw "The downloaded $($mod.File) does not have the published hash."
        }
        $files += $file
    }
    return $files
}

# $mods: names of test mods (x, y, z) that are in the slot's mods folder when the game first starts.
function Initialize-GameDir([string]$slot, [string]$loader, $mods) {
    $dir = Get-GameDir $slot
    New-Item -ItemType Directory -Force $dir | Out-Null
    foreach ($sub in @('dropin-selftest', 'saves', 'backups', 'config\dropin', 'mods')) {
        $path = Join-Path $dir $sub
        if (Test-Path $path) { Remove-Item -Recurse -Force $path }
    }
    foreach ($name in @($mods)) {
        if (-not $name) { continue }
        New-Item -ItemType Directory -Force (Join-Path $dir 'mods') | Out-Null
        Copy-Item (Get-TestMod $loader $name) (Join-Path $dir 'mods')
    }
    $options = @(
        'pauseOnLostFocus:false',
        'renderDistance:2',
        'simulationDistance:5',
        'soundCategory_master:0.0',
        'onboardAccessibility:false',
        'skipMultiplayerWarning:true',
        'tutorialStep:none',
        'narrator:0',
        'fullscreen:false',
        'maxFps:30'
    )
    # ASCII without BOM: the game cannot parse a BOM in options.txt
    [System.IO.File]::WriteAllLines((Join-Path $dir 'options.txt'), $options, (New-Object System.Text.ASCIIEncoding))
}

function Invoke-GradleLogged([string[]]$gradleArgs, [string]$logFile) {
    $cmdLine = '/c ""{0}" {1} > "{2}" 2>&1"' -f $gradlew, ($gradleArgs -join ' '), $logFile
    $proc = Start-Process -FilePath 'cmd.exe' -ArgumentList $cmdLine -WorkingDirectory $root -WindowStyle Hidden -PassThru -Wait
    return $proc.ExitCode
}

function Start-Client($scenario, $role, [int]$port) {
    $task = ':{0}:runClient{1}' -f $role.Loader, $role.Slot.ToUpper()
    $log = Join-Path $logDir ('{0}-{1}.log' -f $scenario.Id, $role.Role)
    $gradleArgs = @(
        $task, '--console=plain',
        ('"-Pdropin.selftest={0}:{1}"' -f $scenario.Scenario, $role.Role),
        ('"-Pdropin.lanPort={0}"' -f $port),
        ('"-Pdropin.sharedDir={0}"' -f $sharedDir)
    )
    if ($scenario.Online) { $gradleArgs += '"-Pdropin.online=true"' }
    # The game's own watchdog (540 s by default) must not end a scenario that is allowed to run longer.
    if ($scenario.TimeoutSec) { $gradleArgs += ('"-Pdropin.selftestTimeoutSec={0}"' -f $scenario.TimeoutSec) }
    $cmdLine = '/c ""{0}" {1} > "{2}" 2>&1"' -f $gradlew, ($gradleArgs -join ' '), $log
    return Start-Process -FilePath 'cmd.exe' -ArgumentList $cmdLine -WorkingDirectory $root -WindowStyle Hidden -PassThru
}

function Read-Result($role) {
    $file = Join-Path (Get-GameDir $role.Slot) 'dropin-selftest\result.json'
    if (-not (Test-Path $file)) { return $null }
    try { return Get-Content -Raw $file | ConvertFrom-Json } catch { return $null }
}

function Stop-Stragglers($procs, $roles) {
    foreach ($role in $roles) {
        $pidFile = Join-Path (Get-GameDir $role.Slot) 'dropin-selftest\pid'
        if (Test-Path $pidFile) {
            $gamePid = 0
            if ([int]::TryParse((Get-Content -Raw $pidFile).Trim(), [ref]$gamePid)) {
                $game = Get-Process -Id $gamePid -ErrorAction SilentlyContinue
                if ($game -and $game.ProcessName -like 'java*') {
                    Stop-Process -Id $gamePid -Force -ErrorAction SilentlyContinue
                }
            }
        }
    }
    foreach ($proc in $procs) {
        if (-not $proc.HasExited) {
            # Through cmd so taskkill's stderr cannot become a terminating PowerShell error
            & cmd.exe /c "taskkill.exe /T /F /PID $($proc.Id) >nul 2>&1"
        }
    }
}

# Kills one role's game the hard way (no shutdown hooks, nothing flushed) and waits for its Gradle wrapper to go.
function Stop-Game($proc, $role) {
    $pidFile = Join-Path (Get-GameDir $role.Slot) 'dropin-selftest\pid'
    $gamePid = 0
    if ((Test-Path $pidFile) -and [int]::TryParse((Get-Content -Raw $pidFile).Trim(), [ref]$gamePid)) {
        $game = Get-Process -Id $gamePid -ErrorAction SilentlyContinue
        if ($game -and $game.ProcessName -like 'java*') { Stop-Process -Id $gamePid -Force -ErrorAction SilentlyContinue }
    }
    $waitUntil = (Get-Date).AddSeconds(20)
    while (-not $proc.HasExited -and (Get-Date) -lt $waitUntil) { Start-Sleep -Milliseconds 250 }
    if (-not $proc.HasExited) { & cmd.exe /c "taskkill.exe /T /F /PID $($proc.Id) >nul 2>&1" }
    foreach ($leftover in @('pid', 'result.json')) {
        $path = Join-Path (Get-GameDir $role.Slot) "dropin-selftest\$leftover"
        if (Test-Path $path) { Remove-Item -Force $path }
    }
}

function Invoke-ClientScenario($scenario) {
    foreach ($role in $scenario.Roles) {
        $mods = @()
        if ($scenario.Mods -and $scenario.Mods.ContainsKey($role.Slot)) { $mods = $scenario.Mods[$role.Slot] }
        Initialize-GameDir $role.Slot $role.Loader $mods
    }
    if (Test-Path $sharedDir) { Remove-Item -Recurse -Force $sharedDir }
    New-Item -ItemType Directory -Force $sharedDir | Out-Null

    $port = Get-FreePort
    $roles = @($scenario.Roles)
    $procs = @()
    foreach ($role in $roles) {
        $procs += Start-Client $scenario $role $port
        Start-Sleep -Seconds 3   # let the first Gradle invocation get past configuration
    }

    $limit = $TimeoutSec
    if ($scenario.TimeoutSec -and $scenario.TimeoutSec -gt $limit) { $limit = $scenario.TimeoutSec }
    $deadline = (Get-Date).AddSeconds($limit)
    $settleUntil = $null
    $idleUntil = $null
    # Relaunches happen in the order listed. A relaunch is triggered by a file: 'Marker' in run/shared (the game is
    # then killed the hard way), or 'GameFile' in the role's game folder (written after the game quit by itself,
    # e.g. by the mod-swap helper; the run that quit must have passed).
    $relaunches = @()
    if ($scenario.Relaunch) { $relaunches = @($scenario.Relaunch) }
    $nextRelaunch = 0
    $relaunchProblems = @()
    $consumed = @{}
    while ((Get-Date) -lt $deadline) {
        $pending = $nextRelaunch -lt $relaunches.Count
        if ($pending) {
            $relaunch = $relaunches[$nextRelaunch]
            $index = $relaunch.Index
            if ($relaunch.GameFile) {
                $trigger = Join-Path (Get-GameDir $roles[$index].Slot) $relaunch.GameFile
            } else {
                $trigger = Join-Path $sharedDir $relaunch.Marker
            }
            # A game file stays where it is (the next run may read it), so one that was already used does not count.
            $fresh = (Test-Path $trigger) -and ($consumed[$trigger] -ne (Get-Item $trigger).LastWriteTimeUtc.Ticks)
            if ($fresh) {
                $consumed[$trigger] = (Get-Item $trigger).LastWriteTimeUtc.Ticks
                if ($relaunch.GameFile) {
                    $waitUntil = (Get-Date).AddSeconds(30)
                    while (-not $procs[$index].HasExited -and (Get-Date) -lt $waitUntil) { Start-Sleep -Milliseconds 250 }
                    $before = Read-Result $roles[$index]
                    if ($null -eq $before) {
                        $relaunchProblems += ('{0}: no result before the restart' -f $roles[$index].Role)
                    } elseif (-not $before.passed) {
                        $relaunchProblems += ('{0}: {1}' -f $roles[$index].Role, $before.failure)
                    }
                }
                $nextRelaunch++
                $pending = $nextRelaunch -lt $relaunches.Count
                Stop-Game $procs[$index] $roles[$index]
                if ($relaunchProblems.Count -gt 0) { break }
                $roles[$index] = New-Role $relaunch.Role $roles[$index].Loader $roles[$index].Slot
                $procs[$index] = Start-Client $scenario $roles[$index] $port
                $settleUntil = $null
                $idleUntil = $null
            }
        }
        $results = @($roles | ForEach-Object { Read-Result $_ })
        $missing = @($results | Where-Object { $null -eq $_ }).Count
        $failed = @($results | Where-Object { $null -ne $_ -and -not $_.passed }).Count
        $running = @($procs | Where-Object { -not $_.HasExited }).Count
        if ($running -eq 0) {
            if (-not $pending) { break }
            # Every game has quit but a restart is still expected: give its trigger file a while to appear.
            if ($null -eq $idleUntil) { $idleUntil = (Get-Date).AddSeconds(120) }
            if ((Get-Date) -ge $idleUntil) { break }
        }
        # A client that is gone without leaving a result (build failure, crash) will never report.
        $dead = 0
        for ($i = 0; $i -lt $procs.Count; $i++) {
            if ($procs[$i].HasExited -and $null -eq $results[$i]) { $dead++ }
        }
        if (($missing -eq 0 -and -not $pending) -or $failed -gt 0 -or $dead -gt 0) {
            # Everything reported (or something failed): give the clients a moment to quit by themselves.
            if ($null -eq $settleUntil) { $settleUntil = (Get-Date).AddSeconds(30) }
            if ((Get-Date) -ge $settleUntil) { break }
        }
        Start-Sleep -Milliseconds 500
    }
    Stop-Stragglers $procs $roles

    $problems = @($relaunchProblems)
    if ($nextRelaunch -lt $relaunches.Count) {
        $waiting = $relaunches[$nextRelaunch]
        $problems += ('{0} was never written, so the restart as {1} did not happen' -f $(if ($waiting.GameFile) { $waiting.GameFile } else { $waiting.Marker }), $waiting.Role)
    }
    foreach ($role in $roles) {
        $result = Read-Result $role
        if ($null -eq $result) {
            $problems += ('{0}: no result (timeout or crash)' -f $role.Role)
        } elseif (-not $result.passed) {
            $problems += ('{0}: {1}' -f $role.Role, $result.failure)
        }
    }
    return $problems
}

# ---- main ----------------------------------------------------------------------------------

if ($Suite -eq 'all') {
    $selected = @($suites.Keys)
} elseif ($suites.Contains($Suite) -or $measureSuites.ContainsKey($Suite)) {
    $selected = @($Suite)
} else {
    throw "Unknown suite '$Suite'. Known: all, $($suites.Keys -join ', '), $($measureSuites.Keys -join ', ')"
}

$scenarios = @()
foreach ($name in $selected) {
    if ($Online) {
        if ($onlineSuites.ContainsKey($name)) { $scenarios += $onlineSuites[$name] }
    } elseif ($suites.Contains($name)) {
        $scenarios += $suites[$name]
    } else {
        $scenarios += $measureSuites[$name]
    }
}
if ($Only) { $scenarios = @($scenarios | Where-Object { $_.Id -eq $Only }) }
if ($scenarios.Count -eq 0) { throw 'No scenarios selected.' }

$env:JAVA_HOME = Resolve-Jdk25
New-Item -ItemType Directory -Force $logDir | Out-Null
$summaryFile = Join-Path $logDir 'summary.txt'
Set-Content -Path $summaryFile -Value ("selftest -Suite {0}{1}  {2}" -f $Suite, $(if ($Online) { ' -Online' } else { '' }), (Get-Date -Format 's'))

# Compile and fetch game assets once up front: the run tasks are started concurrently and must find all of
# that up to date (two Gradle builds downloading the same assets at once fail).
$loaders = @($scenarios | Where-Object { $_.Roles } | ForEach-Object { $_.Roles } | ForEach-Object { $_.Loader } | Sort-Object -Unique)
if ($loaders.Count -gt 0) {
    $compileArgs = @($loaders | ForEach-Object { ":$($_):classes"; ":$($_):downloadAssets"; ":$($_):testmodJars" }) + '--console=plain'
    $code = Invoke-GradleLogged $compileArgs (Join-Path $logDir 'compile.log')
    if ($code -ne 0) {
        $line = 'FAIL  compile  (see build/selftest-logs/compile.log)'
        Write-Output $line
        Add-Content -Path $summaryFile -Value $line
        exit 1
    }
}

$failures = 0
foreach ($scenario in $scenarios) {
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    if ($scenario.Unit) {
        $code = Invoke-GradleLogged ($scenario.Unit + '--console=plain') (Join-Path $logDir ('{0}.log' -f $scenario.Id))
        $problems = @()
        if ($code -ne 0) { $problems += "gradle exit code $code" }
    } else {
        $problems = @(Invoke-ClientScenario $scenario)
    }
    $seconds = '{0:N1}s' -f $watch.Elapsed.TotalSeconds
    if ($problems.Count -eq 0) {
        $line = 'PASS  {0}  {1}' -f $scenario.Id, $seconds
    } else {
        $failures++
        $line = 'FAIL  {0}  {1}  {2}' -f $scenario.Id, $seconds, ($problems -join '; ')
    }
    Write-Output $line
    Add-Content -Path $summaryFile -Value $line
}

if ($failures -gt 0) { exit 1 }
exit 0

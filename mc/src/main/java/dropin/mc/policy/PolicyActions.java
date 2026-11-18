package dropin.mc.policy;

import dropin.core.mods.ModManifest;
import dropin.core.policy.Policy;
import dropin.core.world.CoopWorld;
import dropin.core.world.Member;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.host.HostSync;
import dropin.mc.menu.CoopActions;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** What the owner changes from the menu (DESIGN 4.13): each change signs a new policy version. */
public final class PolicyActions {
    private PolicyActions() {
    }

    /** The world's folder under saves/, or null if this PC has no copy. */
    public static Path folder(Minecraft mc, CoopWorld world) {
        String name = CoopActions.folders(mc).get(world.worldId());
        return name == null ? null : mc.getLevelSource().getLevelPath(name);
    }

    /** The policy this PC holds for the world, or null. */
    public static WorldPolicy.Held held(Minecraft mc, CoopWorld world) {
        HostSync hosting = HostSync.current();
        if (hosting != null && hosting.world().worldId().equals(world.worldId())) {
            return hosting.held();
        }
        return WorldPolicy.load(world, folder(mc, world));
    }

    /** The owner as this PC knows them: the member marked owner, else the player at this PC. */
    private static Member owner(Minecraft mc, CoopWorld world) throws IOException {
        CoopWorld fresh = CoopWorlds.store().get(world.worldId()).orElse(world);
        for (Member member : fresh.members()) {
            if (member.isOwner()) {
                return member;
            }
        }
        return new Member(mc.getUser().getProfileId().toString(), mc.getUser().getName(), 0, 0, Member.OWNER);
    }

    /**
     * Signs the first policy of a world, from what is on this PC now (the world must be closed).
     * Members get no commands by default; the self-tests drive the game with commands.
     */
    public static WorldPolicy.Held signFirst(Minecraft mc, CoopWorld world, Path folder, ModManifest manifest) throws IOException {
        Member owner = owner(mc, world);
        return WorldPolicy.sign(world, folder, 1, owner.uuid(), owner.name(), manifest, WorldPolicy.datapackFiles(folder),
                WorldPolicy.datapacksEnabled(folder), List.of(), List.of(), DropIn.switches().selfTest().isPresent());
    }

    /** Signs the next version with this PC's mods and datapacks (the world must be closed); bans and commands stay. */
    public static WorldPolicy.Held signUpdate(Minecraft mc, CoopWorld world, Path folder, WorldPolicy.Held previous,
                                              ModManifest manifest) throws IOException {
        Policy old = previous.policy();
        return WorldPolicy.sign(world, folder, old.version() + 1, old.ownerUuid(), old.ownerName(), manifest,
                WorldPolicy.datapackFiles(folder), WorldPolicy.datapacksEnabled(folder), old.banned(), old.admins(), old.membersMayUseCommands());
    }

    public static WorldPolicy.Held setMembersMayUseCommands(Minecraft mc, CoopWorld world, boolean allowed) throws IOException {
        return change(mc, world, null, false, allowed, null, false);
    }

    /** DESIGN 4.13: the owner makes a player an admin of the world, or takes that away. */
    public static WorldPolicy.Held setAdmin(Minecraft mc, CoopWorld world, String uuid, boolean admin) throws IOException {
        return change(mc, world, null, false, null, uuid, admin);
    }

    public static WorldPolicy.Held setBanned(Minecraft mc, CoopWorld world, String uuid, boolean banned) throws IOException {
        return change(mc, world, uuid, banned, null, null, false);
    }

    private static WorldPolicy.Held change(Minecraft mc, CoopWorld world, String uuid, boolean ban, Boolean commands,
                                           String adminUuid, boolean admin) throws IOException {
        if (!CoopWorlds.store().isOwner(world.worldId())) {
            throw new IOException("only the owner can change this");
        }
        Path folder = folder(mc, world);
        if (folder == null) {
            throw new IOException("this PC has no copy of the world");
        }
        WorldPolicy.Held previous = held(mc, world);
        if (previous == null) {
            previous = signFirst(mc, world, folder, WorldPolicy.ownerManifest());
        }
        Policy old = previous.policy();
        List<String> banned = new ArrayList<>(old.banned());
        if (uuid != null) {
            banned.remove(uuid);
            if (ban) {
                banned.add(uuid);
            }
        }
        List<String> admins = new ArrayList<>(old.admins());
        if (adminUuid != null) {
            admins.remove(adminUuid);
            if (admin && !old.isOwner(adminUuid)) {
                admins.add(adminUuid);
            }
        }
        boolean mayUse = commands == null ? old.membersMayUseCommands() : commands;
        // Mods and datapacks are taken from the signed policy, not read again: the world may be open right now.
        Map<String, String> packs = old.datapackFiles();
        WorldPolicy.Held next = WorldPolicy.sign(world, folder, old.version() + 1, old.ownerUuid(), old.ownerName(), previous.manifest(),
                packs, old.datapacksEnabled(), banned, admins, mayUse);
        HostSync hosting = HostSync.current();
        if (hosting != null && hosting.world().worldId().equals(world.worldId())) {
            hosting.adoptNewer(next);
        }
        return next;
    }
}

package dropin.mc.selftest;

import dropin.core.ledger.Ledger;
import dropin.core.ledger.LedgerEntry;
import dropin.core.manifest.Manifest;
import dropin.core.manifest.RecordKey;
import dropin.core.sync.SyncClientSession;
import dropin.core.sync.SyncClientSession.Phase;
import dropin.core.sync.SyncState;
import dropin.core.world.CoopWorldStore;
import dropin.mc.DropIn;
import dropin.mc.compat.Compat;
import dropin.mc.compat.Nbt;
import dropin.mc.handoff.HostHandoff;
import dropin.mc.handoff.MemberHandoff;
import dropin.mc.host.ChunkBytes;
import dropin.mc.host.HostSync;
import dropin.mc.member.RecordApplier;
import dropin.mc.member.SyncClient;
import dropin.mc.menu.CoopActions;
import dropin.mc.selftest.M1Scenarios.State;
import dropin.mc.selftest.Scenarios.Context;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.potion.Potion;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.MerchantRecipe;
import net.minecraft.village.MerchantRecipeList;
import net.minecraft.world.WorldServer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

import static dropin.mc.selftest.M1Scenarios.hosting;
import static dropin.mc.selftest.M1Scenarios.joinedRemote;
import static dropin.mc.selftest.M1Scenarios.pressPlay;
import static dropin.mc.selftest.M1Scenarios.require;
import static dropin.mc.selftest.M2Scenarios.copyComplete;
import static dropin.mc.selftest.M2Scenarios.folder;
import static dropin.mc.selftest.M2Scenarios.hostA;
import static dropin.mc.selftest.M2Scenarios.joinB;
import static dropin.mc.selftest.M3Scenarios.players;
import static dropin.mc.selftest.Scenarios.PEER_TICKS;
import static dropin.mc.selftest.Scenarios.SHORT_TICKS;
import static dropin.mc.selftest.Scenarios.WORLD_ID;
import static dropin.mc.selftest.Scenarios.WORLD_TICKS;
import static dropin.mc.selftest.Scenarios.failIfDisconnected;
import static dropin.mc.selftest.Scenarios.inWorld;

/**
 * World integrity (DESIGN 4.5 to 4.9): after play, and again after a host handoff, every player's copy
 * holds the same data as the host's, shown on the data itself and not only on the root hash.
 * <p>
 * Three players. A hosts; B and C get full copies. The host places ores and a filled chest, spawns a
 * villager (inventory, trades), a cow and an iron golem with set health, and gives each player health,
 * effects, experience and items. For a checkpoint the host then publishes a <i>fingerprint</i>: the hashes
 * of the watched records and, decoded from their bytes, the blocks, the chest, the mobs and the players.
 * Each member decodes the same from its own copy at the same checkpoint and must get the same text, and
 * both sides must find the values that were set in the game. A quits; B takes over from its copy and checks
 * the live game objects; B changes the world, and the comparison runs again with A and C as members. At
 * the end every closed copy is read back from disk in full and must hash to a checkpoint of the last host.
 * <p>
 * Records are read through the game's storage objects: the host's through {@code HostSync.read}, a member's
 * through the running sync's {@code RecordApplier}; a closed copy through {@code RecordApplier.scan}.
 * Development only.
 * <p>
 * 1.12.2 port: there is only the {@code region} kind; the mobs are in the chunk record itself
 * ({@code Level.Entities}), so the watched chunk is one record (26.x/1.21.1 also had an {@code entities/} record).
 * Blocks and items that 1.12.2 lacks are replaced by ones it has (deepslate emerald ore by emerald ore, ancient
 * debris by gold ore, raw gold by gold ore, netherite ingot by a nether star, amethyst shard by a prismarine
 * shard). Effects cannot be infinite in 1.12.2: they last 1,000,000 seconds and the fingerprint says "long".
 * There is no {@code /forceload}: the watched chunk is a spawn chunk, which the overworld never unloads
 * ({@code WorldProviderSurface.canDropChunk}); the host checks that.
 */
final class IntegrityScenarios {
    private static final Map<String, String> HANDOFF_ON = Map.of("dropin.dev.noHandoff", "false");
    private static final String DIM = "minecraft:overworld";
    /** The watched chunk, x and z 16 to 31: nobody stands in it; it is a spawn chunk, so it stays loaded. */
    private static final String REGION = RecordKey.chunk(DIM, "region", 1, 1);
    private static final int ORE_Y = 100;
    /** 1.12.2 port: the mobs float at this height (NoAI mobs do not move in 1.12.2), clear of the ores and the chest. */
    private static final double MOB_Y = 100.0;
    /** An effect longer than this many ticks is printed as "long": 1.12.2 has no infinite effects. */
    private static final int LONG_EFFECT_TICKS = 1_000_000;
    private static final UUID VILLAGER = UUID.fromString("d0d0d0d0-0001-4000-8000-000000000001");
    private static final UUID COW = UUID.fromString("d0d0d0d0-0002-4000-8000-000000000002");
    private static final UUID GOLEM = UUID.fromString("d0d0d0d0-0003-4000-8000-000000000003");
    private static final String[] WHO = {"a", "b", "c"};
    private static final M3Scenarios.Mark MARK_C = new M3Scenarios.Mark("DevC", Items.GOLD_INGOT, "minecraft:gold_ingot", 60, -100);

    /** A record the game has rewritten since the checkpoint that is being compared. */
    private static final class Moved extends IOException {
        Moved(String key) {
            super(key + " changed since the checkpoint");
        }
    }

    private IntegrityScenarios() {
    }

    static StepRunner build(String key, Context ctx) {
        return switch (key) {
            case "integrity:a" -> integrityA(ctx);
            case "integrity:b" -> integrityB(ctx);
            case "integrity:c" -> integrityC(ctx);
            default -> null;
        };
    }

    // ---- what the host does to the world ----------------------------------------------------------

    /** 1.12.2 port: the server itself is the command sender (26.x performPrefixedCommand with a server source). */
    private static void run(MinecraftServer server, String command) {
        server.getCommandManager().executeCommand(server, command);
    }

    /** On the server thread: removes any mob with this id, then adds a fresh one, still and persistent. */
    private static <T extends EntityLiving> T spawn(MinecraftServer server, WorldServer level, T mob, UUID id, double x, double z,
                                                    float health, Consumer<T> prepare) {
        Entity old = Compat.entityInAnyDimension(server, id);
        if (old != null) {
            // 1.12.2 port: setDead() leaves the id in WorldServer.entitiesByUuid until the next tick, and
            // canAddEntity refuses a second entity with that id; removeEntityDangerously drops it at once.
            old.world.removeEntityDangerously(old);
        }
        mob.setUniqueId(id);
        mob.setLocationAndAngles(x, MOB_Y, z, 0.0f, 0.0f);
        mob.setNoAI(true);
        mob.enablePersistence();
        mob.setHealth(health);
        prepare.accept(mob);
        require(level.spawnEntity(mob), "could not add " + mob.getClass().getSimpleName());
        return mob;
    }

    private static int count(IInventory inventory, Item item) {
        int count = 0;
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static Block blockOf(WorldServer level, int x, int y, int z) {
        return level.getBlockState(new BlockPos(x, y, z)).getBlock();
    }

    /** Round 1, on the server thread of A: ores, a chest, three mobs, and each player's own state. */
    private static void play1(MinecraftServer server) {
        WorldServer level = server.getWorld(0);
        // 1.12.2 port: stands in for "/forceload add 16 16": spawn chunks are never unloaded in the overworld.
        require(level.isSpawnChunk(1, 1), "the watched chunk 1,1 is not a spawn chunk of this world, so 1.12.2 may unload it "
                + "(there is no /forceload); spawn is at " + level.getSpawnPoint());
        run(server, "setblock 20 100 20 minecraft:diamond_ore");
        run(server, "setblock 21 100 20 minecraft:emerald_ore");
        run(server, "setblock 22 100 20 minecraft:gold_ore");
        run(server, "fill 24 100 20 27 103 23 minecraft:iron_ore");
        run(server, "setblock 20 101 21 minecraft:chest 0 replace {Items:[{Slot:0b,id:\"minecraft:gold_ore\",Count:17b},"
                + "{Slot:5b,id:\"minecraft:redstone\",Count:40b}]}");
        EntityVillager villager = spawn(server, level, new EntityVillager(level), VILLAGER, 20.5, 24.5, 13.0f, v -> {
            // 1.12.2 port: EntityVillager.setRecipes is client-only; the offers go in through the villager's own NBT.
            MerchantRecipeList offers = new MerchantRecipeList();
            offers.add(new MerchantRecipe(new ItemStack(Items.EMERALD, 3), ItemStack.EMPTY, new ItemStack(Items.DIAMOND, 1), 2, 7));
            offers.add(new MerchantRecipe(new ItemStack(Items.WHEAT, 20), ItemStack.EMPTY, new ItemStack(Items.EMERALD, 1), 0, 16));
            NBTTagCompound tag = v.writeToNBT(new NBTTagCompound());
            tag.setTag("Offers", offers.getRecipiesAsTags());
            v.readFromNBT(tag);
        });
        villager.getVillagerInventory().addItem(new ItemStack(Items.BREAD, 5));
        villager.getVillagerInventory().addItem(new ItemStack(Items.WHEAT_SEEDS, 12));
        spawn(server, level, new EntityCow(level), COW, 24.5, 24.5, 4.0f, cow -> {
        });
        spawn(server, level, new EntityIronGolem(level), GOLEM, 28.5, 28.5, 37.0f, golem -> {
        });
        run(server, "effect DevA minecraft:speed 1000000 1");
        run(server, "give DevA minecraft:nether_star 2");
        // 1.12.2 port: no /attribute command; the base value is set as the command would set it.
        EntityPlayerMP b = server.getPlayerList().getPlayerByUsername("DevB");
        require(b != null, "DevB is not on the server");
        b.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(14.0);
        b.setHealth(14.0f);
        run(server, "effect DevB minecraft:strength 1000000 0");
        run(server, "effect DevC minecraft:haste 1000000 2");
        setLevel(server, "DevC", 7);
    }

    /** 1.12.2 port: "/xp set" does not exist ("/xp" only adds); the level is set through the game's own call. */
    private static void setLevel(MinecraftServer server, String name, int level) {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUsername(name);
        require(player != null, name + " is not on the server");
        player.addExperienceLevel(level - player.experienceLevel);
    }

    /** Round 2, on the server thread of B, the new host: the world goes on changing. */
    private static void play2(MinecraftServer server) {
        run(server, "setblock 23 100 20 minecraft:emerald_block");
        run(server, "setblock 21 100 20 minecraft:air");
        ((EntityLivingBase) Compat.entityInAnyDimension(server, COW)).setHealth(3.0f);
        ((EntityVillager) Compat.entityInAnyDimension(server, VILLAGER)).getVillagerInventory().addItem(new ItemStack(Items.CARROT, 9));
        run(server, "give DevA minecraft:prismarine_shard 6");
        setLevel(server, "DevC", 9);
    }

    /** What must be found in a fingerprint of that round, wherever it was computed. */
    private static List<String> expected(int round) {
        List<String> lines = new ArrayList<>(List.of(
                "block 20 100 20 = minecraft:diamond_ore",
                "block 22 100 20 = minecraft:gold_ore",
                "block 25 102 22 = minecraft:iron_ore",
                "count minecraft:iron_ore = 64",
                "count minecraft:diamond_ore = 1",
                "chest 20 101 21 = minecraft:gold_ore x17 @0, minecraft:redstone x40 @5",
                "villager pos = 20.50 100.00 24.50",
                "villager health = 13.0",
                // 1.12.2 port: MerchantRecipe has no xp amount, only whether the trade rewards xp.
                "villager offers = minecraft:emerald x3 -> minecraft:diamond x1 uses 2/7 xp true; "
                        + "minecraft:wheat x20 -> minecraft:emerald x1 uses 0/16 xp true",
                "golem health = 37.0",
                "player b health = 14.0",
                "player b effects = minecraft:strength amp 0 duration long",
                "player c effects = minecraft:haste amp 2 duration long",
                "player a effects = minecraft:speed amp 1 duration long"));
        if (round == 1) {
            lines.add("block 21 100 20 = minecraft:emerald_ore");
            lines.add("block 23 100 20 = minecraft:air");
            lines.add("villager inventory = minecraft:bread x5, minecraft:wheat_seeds x12");
            lines.add("cow health = 4.0");
            lines.add("player c xp level = 7");
        } else {
            lines.add("block 21 100 20 = minecraft:air");
            lines.add("block 23 100 20 = minecraft:emerald_block");
            lines.add("villager inventory = minecraft:bread x5, minecraft:wheat_seeds x12, minecraft:carrot x9");
            lines.add("cow health = 3.0");
            lines.add("player c xp level = 9");
        }
        return lines;
    }

    /** The same facts, asked of the running game on the host (the authority), on its server thread. */
    private static String liveProblem(MinecraftServer server, int round) {
        WorldServer level = server.getWorld(0);
        if (blockOf(level, 20, ORE_Y, 20) != Blocks.DIAMOND_ORE
                || blockOf(level, 25, 102, 22) != Blocks.IRON_ORE
                || blockOf(level, 20, 101, 21) != Blocks.CHEST) {
            return "the ores or the chest are not in the world";
        }
        if (round == 2 && blockOf(level, 23, ORE_Y, 20) != Blocks.EMERALD_BLOCK) {
            return "the new host's block is not in the world";
        }
        if (!(Compat.entityInAnyDimension(server, VILLAGER) instanceof EntityVillager villager)) {
            return "the villager is not in the world";
        }
        if (Math.abs(villager.posX - 20.5) > 0.01 || Math.abs(villager.posZ - 24.5) > 0.01 || villager.getHealth() != 13.0f) {
            return "the villager is at " + villager.getPositionVector() + " with health " + villager.getHealth();
        }
        IInventory inventory = villager.getVillagerInventory();
        if (count(inventory, Items.BREAD) != 5 || count(inventory, Items.WHEAT_SEEDS) != 12
                || count(inventory, Items.CARROT) != (round == 1 ? 0 : 9)) {
            return "the villager's inventory is " + inventory;
        }
        MerchantRecipeList offers = villager.getRecipes(null);
        if (offers == null || offers.size() != 2 || offers.get(0).getItemToSell().getItem() != Items.DIAMOND
                || offers.get(0).getToolUses() != 2 || offers.get(0).getMaxTradeUses() != 7
                || offers.get(1).getItemToSell().getItem() != Items.EMERALD) {
            return "the villager has " + (offers == null ? 0 : offers.size()) + " offers, or not the ones that were set";
        }
        if (!(Compat.entityInAnyDimension(server, COW) instanceof EntityLivingBase cow) || cow.getHealth() != (round == 1 ? 4.0f : 3.0f)) {
            return "the cow is gone or has the wrong health";
        }
        if (!(Compat.entityInAnyDimension(server, GOLEM) instanceof EntityLivingBase golem) || golem.getHealth() != 37.0f) {
            return "the iron golem is gone or has the wrong health";
        }
        EntityPlayerMP a = server.getPlayerList().getPlayerByUsername("DevA");
        EntityPlayerMP b = server.getPlayerList().getPlayerByUsername("DevB");
        EntityPlayerMP c = server.getPlayerList().getPlayerByUsername("DevC");
        if (a == null || b == null || c == null) {
            return "not all three players are in the world";
        }
        if (b.getHealth() != 14.0f || b.getMaxHealth() != 14.0f) {
            return "B has health " + b.getHealth() + " of " + b.getMaxHealth();
        }
        if (a.getActivePotionEffects().size() != 1 || b.getActivePotionEffects().size() != 1 || c.getActivePotionEffects().size() != 1) {
            return "the players' effects are " + a.getActivePotionEffects() + " " + b.getActivePotionEffects() + " "
                    + c.getActivePotionEffects();
        }
        if (c.experienceLevel != (round == 1 ? 7 : 9)) {
            return "C has experience level " + c.experienceLevel;
        }
        if (!a.inventory.hasItemStack(new ItemStack(Items.NETHER_STAR))
                || !M3Scenarios.marked(a, M3Scenarios.MARK_A) || !M3Scenarios.marked(b, M3Scenarios.MARK_B) || !M3Scenarios.marked(c, MARK_C)) {
            return "a player's items or place are not as they were";
        }
        return null;
    }

    private static String liveOn(Minecraft mc, int round) throws Exception {
        IntegratedServer server = mc.getIntegratedServer();
        if (server == null) {
            return "no server";
        }
        // 1.12.2 port: callFromMainThread is 26.x server.submit(supplier).
        return server.callFromMainThread(() -> {
            String problem = liveProblem(server, round);
            return problem == null ? "" : problem;
        }).get();
    }

    // ---- the fingerprint ----------------------------------------------------------------------------

    /** Reads a record of one copy; null if the copy has none. */
    private interface Reader {
        byte[] read(String key) throws IOException;
    }

    private static String playerKey(Context ctx, String who) throws IOException {
        return RecordKey.file("playerdata/" + ctx.readShared(who + "-uuid") + ".dat");
    }

    /**
     * The watched records of one copy as text: their hashes, and what their bytes say about blocks, the
     * chest, the three mobs and the three players. Two copies that hold the same data give the same text.
     */
    private static String fingerprint(Context ctx, Reader reader) throws IOException {
        StringBuilder out = new StringBuilder();
        byte[] region = reader.read(REGION);
        if (region == null) {
            throw new IOException("the watched chunk is not in this copy");
        }
        out.append("record ").append(REGION).append(" = ").append(Manifest.hash(region)).append('\n');

        // 1.12.2 chunk NBT: {DataVersion, Level:{Sections, Entities, TileEntities, ...}}.
        NBTTagCompound chunk = Nbt.compoundOrEmpty(ChunkBytes.parse(region), "Level");
        for (int[] pos : new int[][]{{20, ORE_Y, 20}, {21, ORE_Y, 20}, {22, ORE_Y, 20}, {23, ORE_Y, 20}, {25, 102, 22}}) {
            out.append("block ").append(pos[0]).append(' ').append(pos[1]).append(' ').append(pos[2]).append(" = ")
                    .append(blockAt(chunk, pos[0], pos[1], pos[2])).append('\n');
        }
        TreeMap<String, Integer> counts = new TreeMap<>();
        for (int y = ORE_Y; y < ORE_Y + 4; y++) {
            for (int z = 16; z < 32; z++) {
                for (int x = 16; x < 32; x++) {
                    counts.merge(blockAt(chunk, x, y, z), 1, Integer::sum);
                }
            }
        }
        counts.forEach((name, count) -> out.append("count ").append(name).append(" = ").append(count).append('\n'));
        for (NBTTagCompound blockEntity : Nbt.compounds(Nbt.listOrEmpty(chunk, "TileEntities")).toList()) {
            out.append("chest ").append(Nbt.intOr(blockEntity, "x", 0)).append(' ').append(Nbt.intOr(blockEntity, "y", 0)).append(' ')
                    .append(Nbt.intOr(blockEntity, "z", 0)).append(" = ").append(items(Nbt.listOrEmpty(blockEntity, "Items"))).append('\n');
        }

        mob(out, "villager", chunk, VILLAGER);
        mob(out, "cow", chunk, COW);
        mob(out, "golem", chunk, GOLEM);

        for (String who : WHO) {
            String key = playerKey(ctx, who);
            byte[] file = reader.read(key);
            if (file == null) {
                throw new IOException("player " + who + " has no data file in this copy");
            }
            out.append("record ").append(key).append(" = ").append(Manifest.hash(file)).append('\n');
            NBTTagCompound player = CompressedStreamTools.readCompressed(new ByteArrayInputStream(file));
            String p = "player " + who + ' ';
            out.append(p).append("health = ").append(Nbt.floatOr(player, "Health", -1f)).append('\n');
            out.append(p).append("pos = ").append(pos(Nbt.listOrEmpty(player, "Pos"))).append('\n');
            out.append(p).append("xp level = ").append(Nbt.intOr(player, "XpLevel", 0)).append('\n');
            out.append(p).append("effects = ").append(effects(Nbt.listOrEmpty(player, "ActiveEffects"))).append('\n');
            out.append(p).append("inventory = ").append(items(Nbt.listOrEmpty(player, "Inventory"))).append('\n');
            out.append(p).append("whole = ").append(Manifest.hash(ChunkBytes.canonical(player))).append('\n');
        }
        return out.toString();
    }

    private static void mob(StringBuilder out, String name, NBTTagCompound chunk, UUID id) {
        NBTTagCompound found = null;
        for (NBTTagCompound entity : Nbt.compounds(Nbt.listOrEmpty(chunk, "Entities")).toList()) {
            // 1.12.2 entity NBT keeps the id as two longs.
            if (Nbt.longOr(entity, "UUIDMost", 0L) == id.getMostSignificantBits()
                    && Nbt.longOr(entity, "UUIDLeast", 0L) == id.getLeastSignificantBits()) {
                found = entity;
            }
        }
        if (found == null) {
            out.append(name).append(" missing\n");
            return;
        }
        out.append(name).append(" pos = ").append(pos(Nbt.listOrEmpty(found, "Pos"))).append('\n');
        out.append(name).append(" health = ").append(Nbt.floatOr(found, "Health", -1f)).append('\n');
        if (found.hasKey("Inventory")) {
            out.append(name).append(" inventory = ").append(items(Nbt.listOrEmpty(found, "Inventory"))).append('\n');
        }
        if (found.hasKey("Offers")) {
            List<String> offers = new ArrayList<>();
            for (NBTTagCompound offer : Nbt.compounds(Nbt.listOrEmpty(found.getCompoundTag("Offers"), "Recipes")).toList()) {
                offers.add(item(offer.getCompoundTag("buy")) + " -> " + item(offer.getCompoundTag("sell")) + " uses "
                        + Nbt.intOr(offer, "uses", 0) + "/" + Nbt.intOr(offer, "maxUses", 0) + " xp " + (Nbt.byteOr(offer, "rewardExp", (byte) 0) != 0));
            }
            out.append(name).append(" offers = ").append(String.join("; ", offers)).append('\n');
        }
        // Every other field of the mob as well: the hash of its whole data.
        out.append(name).append(" whole = ").append(Manifest.hash(ChunkBytes.canonical(found))).append('\n');
    }

    private static String item(NBTTagCompound stack) {
        int damage = Nbt.intOr(stack, "Damage", 0);
        return Nbt.stringOr(stack, "id", "?") + (damage != 0 ? ":" + damage : "") + " x" + Nbt.intOr(stack, "Count", 1);
    }

    private static String items(NBTTagList list) {
        List<String> parts = new ArrayList<>();
        for (NBTTagCompound stack : Nbt.compounds(list).toList()) {
            parts.add(item(stack) + (stack.hasKey("Slot") ? " @" + Nbt.byteOr(stack, "Slot", (byte) 0) : ""));
        }
        return String.join(", ", parts);
    }

    private static String effects(NBTTagList list) {
        List<String> parts = new ArrayList<>();
        for (NBTTagCompound effect : Nbt.compounds(list).toList()) {
            // 1.12.2 PotionEffect NBT: Id (byte, the registry number), Amplifier, Duration (ticks).
            Potion potion = Potion.getPotionById(Nbt.byteOr(effect, "Id", (byte) 0) & 0xFF);
            String id = potion == null ? "?" : String.valueOf(Potion.REGISTRY.getNameForObject(potion));
            int duration = Nbt.intOr(effect, "Duration", 0);
            parts.add(id + " amp " + Nbt.byteOr(effect, "Amplifier", (byte) 0) + " duration "
                    + (duration > LONG_EFFECT_TICKS ? "long" : Integer.toString(duration)));
        }
        return String.join(", ", parts);
    }

    private static String pos(NBTTagList pos) {
        return String.format(Locale.ROOT, "%.2f %.2f %.2f", Nbt.doubleOr(pos, 0, 0), Nbt.doubleOr(pos, 1, 0), Nbt.doubleOr(pos, 2, 0));
    }

    /**
     * The block at a world position, decoded from a chunk's saved sections. 1.12.2 layout
     * (AnvilChunkLoader / BlockStateContainer.getDataForNBT): index y<<8|z<<4|x; the state id is
     * (Add<<12 | Blocks<<4 | Data), the block id its upper bits and the metadata its low nibble.
     */
    private static String blockAt(NBTTagCompound level, int x, int y, int z) {
        for (NBTTagCompound section : Nbt.compounds(Nbt.listOrEmpty(level, "Sections")).toList()) {
            if (Nbt.byteOr(section, "Y", (byte) 99) != (byte) (y >> 4)) {
                continue;
            }
            byte[] blocks = section.getByteArray("Blocks");
            byte[] data = section.getByteArray("Data");
            byte[] add = section.getByteArray("Add");
            int index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
            if (blocks.length != 4096) {
                return "?";
            }
            int id = blocks[index] & 0xFF;
            if (add.length == 2048) {
                id |= nibble(add, index) << 8;
            }
            int meta = data.length == 2048 ? nibble(data, index) : 0;
            Block block = Block.getBlockById(id);
            String name = String.valueOf(Block.REGISTRY.getNameForObject(block));
            return meta == 0 ? name : name + "[" + meta + "]";
        }
        return "minecraft:air";
    }

    /** NibbleArray.getFromIndex: the low nibble at an even index, the high one at an odd index. */
    private static int nibble(byte[] array, int index) {
        int value = array[index >> 1];
        return (index & 1) == 0 ? value & 15 : (value >> 4) & 15;
    }

    private static String firstDifference(String mine, String theirs) {
        String[] a = mine.trim().split("\n");
        String[] b = theirs.trim().split("\n");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String left = i < a.length ? a[i] : "(nothing)";
            String right = i < b.length ? b[i] : "(nothing)";
            if (!left.equals(right)) {
                return "this copy says '" + left + "', the host says '" + right + "'";
            }
        }
        return "no difference";
    }

    private static void requireExpected(String fingerprint, int round, String whose) {
        List<String> lines = List.of(fingerprint.split("\n"));
        for (String line : expected(round)) {
            require(lines.contains(line), whose + " does not hold '" + line + "' in round " + round + ":\n" + fingerprint);
        }
    }

    // ---- host and member halves of one comparison ------------------------------------------------------

    /**
     * The host: for every checkpoint from {@code fromSeq[0]} on, publishes the fingerprint of its records as they
     * were at that checkpoint, until every member in {@code members} confirmed one.
     */
    private static StepRunner publish(StepRunner runner, Context ctx, int round, long[] fromSeq, String... members) {
        Minecraft mc = ctx.mc();
        int[] ticks = {0};
        long[] published = {0};
        return runner.step("round " + round + ": the members' copies match the host's", 2 * PEER_TICKS, () -> {
            boolean all = true;
            for (String member : members) {
                all &= ctx.sharedExists("fp" + round + "-ok-" + member);
            }
            if (all) {
                return true;
            }
            HostSync sync = HostSync.current();
            require(sync != null && mc.getIntegratedServer() != null, "the host session ended");
            if (ticks[0]++ % 10 != 0) {
                return false;
            }
            long seq = sync.seq();
            if (seq < fromSeq[0] || seq == published[0]) {
                return false;
            }
            Manifest at = sync.manifestSnapshot();
            String root = at.root();
            String text;
            try {
                text = fingerprint(ctx, key -> {
                    byte[] bytes = sync.read(key);
                    // Only the bytes the checkpoint hashed: a record the game has rewritten since is not compared.
                    if (bytes == null || !Manifest.hash(bytes).equals(at.get(key))) {
                        throw new Moved(key);
                    }
                    return bytes;
                });
            } catch (Moved e) {
                return false;
            }
            if (sync.seq() != seq) {
                return false;
            }
            requireExpected(text, round, "the host's storage");
            ctx.writeShared("fp" + round + "-" + seq, "root " + root + "\n" + text);
            published[0] = seq;
            DropIn.LOGGER.info("Self-test integrity: round {}, host fingerprint at checkpoint {} (root {}):\n{}", round, seq, root, text);
            return false;
        });
    }

    /** A member: at a checkpoint the host published, its own copy must give the same fingerprint. */
    private static StepRunner match(StepRunner runner, Context ctx, State state, int round, String who) {
        Minecraft mc = ctx.mc();
        int[] ticks = {0};
        return runner.step("round " + round + ": this copy matches the host's", 2 * PEER_TICKS, () -> {
            failIfDisconnected(mc);
            if (ticks[0]++ % 10 != 0) {
                return false;
            }
            String id = state.world.worldId();
            Path folder = folder(mc, state);
            SyncClientSession.Status before = SyncClient.status(id);
            if (folder == null || before == null || before.phase() != Phase.LIVE) {
                return false;
            }
            long seq = SyncClient.savedState(folder).syncedThrough();
            if (!ctx.sharedExists("fp" + round + "-" + seq)) {
                return false;
            }
            String text = null;
            String failure = null;
            try {
                text = fingerprint(ctx, key -> RecordKey.isChunk(key) ? SyncClient.localChunk(id, key)
                        : readFile(folder.resolve(RecordKey.filePath(key))));
            } catch (IOException | RuntimeException e) {
                failure = e.toString();
            }
            SyncClientSession.Status after = SyncClient.status(id);
            if (after == null || after.phase() != Phase.LIVE || SyncClient.savedState(folder).syncedThrough() != seq) {
                // The next checkpoint started to arrive while this copy was being read: look again.
                return false;
            }
            require(text != null, "at checkpoint " + seq + " the copy of " + who + " could not be read: " + failure);
            String[] host = ctx.readShared("fp" + round + "-" + seq).split("\n", 2);
            require(text.trim().equals(host[1].trim()), "at checkpoint " + seq + " the copy of " + who + " differs from the host's: "
                    + firstDifference(text, host[1]));
            requireExpected(text, round, "the copy of " + who);
            String recorded = Manifest.load(folder.resolve(CoopWorldStore.MARKER_DIR).resolve("manifest").resolve("current.tsv")).root();
            require(("root " + recorded).equals(host[0]), "at checkpoint " + seq + " the copy of " + who + " has root " + recorded
                    + ", the host published '" + host[0] + "'");
            DropIn.LOGGER.info("Self-test integrity: round {}, the copy of {} equals the host's at checkpoint {} ({}, {} lines)",
                    round, who, seq, host[0], text.split("\n").length);
            ctx.writeShared("fp" + round + "-ok-" + who, Long.toString(seq));
            return true;
        });
    }

    private static byte[] readFile(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
    }

    /**
     * A member leaves right after a checkpoint was committed (the next one is seconds away), then reads its
     * closed copy back from disk in full: it must hash to the manifest it recorded, and that must be the
     * root of the checkpoint it says it is at. Reported as {@code <who>-final}.
     */
    private static StepRunner leaveAndRescan(StepRunner runner, Context ctx, State state, String who) {
        Minecraft mc = ctx.mc();
        long[] seen = {-1};
        return runner
                .step("a checkpoint was just committed", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    Path folder = folder(mc, state);
                    SyncClientSession.Status live = SyncClient.status(state.world.worldId());
                    if (folder == null || live == null || live.phase() != Phase.LIVE) {
                        return false;
                    }
                    long seq = SyncClient.savedState(folder).syncedThrough();
                    if (seen[0] < 0) {
                        seen[0] = seq;
                    }
                    if (seq == seen[0]) {
                        return false;
                    }
                    Compat.disconnectFromWorld(mc, Compat.DEFAULT_QUIT_MESSAGE);
                    return true;
                })
                .step("left", SHORT_TICKS, () -> mc.world == null)
                .action("the closed copy, read back in full", () -> {
                    mc.displayGuiScreen(new GuiMainMenu());
                    Path folder = folder(mc, state);
                    SyncClient.stopAndWait(state.world.worldId(), 15_000);
                    require(SyncClient.status(state.world.worldId()) == null, "the sync did not let go of the copy");
                    SyncState saved = SyncClient.savedState(folder);
                    Path dropin = folder.resolve(CoopWorldStore.MARKER_DIR);
                    Manifest recorded = Manifest.load(dropin.resolve("manifest").resolve("current.tsv"));
                    Manifest onDisk = RecordApplier.scan(folder);
                    require(onDisk.root().equals(recorded.root()), "the files of " + who + " hash to root " + onDisk.root()
                            + ", its manifest says " + recorded.root() + "; records that differ: " + differing(onDisk, recorded));
                    LedgerEntry last = Ledger.load(state.world.keys().auth(), dropin.resolve("ledger.jsonl")).last();
                    require(last != null && last.seq() == saved.syncedThrough(), "the ledger of " + who + " ends at "
                            + (last == null ? "nothing" : last.seq()) + ", its state says " + saved.syncedThrough());
                    require(saved.complete() && last.root().equals(onDisk.root()), "the copy of " + who
                            + " is not the checkpoint " + last.seq() + " it says it is at");
                    DropIn.LOGGER.info("Self-test integrity: the closed copy of {} ({} records) read back from disk is checkpoint {} "
                            + "(root {})", who, onDisk.size(), last.seq(), last.root());
                    ctx.writeShared(who + "-final", last.seq() + " " + last.root() + " " + last.hash());
                });
    }

    /** A chunk nobody touches in this scenario (A stands in it): shows what a shutdown alone rewrites. */
    private static final String SAMPLE = RecordKey.chunk("minecraft:overworld", "region", 6, 6);

    /** How many keys two manifests disagree on, and up to twelve of them. */
    private static String differing(Manifest onDisk, Manifest recorded) {
        Manifest.Diff diff = Manifest.diff(recorded, onDisk);
        List<String> keys = new ArrayList<>(diff.fetch().keySet());
        keys.addAll(diff.delete());
        return keys.size() + " records " + keys.subList(0, Math.min(12, keys.size()));
    }

    /** After this game stopped hosting: what its closed world holds beyond its last checkpoint. Logged, not judged. */
    private static void logLeftover(Path folder, String who) throws IOException {
        logLeftover(folder, who, null, null);
    }

    /** As above; {@code before} is what the host's storage held for {@code sample} shortly before it quit. */
    private static void logLeftover(Path folder, String who, String sample, byte[] before) throws IOException {
        Manifest recorded = Manifest.load(folder.resolve(CoopWorldStore.MARKER_DIR).resolve("manifest").resolve("current.tsv"));
        Manifest onDisk = RecordApplier.scan(folder);
        Manifest.Diff diff = Manifest.diff(recorded, onDisk);
        int added = 0;
        TreeMap<String, Integer> changed = new TreeMap<>();
        for (String key : diff.fetch().keySet()) {
            if (recorded.get(key) == null) {
                added++;
            } else {
                changed.merge(RecordKey.isFile(key) ? "file" : RecordKey.parseChunk(key).kind(), 1, Integer::sum);
            }
        }
        DropIn.LOGGER.info("Self-test integrity: after {} stopped hosting, its closed world differs from its last checkpoint in {}"
                + " (changed by kind {}, not in the checkpoint {}, gone {})", who, differing(onDisk, recorded), changed, added,
                diff.delete().size());
        if (before == null) {
            return;
        }
        RecordApplier applier = new RecordApplier(folder);
        try {
            byte[] after = applier.read(sample);
            if (after == null) {
                DropIn.LOGGER.info("Self-test integrity: {} is not in {}'s closed world", sample, who);
                return;
            }
            // 1.12.2 port: the fields are those of the chunk's Level compound (the root holds only DataVersion and Level).
            NBTTagCompound wasRoot = ChunkBytes.parse(before);
            NBTTagCompound isRoot = ChunkBytes.parse(after);
            NBTTagCompound was = Nbt.compoundOrEmpty(wasRoot, "Level");
            NBTTagCompound is = Nbt.compoundOrEmpty(isRoot, "Level");
            TreeSet<String> names = new TreeSet<>(was.getKeySet());
            names.addAll(is.getKeySet());
            StringBuilder out = new StringBuilder();
            if (!java.util.Objects.equals(wasRoot.getTag("DataVersion"), isRoot.getTag("DataVersion"))) {
                out.append(" DataVersion");
            }
            for (String name : names) {
                NBTBase a = was.getTag(name);
                NBTBase b = is.getTag(name);
                if (!java.util.Objects.equals(a, b)) {
                    String left = String.valueOf(a);
                    String right = String.valueOf(b);
                    out.append(' ').append(name).append(left.length() + right.length() < 60 ? "(" + left + " -> " + right + ")" : "");
                }
            }
            DropIn.LOGGER.info("Self-test integrity: {} before {} quit and in its closed world: {}", sample, who,
                    out.length() == 0 ? " the same" : "fields that differ:" + out);
        } finally {
            applier.close();
        }
    }

    // ---- the three players ----------------------------------------------------------------------------

    private static String myUuid(Minecraft mc) {
        return mc.getSession().getProfile().getId().toString();
    }

    private static StepRunner integrityA(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        long[] from = {Long.MAX_VALUE};
        String[] problem = {""};
        byte[][] sampleBefore = {null};
        StepRunner runner = hostA(ctx, state, HANDOFF_ON, 0)
                .action("who A is", () -> ctx.writeShared("a-uuid", myUuid(mc)))
                .step("B and C dropped in", 2 * PEER_TICKS, () -> players(mc) == 3)
                .step("B and C have full copies and their marks", 2 * PEER_TICKS,
                        () -> ctx.sharedExists("b-marked") && ctx.sharedExists("c-marked"));
        runner = M3Scenarios.mark(runner, mc, M3Scenarios.MARK_A)
                .action("play: ores, a chest, mobs, player states", () -> {
                    IntegratedServer server = mc.getIntegratedServer();
                    server.callFromMainThread(() -> {
                        play1(server);
                        return null;
                    }).get();
                    // The checkpoint that is under way may have saved before this; the one after it has not.
                    from[0] = HostSync.current().seq() + 2;
                })
                .step("the running game has all of it", SHORT_TICKS, () -> {
                    problem[0] = liveOn(mc, 1);
                    return problem[0].isEmpty();
                });
        runner = publish(runner, ctx, 1, from, "b", "c")
                .action("A quits", () -> {
                    sampleBefore[0] = HostSync.current().read(SAMPLE);
                    Compat.disconnectFromWorld(mc, Compat.DEFAULT_QUIT_MESSAGE);
                })
                .step("the handoff ran and the world closed", WORLD_TICKS + SHORT_TICKS,
                        () -> mc.getIntegratedServer() == null && mc.world == null && HostHandoff.state() == HostHandoff.State.IDLE)
                .action("the world went to B", () -> {
                    String b = ctx.readShared("b-uuid");
                    require(b.equals(HostSync.lastHandedTo()), "A handed the world to '" + HostSync.lastHandedTo() + "', expected B " + b);
                    Path world = CoopActions.levelPath(mc, WORLD_ID);
                    LedgerEntry last = Ledger.load(state.world.keys().auth(), world.resolve(CoopWorldStore.MARKER_DIR).resolve("ledger.jsonl")).last();
                    require(last != null && last.root().equals(HostSync.lastFinalRoot()), "the ledger does not end on the handed-over root");
                    ctx.writeShared("a-root", last.seq() + " " + last.root());
                    logLeftover(world, "A", SAMPLE, sampleBefore[0]);
                    mc.displayGuiScreen(new GuiMainMenu());
                })
                .step("B hosts", WORLD_TICKS + PEER_TICKS, () -> ctx.sharedExists("b-hosting"))
                .action("press Play", () -> pressPlay(mc, state))
                .step("landed in B's session", PEER_TICKS, () -> joinedRemote(mc));
        runner = match(runner, ctx, state, 2, "a")
                .step("B is done", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists("b-done");
                });
        return leaveAndRescan(runner, ctx, state, "a");
    }

    private static StepRunner integrityB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        long[] from = {Long.MAX_VALUE};
        String[] problem = {""};
        StepRunner runner = joinB(ctx, state, HANDOFF_ON)
                .step("copy complete", 2 * PEER_TICKS, copyComplete(mc, state));
        runner = M3Scenarios.mark(runner, mc, M3Scenarios.MARK_B)
                .action("tell A", () -> {
                    // The clients of one run share a LAN port; B needs its own when it takes over (see handoff-3).
                    try (java.net.ServerSocket free = new java.net.ServerSocket(0)) {
                        System.setProperty("dropin.dev.lanPort", Integer.toString(free.getLocalPort()));
                    }
                    ctx.writeShared("b-uuid", myUuid(mc));
                    ctx.writeShared("b-marked", "ok");
                });
        runner = match(runner, ctx, state, 1, "b")
                // No input from here on: A quits, and this game must end up hosting its own copy.
                .step("took over as host", 3 * PEER_TICKS, () -> MemberHandoff.lastRole() == MemberHandoff.Role.SUCCESSOR && hosting(ctx))
                .step("A reported its root", WORLD_TICKS, () -> ctx.sharedExists("a-root"))
                .action("the root at takeover is A's final root", () -> {
                    String[] reported = ctx.readShared("a-root").split(" ");
                    require(MemberHandoff.takeoverSeq() == Long.parseLong(reported[0]) && MemberHandoff.takeoverRoot().equals(reported[1]),
                            "B took over at checkpoint " + MemberHandoff.takeoverSeq() + " root " + MemberHandoff.takeoverRoot()
                                    + ", A ended on " + reported[0] + " root " + reported[1]);
                    ctx.writeShared("b-hosting", "ok");
                })
                .step("C is back and A rejoined", 3 * PEER_TICKS, () -> players(mc) == 3)
                .step("the game B runs from its copy has everything A's game had", SHORT_TICKS, () -> {
                    problem[0] = liveOn(mc, 1);
                    return problem[0].isEmpty();
                })
                .action("play on as the new host", () -> {
                    IntegratedServer server = mc.getIntegratedServer();
                    server.callFromMainThread(() -> {
                        play2(server);
                        return null;
                    }).get();
                    from[0] = HostSync.current().seq() + 2;
                })
                .step("the running game has the new changes", SHORT_TICKS, () -> {
                    problem[0] = liveOn(mc, 2);
                    return problem[0].isEmpty();
                });
        return publish(runner, ctx, 2, from, "a", "c")
                .action("tell A and C", () -> ctx.writeShared("b-done", "ok"))
                .step("A and C left with their copies checked", 2 * PEER_TICKS,
                        () -> players(mc) == 1 && ctx.sharedExists("a-final") && ctx.sharedExists("c-final"))
                .action("B quits", () -> Compat.disconnectFromWorld(mc, Compat.DEFAULT_QUIT_MESSAGE))
                .step("the world closed", WORLD_TICKS, () -> mc.getIntegratedServer() == null && mc.world == null)
                .action("A's and C's copies are checkpoints of B's ledger", () -> {
                    mc.displayGuiScreen(new GuiMainMenu());
                    Path world = folder(mc, state);
                    Ledger ledger = Ledger.load(state.world.keys().auth(), world.resolve(CoopWorldStore.MARKER_DIR).resolve("ledger.jsonl"));
                    for (String who : new String[]{"a", "c"}) {
                        String[] reported = ctx.readShared(who + "-final").split(" ");
                        long seq = Long.parseLong(reported[0]);
                        List<LedgerEntry> at = ledger.entriesAfter(seq - 1);
                        require(!at.isEmpty() && at.get(0).seq() == seq && at.get(0).root().equals(reported[1])
                                        && at.get(0).hash().equals(reported[2]),
                                "the copy of " + who + " says checkpoint " + seq + " root " + reported[1] + "; B's ledger has "
                                        + (at.isEmpty() ? "no such checkpoint" : "root " + at.get(0).root()));
                        DropIn.LOGGER.info("Self-test integrity: the closed copy of {} is checkpoint {} of B's ledger ({} checkpoints)",
                                who, seq, ledger.size());
                    }
                    logLeftover(world, "B");
                });
    }

    private static StepRunner integrityC(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        // C joins after B has its copy, so that B is the member that connected first (the successor).
        StepRunner runner = joinB(ctx, state, HANDOFF_ON, "b-marked")
                .step("copy complete", 2 * PEER_TICKS, copyComplete(mc, state));
        runner = M3Scenarios.mark(runner, mc, MARK_C)
                .action("tell A", () -> {
                    ctx.writeShared("c-uuid", myUuid(mc));
                    ctx.writeShared("c-marked", "ok");
                });
        runner = match(runner, ctx, state, 1, "c")
                // No input from here on: A quits, B takes over, and this game must be back in the world by itself.
                .step("back in the world, on B's session", 4 * PEER_TICKS, () -> MemberHandoff.lastRole() == MemberHandoff.Role.FOLLOWER
                        && ctx.sharedExists("b-hosting") && inWorld(mc) && mc.getIntegratedServer() == null
                        && SyncClient.status(state.world.worldId()) != null);
        runner = match(runner, ctx, state, 2, "c")
                .step("B is done", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists("b-done");
                });
        return leaveAndRescan(runner, ctx, state, "c");
    }
}

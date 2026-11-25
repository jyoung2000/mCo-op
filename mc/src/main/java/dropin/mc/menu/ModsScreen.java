package dropin.mc.menu;

import dropin.core.mods.ModManifest;
import dropin.core.world.CoopWorld;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.policy.PolicyActions;
import dropin.mc.policy.WorldPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Mods tab of DESIGN 4.15: the mod names and versions of the world's mod list, with "missing here" /
 * "extra here" marks against this PC's {@code mods/} folder. Extra jars can be marked "keep" (DESIGN 4.14).
 */
public class ModsScreen extends Screen {
    private static final String K = "text.dropin_coop.";
    private static final int ROW_HEIGHT = 14;
    private static final int TOP = 36;

    public enum Mark { NONE, MISSING, EXTRA }

    /** @param file the jar's file name; {@code name} is the mod id if the jar states one */
    public record Row(String file, String name, String version, Mark mark, boolean kept) {
    }

    private final Screen parent;
    private final String worldId;
    private List<Row> rows = List.of();
    private boolean known;
    private int page;

    public ModsScreen(Screen parent, String worldId) {
        super(Component.translatable(K + "mods.title"));
        this.parent = parent;
        this.worldId = worldId;
    }

    public List<Row> rows() {
        return rows;
    }

    /** The world's mods first (in file order), then this PC's extra jars. Null if no mod list is known yet. Also used by the self-tests. */
    public static List<Row> rowsOf(Minecraft mc, String worldId) {
        try {
            CoopWorld world = CoopWorlds.store().get(worldId).orElse(null);
            WorldPolicy.Held held = world == null ? null : PolicyActions.held(mc, world);
            if (held == null) {
                return null;
            }
            List<ModManifest.Entry> local = WorldPolicy.localMods();
            Set<String> keep = WorldPolicy.keep().stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            List<Row> rows = new ArrayList<>();
            for (ModManifest.Entry file : held.manifest().files()) {
                boolean here = local.stream().anyMatch(have -> have.path().equalsIgnoreCase(file.path()) && have.sha512().equals(file.sha512()));
                rows.add(new Row(file.path(), file.displayName(), file.version(), here ? Mark.NONE : Mark.MISSING, false));
            }
            for (ModManifest.Entry have : local) {
                if (held.manifest().files().stream().noneMatch(file -> have.path().equalsIgnoreCase(file.path()) && have.sha512().equals(file.sha512()))) {
                    rows.add(new Row(have.path(), have.displayName(), have.version(), Mark.EXTRA, keep.contains(have.path().toLowerCase(Locale.ROOT))));
                }
            }
            return rows;
        } catch (Exception e) {
            DropIn.LOGGER.warn("Could not read the mod list of {}", worldId, e);
            return null;
        }
    }

    /** Marks an extra jar "keep" (it stays when the mods are swapped; a player who keeps extras can join but not host). */
    public static void setKeep(String file, boolean keep) {
        try {
            WorldPolicy.setKeep(file, keep);
        } catch (Exception e) {
            DropIn.LOGGER.warn("Could not change the keep mark of {}", file, e);
        }
    }

    private int perPage() {
        return Math.max(1, (this.height - TOP - 40) / ROW_HEIGHT);
    }

    @Override
    protected void init() {
        List<Row> found = rowsOf(this.minecraft, worldId);
        known = found != null;
        rows = known ? found : List.of();
        int perPage = perPage();
        int pages = Math.max(1, (rows.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        int left = Math.max(10, this.width / 2 - 170);
        for (int i = 0; i < perPage && page * perPage + i < rows.size(); i++) {
            Row row = rows.get(page * perPage + i);
            if (row.mark() == Mark.EXTRA) {
                this.addRenderableWidget(Button.builder(Component.translatable(row.kept() ? K + "mods.kept" : K + "mods.keep"), button -> {
                    setKeep(row.file(), !row.kept());
                    this.rebuildWidgets();
                }).bounds(left + 290, TOP + i * ROW_HEIGHT - 2, 50, 13).build());
            }
        }
        if (pages > 1) {
            this.addRenderableWidget(Button.builder(Component.literal("<"), button -> {
                page = Math.floorMod(page - 1, pages);
                this.rebuildWidgets();
            }).bounds(left, 8, 20, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal(">"), button -> {
                page = Math.floorMod(page + 1, pages);
                this.rebuildWidgets();
            }).bounds(left + 320, 8, 20, 20).build());
        }
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    private static Component line(Row row) {
        MutableComponent text = Component.literal(row.name());
        if (!row.version().isEmpty()) {
            text.append(" " + row.version());
        }
        if (row.mark() == Mark.MISSING) {
            text.append("  ").append(Component.translatable(K + "mods.missing"));
        } else if (row.mark() == Mark.EXTRA) {
            text.append("  ").append(Component.translatable(K + "mods.extra"));
        }
        return text;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float a) {
        super.render(graphics, mouseX, mouseY, a);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, -1);
        int left = Math.max(10, this.width / 2 - 170);
        if (!known) {
            graphics.drawCenteredString(this.font, Component.translatable(K + "mods.unknown"), this.width / 2, TOP, -6250336);
        } else if (rows.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.translatable(K + "mods.none"), this.width / 2, TOP, -6250336);
        }
        int perPage = perPage();
        for (int i = 0; i < perPage && page * perPage + i < rows.size(); i++) {
            Row row = rows.get(page * perPage + i);
            int color = row.mark() == Mark.MISSING ? -43691 : row.mark() == Mark.EXTRA ? -9216 : -1;
            graphics.drawString(this.font, line(row), left, TOP + i * ROW_HEIGHT, color);
        }
    }
}

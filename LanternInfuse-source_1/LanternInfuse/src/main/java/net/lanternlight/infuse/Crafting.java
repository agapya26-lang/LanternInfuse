package net.lanternlight.infuse;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registers the 3x3 recipes from recipes.yml and enforces the craft limits:
 * the first craft of each effect is Augmented, then {@code max-normal-crafts} normal ones, then it's gone.
 */
public final class Crafting implements Listener {
    private final LanternInfuse plugin;
    private final Map<NamespacedKey, InfuseType> byKey = new HashMap<>();
    private final EnumMap<InfuseType, List<String>> rows = new EnumMap<>(InfuseType.class);
    private final EnumMap<InfuseType, Material[]> grids = new EnumMap<>(InfuseType.class);

    public Crafting(LanternInfuse plugin) {
        this.plugin = plugin;
    }

    public void load() {
        for (NamespacedKey k : byKey.keySet()) {
            Bukkit.removeRecipe(k);
        }
        byKey.clear();
        rows.clear();
        grids.clear();

        File f = new File(plugin.getDataFolder(), "recipes.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = y.getConfigurationSection("recipes");
        if (root == null) {
            plugin.getLogger().warning("recipes.yml has no 'recipes' section - no effects can be crafted.");
            return;
        }

        for (InfuseType t : InfuseType.values()) {
            ConfigurationSection s = root.getConfigurationSection(t.id());
            if (s == null) continue;
            List<String> shape = s.getStringList("shape");
            ConfigurationSection ing = s.getConfigurationSection("ingredients");
            if (shape.size() != 3 || ing == null) {
                plugin.getLogger().warning("Recipe for " + t.id() + " is invalid (needs a 3-row shape and ingredients).");
                continue;
            }

            NamespacedKey key = new NamespacedKey(plugin, "craft_" + t.id());
            ShapedRecipe recipe = new ShapedRecipe(key, plugin.items().create(t, false));
            recipe.shape(shape.toArray(new String[0]));

            Map<Character, Material> mats = new HashMap<>();
            boolean ok = true;
            for (String k : ing.getKeys(false)) {
                Material m = Material.matchMaterial(ing.getString(k, ""));
                if (m == null || k.length() != 1) {
                    plugin.getLogger().warning("Recipe " + t.id() + ": bad ingredient '" + k + "' -> '" + ing.getString(k) + "'");
                    ok = false;
                    break;
                }
                recipe.setIngredient(k.charAt(0), m);
                mats.put(k.charAt(0), m);
            }
            if (!ok) continue;

            try {
                Bukkit.addRecipe(recipe);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Could not register recipe " + t.id() + ": " + ex.getMessage());
                continue;
            }
            byKey.put(key, t);

            List<String> pretty = new ArrayList<>();
            for (String row : shape) {
                List<String> cells = new ArrayList<>();
                for (char c : row.toCharArray()) {
                    Material m = mats.get(c);
                    cells.add(m == null ? "—" : prettify(m));
                }
                pretty.add(String.join(" &8| &f", cells));
            }
            rows.put(t, pretty);

            Material[] grid = new Material[9];
            for (int r = 0; r < 3; r++) {
                String row = shape.get(r);
                for (int c = 0; c < 3 && c < row.length(); c++) {
                    grid[r * 3 + c] = mats.get(row.charAt(c));
                }
            }
            grids.put(t, grid);
        }
        plugin.getLogger().info("Registered " + byKey.size() + " effect recipes.");
    }

    /** Recipe rows ready to print in chat. */
    public List<String> rows(InfuseType t) {
        return rows.getOrDefault(t, List.of());
    }

    /** The 9 crafting slots (row by row); null = empty. */
    public Material[] grid(InfuseType t) {
        return grids.getOrDefault(t, new Material[9]);
    }

    private static String prettify(Material m) {
        String[] parts = m.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    public int maxCrafts() {
        int normal = plugin.getConfig().getInt("settings.max-normal-crafts", 3);
        boolean aug = plugin.getConfig().getBoolean("settings.augmented-first-craft", true);
        return normal + (aug ? 1 : 0);
    }

    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        CraftingInventory inv = e.getInventory();
        // effect items are never valid ingredients for anything
        for (ItemStack it : inv.getMatrix()) {
            if (it != null && plugin.items().read(it) != null) {
                inv.setResult(null);
                return;
            }
        }
        Recipe r = e.getRecipe();
        if (!(r instanceof Keyed k)) return;
        InfuseType t = byKey.get(k.getKey());
        if (t == null) return;

        int made = plugin.crafted(t);
        if (made >= maxCrafts()) {
            inv.setResult(null);
            return;
        }
        boolean augFirst = plugin.getConfig().getBoolean("settings.augmented-first-craft", true);
        inv.setResult(plugin.items().create(t, augFirst && made == 0));
    }

    @EventHandler
    public void onCraft(CraftItemEvent e) {
        Recipe r = e.getRecipe();
        if (!(r instanceof Keyed k)) return;
        InfuseType t = byKey.get(k.getKey());
        if (t == null) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (e.isShiftClick()) {
            e.setCancelled(true);
            plugin.msg(p, "&cCraft effects one at a time (no shift-click).");
            return;
        }
        if (plugin.crafted(t) >= maxCrafts()) {
            e.setCancelled(true);
            plugin.msg(p, "&cThis effect has already been crafted the maximum number of times.");
            return;
        }

        ItemFactory.Held held = plugin.items().read(e.getCurrentItem());
        boolean aug = held != null && held.aug();
        plugin.addCrafted(t);
        announce(p, t, aug);
    }

    private void announce(Player p, InfuseType t, boolean aug) {
        if (!plugin.getConfig().getBoolean("settings.broadcast-craft", true)) return;
        EffectSettings s = plugin.settings(t);
        Location l = p.getLocation();
        String where = plugin.getConfig().getBoolean("settings.broadcast-coordinates", true)
                ? " &7at &f" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ()
                : "";
        String what = (aug ? "&6✦ Augmented " : "a ") + s.name() + " Effect";
        Bukkit.broadcast(Text.c(plugin.prefix() + "&e" + p.getName() + " &7crafted " + what + where + "&7!"));
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        }
    }
}

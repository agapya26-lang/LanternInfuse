package net.frosting.core;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The /shop that Donut SMP players asked to get back: buy basics from the server, by category. Prices in shop.yml. */
public final class ShopModule implements CommandExecutor {
    private record Category(String id, String name, Material icon, Map<Material, Double> items) {
    }

    private final FrostingCore plugin;
    private final Map<String, Category> categories = new LinkedHashMap<>();

    public ShopModule(FrostingCore plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        categories.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "shop.yml"));
        ConfigurationSection cats = y.getConfigurationSection("categories");
        if (cats == null) return;
        for (String id : cats.getKeys(false)) {
            ConfigurationSection c = cats.getConfigurationSection(id);
            if (c == null) continue;
            Material icon = Material.matchMaterial(c.getString("icon", "CHEST"));
            Map<Material, Double> items = new LinkedHashMap<>();
            ConfigurationSection is = c.getConfigurationSection("items");
            if (is != null) {
                for (String k : is.getKeys(false)) {
                    Material m = Material.matchMaterial(k);
                    if (m != null) items.put(m, is.getDouble(k));
                    else plugin.getLogger().warning("shop.yml: unknown item " + k);
                }
            }
            categories.put(id, new Category(id, c.getString("name", id), icon == null ? Material.CHEST : icon, items));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        openMain(p);
        return true;
    }

    private void openMain(Player p) {
        int rows = Math.max(3, Math.min(6, (categories.size() + 8) / 9 + 2));
        Gui g = new Gui(rows, "&8Shop");
        int slot = 10;
        for (Category c : categories.values()) {
            if (slot % 9 == 8) slot += 2;
            final Category cat = c;
            g.set(slot++, Items.named(c.icon(), c.name(), "&7" + c.items().size() + " items", "", "&eClick to browse"),
                    e -> openCategory(p, cat, 0));
        }
        g.fillEmpty(Items.pane());
        g.open(p);
    }

    private void openCategory(Player p, Category c, int page) {
        List<Map.Entry<Material, Double>> list = new ArrayList<>(c.items().entrySet());
        int pages = Math.max(1, (list.size() + 44) / 45);
        final int pg = Math.max(0, Math.min(page, pages - 1));
        double mult = plugin.getConfig().getDouble("shop.price-multiplier", 1.0);
        Gui g = new Gui(6, "&8Shop &7- " + c.name() + " &7(" + (pg + 1) + "/" + pages + ")");
        for (int i = 0; i < 45; i++) {
            int idx = pg * 45 + i;
            if (idx >= list.size()) break;
            Material m = list.get(idx).getKey();
            double each = list.get(idx).getValue() * mult;
            ItemStack icon = Items.withLore(new ItemStack(m), "", "&7Price: &a" + Fmt.money(each) + " &7each",
                    "&eLeft-click: &fbuy 1", "&eRight-click: &fbuy 16", "&eShift-click: &fbuy 64");
            g.set(i, icon, e -> {
                int amount = e.isShiftClick() ? 64 : e.isRightClick() ? 16 : 1;
                buy(p, m, amount, each);
            });
        }
        for (int i = 45; i < 54; i++) g.put(i, Items.pane());
        g.set(49, Items.named(Material.BARRIER, "&c← Back to categories"), e -> openMain(p));
        g.set(48, Items.named(Material.PAPER, "&eYour balance", "&a" + Fmt.money(plugin.eco().balance(p.getUniqueId()))), null);
        if (pg > 0) g.set(45, Items.named(Material.ARROW, "&e← Previous"), e -> openCategory(p, c, pg - 1));
        if (pg < pages - 1) g.set(53, Items.named(Material.ARROW, "&eNext →"), e -> openCategory(p, c, pg + 1));
        g.open(p);
    }

    private void buy(Player p, Material m, int amount, double each) {
        double cost = Math.round(each * amount * 100.0) / 100.0;
        if (Items.capacityFor(p.getInventory(), m) < amount) {
            plugin.msg(p, "&cYou don't have enough inventory space.");
            return;
        }
        if (!plugin.eco().take(p.getUniqueId(), cost)) {
            plugin.msg(p, "&cThat costs &f" + Fmt.money(cost) + "&c. Balance: &f" + Fmt.money(plugin.eco().balance(p.getUniqueId())));
            return;
        }
        int left = amount;
        while (left > 0) {
            int n = Math.min(left, m.getMaxStackSize());
            p.getInventory().addItem(new ItemStack(m, n));
            left -= n;
        }
        plugin.msg(p, "&aBought &f" + amount + "x " + m.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " &afor &f" + Fmt.money(cost) + "&a.");
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.1f);
    }
}

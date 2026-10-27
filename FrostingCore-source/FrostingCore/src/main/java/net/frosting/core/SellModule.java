package net.frosting.core;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/** /sell (menu: close it to sell), /sell hand, /sell all, /worth. Prices live in worth.yml. */
public final class SellModule implements CommandExecutor {
    private final FrostingCore plugin;
    private final EnumMap<Material, Double> worth = new EnumMap<>(Material.class);

    public SellModule(FrostingCore plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        worth.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "worth.yml"));
        ConfigurationSection s = y.getConfigurationSection("worth");
        if (s == null) return;
        for (String key : s.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            if (m != null) worth.put(m, s.getDouble(key));
            else plugin.getLogger().warning("worth.yml: unknown item " + key);
        }
    }

    /** Total value of a stack, 0 if it can't be sold (custom items are never sellable). */
    public double priceOf(ItemStack it) {
        if (it == null || it.getType().isAir()) return 0;
        if (!Items.isPlain(it)) return 0;
        Double each = worth.get(it.getType());
        if (each == null) return 0;
        return each * it.getAmount() * plugin.getConfig().getDouble("sell.multiplier", 1.0);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (cmd.getName().toLowerCase(Locale.ROOT).equals("worth")) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            double v = priceOf(hand);
            if (v <= 0) plugin.msg(p, "&cThat item can't be sold.");
            else plugin.msg(p, "&f" + hand.getAmount() + "x " + hand.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                    + " &7is worth &a" + Fmt.money(v) + "&7.");
            return true;
        }
        String mode = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (mode) {
            case "hand" -> {
                ItemStack hand = p.getInventory().getItemInMainHand();
                double v = priceOf(hand);
                if (v <= 0) {
                    plugin.msg(p, "&cThat item can't be sold.");
                } else {
                    int n = hand.getAmount();
                    p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                    pay(p, v, n);
                }
            }
            case "all" -> {
                double total = 0;
                int count = 0;
                ItemStack[] contents = p.getInventory().getStorageContents();
                for (int i = 0; i < contents.length; i++) {
                    double v = priceOf(contents[i]);
                    if (v > 0) {
                        total += v;
                        count += contents[i].getAmount();
                        p.getInventory().setItem(i, null);
                    }
                }
                if (total <= 0) plugin.msg(p, "&cYou have nothing sellable in your inventory.");
                else pay(p, total, count);
            }
            default -> openMenu(p);
        }
        return true;
    }

    private void pay(Player p, double total, int count) {
        plugin.eco().add(p.getUniqueId(), total);
        plugin.msg(p, "&aSold &f" + count + " &aitems for &f" + Fmt.money(total) + "&a.");
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
    }

    private void openMenu(Player p) {
        Gui g = new Gui(6, "&8Sell Items &7(close to sell)").free();
        g.onClose(e -> finish(p, g.inventory()));
        g.open(p);
    }

    private void finish(Player p, Inventory inv) {
        double total = 0;
        int count = 0;
        List<ItemStack> back = new ArrayList<>();
        for (ItemStack it : inv.getContents()) {
            if (it == null || it.getType().isAir()) continue;
            double v = priceOf(it);
            if (v > 0) {
                total += v;
                count += it.getAmount();
            } else {
                back.add(it);
            }
        }
        inv.clear();
        for (ItemStack b : back) Items.give(p, b);
        if (total > 0) pay(p, total, count);
        else if (!back.isEmpty()) plugin.msg(p, "&cNothing in there could be sold, items returned.");
    }
}

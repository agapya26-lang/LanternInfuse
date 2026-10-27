package net.frosting.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /shardshop: spend shards (earned from kills, AFK and daily rewards). Entries live in config.yml. */
public final class ShardShopModule implements CommandExecutor {
    private record Entry(double cost, String type, String arg, String label, Material icon) {
    }

    private final FrostingCore plugin;

    public ShardShopModule(FrostingCore plugin) {
        this.plugin = plugin;
    }

    /** Lines look like  100|key|common:1|&aCommon Crate Key|TRIPWIRE_HOOK  (cost|type|arg|label|icon). */
    private List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("shardshop.items")) {
            String[] p = line.split("\\|");
            if (p.length < 5) continue;
            Material icon = Material.matchMaterial(p[4].trim());
            double cost = Fmt.parse(p[0]);
            if (Double.isNaN(cost) || icon == null) {
                plugin.getLogger().warning("shardshop.items: bad line '" + line + "'");
                continue;
            }
            out.add(new Entry(cost, p[1].trim().toLowerCase(Locale.ROOT), p[2].trim(), p[3], icon));
        }
        return out;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        open(p);
        return true;
    }

    private void open(Player p) {
        List<Entry> list = entries();
        int rows = Math.max(3, Math.min(6, (list.size() + 8) / 9 + 2));
        Gui g = new Gui(rows, "&8Shard Shop &7- &b" + Fmt.shortNum(plugin.eco().shards(p.getUniqueId())) + " shards");
        int slot = 10;
        for (Entry en : list) {
            if (slot % 9 == 8) slot += 2;
            if (slot >= rows * 9 - 9) break;
            g.set(slot++, Items.named(en.icon(), en.label(), "&7Cost: &b" + Fmt.shortNum(en.cost()) + " shards", "", "&eClick to buy"),
                    e -> buy(p, en));
        }
        g.fillEmpty(Items.pane());
        g.open(p);
    }

    private void buy(Player p, Entry en) {
        if (!plugin.eco().takeShards(p.getUniqueId(), en.cost())) {
            plugin.msg(p, "&cYou need &b" + Fmt.shortNum(en.cost()) + " shards&c. You have &b"
                    + Fmt.shortNum(plugin.eco().shards(p.getUniqueId())) + "&c.");
            return;
        }
        String[] a = en.arg().split(":");
        switch (en.type()) {
            case "money" -> plugin.eco().add(p.getUniqueId(), Fmt.parse(a[0]));
            case "key" -> {
                if (plugin.crates() != null) plugin.crates().addKeys(p.getUniqueId(), a[0], a.length > 1 ? (int) Fmt.parse(a[1]) : 1);
            }
            case "item" -> {
                Material m = Material.matchMaterial(a[0]);
                if (m != null) Items.give(p, new ItemStack(m, a.length > 1 ? (int) Fmt.parse(a[1]) : 1));
            }
            case "cmd" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), en.arg().replace("%player%", p.getName()));
            default -> plugin.getLogger().warning("shardshop: unknown type " + en.type());
        }
        plugin.msg(p, "&aBought " + en.label() + " &afor &b" + Fmt.shortNum(en.cost()) + " shards&a.");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
        open(p);
    }
}

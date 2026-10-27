package net.frosting.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** /bounty: put money on someone's head; whoever kills them collects it. */
public final class BountyModule implements CommandExecutor, TabCompleter {
    private final FrostingCore plugin;

    public BountyModule(FrostingCore plugin) {
        this.plugin = plugin;
    }

    /** Called when a player dies to another player. */
    public void claim(Player killer, UUID victim, String victimName) {
        PlayerStore.Data vd = plugin.store().peek(victim);
        if (vd == null || vd.bounty <= 0 || killer.getUniqueId().equals(victim)) return;
        double amount = vd.bounty;
        vd.bounty = 0;
        plugin.eco().add(killer.getUniqueId(), amount);
        plugin.store().dirty();
        Bukkit.broadcast(Text.c(plugin.prefix() + "&c" + killer.getName() + " &7claimed the &6" + Fmt.money(amount)
                + " &7bounty on &c" + victimName + "&7!"));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length == 0) {
            openList(p);
            return true;
        }
        if (args.length < 2) {
            plugin.msg(p, "&cUsage: /bounty <player> <amount>");
            return true;
        }
        UUID target = null;
        String name = args[0];
        Player online = Bukkit.getPlayerExact(args[0]);
        if (online != null) {
            target = online.getUniqueId();
            name = online.getName();
        } else {
            for (Map.Entry<UUID, PlayerStore.Data> en : plugin.store().all().entrySet()) {
                if (en.getValue().name.equalsIgnoreCase(args[0])) {
                    target = en.getKey();
                    name = en.getValue().name;
                }
            }
        }
        if (target == null) {
            plugin.msg(p, "&cPlayer not found.");
            return true;
        }
        if (target.equals(p.getUniqueId())) {
            plugin.msg(p, "&cYou can't put a bounty on yourself.");
            return true;
        }
        double amt = Fmt.parse(args[1]);
        double min = plugin.getConfig().getDouble("bounty.min", 1000);
        if (Double.isNaN(amt) || amt < min) {
            plugin.msg(p, "&cThe minimum bounty is &f" + Fmt.money(min) + "&c.");
            return true;
        }
        amt = Math.round(amt * 100.0) / 100.0;
        if (!plugin.eco().take(p.getUniqueId(), amt)) {
            plugin.msg(p, "&cYou don't have &f" + Fmt.money(amt) + "&c.");
            return true;
        }
        PlayerStore.Data td = plugin.store().get(target);
        td.bounty += amt;
        plugin.store().dirty();
        Bukkit.broadcast(Text.c(plugin.prefix() + "&e" + p.getName() + " &7put a &6" + Fmt.money(amt) + " &7bounty on &c" + name
                + " &8(total &6" + Fmt.money(td.bounty) + "&8)"));
        return true;
    }

    private void openList(Player p) {
        List<Map.Entry<UUID, PlayerStore.Data>> list = new ArrayList<>();
        for (Map.Entry<UUID, PlayerStore.Data> en : plugin.store().all().entrySet()) {
            if (en.getValue().bounty > 0) list.add(en);
        }
        list.sort(Comparator.comparingDouble((Map.Entry<UUID, PlayerStore.Data> en) -> en.getValue().bounty).reversed());
        Gui g = new Gui(6, "&8Bounties &7(" + list.size() + ")");
        for (int i = 0; i < Math.min(45, list.size()); i++) {
            UUID id = list.get(i).getKey();
            PlayerStore.Data d = list.get(i).getValue();
            ItemStack head = Items.named(Material.PLAYER_HEAD, "&c" + d.name, "&7Bounty: &6" + Fmt.money(d.bounty),
                    "&7Kills: &f" + d.kills, "", "&eKill them to claim it!");
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            sm.setOwningPlayer(op);
            head.setItemMeta(sm);
            g.put(i, head);
        }
        for (int i = 45; i < 54; i++) g.put(i, Items.pane());
        g.put(49, Items.named(Material.PAPER, "&eHow it works", "&7/bounty <player> <amount>", "&7Kill someone with a bounty to claim it."));
        g.open(p);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getName().toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(pl.getName());
            }
        }
        return out;
    }
}

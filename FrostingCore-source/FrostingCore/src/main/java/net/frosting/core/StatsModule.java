package net.frosting.core;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Money commands (/bal /pay /baltop), shards, kills/deaths/playtime and leaderboards. */
public final class StatsModule implements Listener, CommandExecutor, TabCompleter {
    private final FrostingCore plugin;
    private final Map<UUID, Integer> streaks = new HashMap<>();

    public StatsModule(FrostingCore plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- tracking

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        PlayerStore.Data d = plugin.store().get(e.getPlayer().getUniqueId());
        d.name = e.getPlayer().getName();
        plugin.store().dirty();
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        plugin.store().get(victim.getUniqueId()).deaths++;
        streaks.remove(victim.getUniqueId());
        Player killer = victim.getKiller();
        if (killer == null && plugin.combat() != null) {
            UUID a = plugin.combat().attackerOf(victim.getUniqueId());
            if (a != null) killer = Bukkit.getPlayer(a);
        }
        if (killer != null && !killer.equals(victim)) {
            PlayerStore.Data kd = plugin.store().get(killer.getUniqueId());
            kd.kills++;
            if (plugin.bounty() != null) plugin.bounty().claim(killer, victim.getUniqueId(), victim.getName());
            int streak = streaks.merge(killer.getUniqueId(), 1, Integer::sum);
            if (streak >= 3 && streak % 3 == 0) {
                Bukkit.broadcast(Text.c(plugin.prefix() + "&c" + killer.getName() + " &7is on a &6" + streak + " kill streak&7!"));
                plugin.eco().addShards(killer.getUniqueId(), streak * 2.0);
            }
            if (plugin.getConfig().getBoolean("effects.kill-effects", true)) {
                Location at = victim.getLocation();
                at.getWorld().strikeLightningEffect(at);
                Fireworks.burst(at.clone().add(0, 1, 0), Color.RED, Color.ORANGE);
                e.deathMessage(Text.c("&8☠ &c" + victim.getName() + " &7was slain by &a" + killer.getName()));
            }
            double reward = plugin.getConfig().getDouble("shards.per-kill", 15);
            if (reward > 0) {
                plugin.eco().addShards(killer.getUniqueId(), reward);
                killer.sendActionBar(Text.c("&b+" + Fmt.shortNum(reward) + " shards &7for killing &f" + victim.getName()));
            }
        }
        plugin.store().dirty();
    }

    public void tickSecond() {
        for (Player p : Bukkit.getOnlinePlayers()) plugin.store().get(p.getUniqueId()).playSeconds++;
        plugin.store().dirty();
    }

    // ---------------------------------------------------------------- lookup

    /** Finds a player's UUID by name: online first, then anyone we've seen. */
    private UUID find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        for (Map.Entry<UUID, PlayerStore.Data> en : plugin.store().all().entrySet()) {
            if (en.getValue().name.equalsIgnoreCase(name)) return en.getKey();
        }
        return null;
    }

    private String nameOf(UUID id) {
        PlayerStore.Data d = plugin.store().peek(id);
        return d == null || d.name.isEmpty() ? "Unknown" : d.name;
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "bal" -> balance(sender, args);
            case "shards" -> shards(sender, args);
            case "pay" -> pay(sender, args);
            case "baltop" -> top(sender, "money");
            case "top" -> top(sender, args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "money");
            case "stats" -> stats(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void balance(CommandSender s, String[] a) {
        UUID id;
        if (a.length > 0) {
            id = find(a[0]);
            if (id == null) {
                plugin.msg(s, "&cPlayer not found.");
                return;
            }
        } else if (s instanceof Player p) {
            id = p.getUniqueId();
        } else {
            plugin.msg(s, "&cUsage: /bal <player>");
            return;
        }
        plugin.msg(s, "&f" + nameOf(id) + "&7's balance: &a" + Fmt.money(plugin.eco().balance(id)));
    }

    private void shards(CommandSender s, String[] a) {
        UUID id;
        if (a.length > 0) {
            id = find(a[0]);
            if (id == null) {
                plugin.msg(s, "&cPlayer not found.");
                return;
            }
        } else if (s instanceof Player p) {
            id = p.getUniqueId();
        } else {
            plugin.msg(s, "&cUsage: /shards <player>");
            return;
        }
        plugin.msg(s, "&f" + nameOf(id) + "&7's shards: &b" + Fmt.shortNum(plugin.eco().shards(id)));
    }

    private void pay(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Players only.");
            return;
        }
        if (a.length < 2) {
            plugin.msg(p, "&cUsage: /pay <player> <amount>  (1k, 2.5m ... works)");
            return;
        }
        UUID to = find(a[0]);
        if (to == null) {
            plugin.msg(p, "&cPlayer not found.");
            return;
        }
        if (to.equals(p.getUniqueId())) {
            plugin.msg(p, "&cYou can't pay yourself.");
            return;
        }
        double amt = Fmt.parse(a[1]);
        double min = plugin.getConfig().getDouble("economy.min-pay", 1);
        if (Double.isNaN(amt) || amt < min) {
            plugin.msg(p, "&cEnter an amount of at least &f" + Fmt.money(min) + "&c.");
            return;
        }
        amt = Math.round(amt * 100.0) / 100.0;
        if (!plugin.eco().take(p.getUniqueId(), amt)) {
            plugin.msg(p, "&cYou don't have enough money. Balance: &f" + Fmt.money(plugin.eco().balance(p.getUniqueId())));
            return;
        }
        plugin.eco().add(to, amt);
        plugin.msg(p, "&aYou paid &f" + nameOf(to) + " &a" + Fmt.money(amt) + "&a.");
        Player target = Bukkit.getPlayer(to);
        if (target != null) plugin.msg(target, "&f" + p.getName() + " &apaid you &f" + Fmt.money(amt) + "&a.");
    }

    private double value(PlayerStore.Data d, String type) {
        return switch (type) {
            case "shards" -> d.shards;
            case "kills" -> d.kills;
            case "deaths" -> d.deaths;
            case "playtime" -> d.playSeconds;
            default -> d.money;
        };
    }

    private String show(double v, String type) {
        return switch (type) {
            case "shards" -> "&b" + Fmt.shortNum(v);
            case "kills", "deaths" -> "&c" + (long) v;
            case "playtime" -> "&e" + Fmt.duration((long) v);
            default -> "&a" + Fmt.money(v);
        };
    }

    private void top(CommandSender s, String type) {
        if (!List.of("money", "shards", "kills", "deaths", "playtime").contains(type)) {
            plugin.msg(s, "&cTypes: money, shards, kills, deaths, playtime");
            return;
        }
        List<Map.Entry<UUID, PlayerStore.Data>> list = new ArrayList<>(plugin.store().all().entrySet());
        list.removeIf(en -> en.getValue().name.isEmpty());
        list.sort(Comparator.comparingDouble((Map.Entry<UUID, PlayerStore.Data> en) -> value(en.getValue(), type)).reversed());
        plugin.msg(s, "&d&lTop " + type);
        int n = Math.min(10, list.size());
        for (int i = 0; i < n; i++) {
            PlayerStore.Data d = list.get(i).getValue();
            s.sendMessage(Text.c(" &e#" + (i + 1) + " &f" + d.name + " &8- " + show(value(d, type), type)));
        }
        if (n == 0) s.sendMessage(Text.c(" &7Nobody yet."));
    }

    private void stats(CommandSender s, String[] a) {
        UUID id;
        if (a.length > 0) {
            id = find(a[0]);
            if (id == null) {
                plugin.msg(s, "&cPlayer not found.");
                return;
            }
        } else if (s instanceof Player p) {
            id = p.getUniqueId();
        } else {
            plugin.msg(s, "&cUsage: /stats <player>");
            return;
        }
        PlayerStore.Data d = plugin.store().get(id);
        plugin.msg(s, "&d&lStats &8- &f" + nameOf(id));
        s.sendMessage(Text.c(" &7Money: &a" + Fmt.money(d.money) + "  &7Shards: &b" + Fmt.shortNum(d.shards)));
        s.sendMessage(Text.c(" &7Kills: &c" + d.kills + "  &7Deaths: &c" + d.deaths
                + "  &7K/D: &f" + (d.deaths == 0 ? String.valueOf(d.kills) : String.format(Locale.US, "%.2f", (double) d.kills / d.deaths))));
        s.sendMessage(Text.c(" &7Playtime: &e" + Fmt.duration(d.playSeconds)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        String n = cmd.getName().toLowerCase(Locale.ROOT);
        if (args.length == 1 && n.equals("top")) {
            out.addAll(List.of("money", "shards", "kills", "deaths", "playtime"));
        } else if (args.length == 1 && List.of("bal", "pay", "shards", "stats").contains(n)) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}

package net.frosting.core;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /fc reload | give|take|set <player> <money|shards> <amount> */
public final class AdminCommand implements CommandExecutor, TabCompleter {
    private final FrostingCore plugin;

    public AdminCommand(FrostingCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("frostingcore.admin")) {
            plugin.msg(s, "&cYou don't have permission.");
            return true;
        }
        if (a.length == 0) {
            plugin.msg(s, "&e/fc reload &8| &e/fc give|take|set <player> <money|shards> <amount>");
            plugin.msg(s, "&e/fc hub build|holo|clear &8| &e/fc crate <id> &8| &e/fc key <player> <crate> [n] &8| &e/fc keyall <crate> [n]");
            plugin.msg(s, "&e/fc airdrop &8| &e/fc lottery draw");
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "hub" -> {
                if (plugin.hub() == null) plugin.msg(s, "&cThe hub module is off.");
                else plugin.hub().handle(s, a);
                return true;
            }
            case "airdrop" -> {
                if (plugin.airdrop() == null) plugin.msg(s, "&cThe airdrop module is off.");
                else {
                    plugin.airdrop().start();
                    plugin.msg(s, "&aStarting a Frosting Drop...");
                }
                return true;
            }
            case "lottery" -> {
                if (plugin.lottery() != null && a.length > 1 && a[1].equalsIgnoreCase("draw")) plugin.lottery().draw();
                else plugin.msg(s, "&cUsage: /fc lottery draw");
                return true;
            }
            case "crate" -> {
                if (!(s instanceof Player p) || plugin.crates() == null || a.length < 2) {
                    plugin.msg(s, "&cUsage (in game, look at a block): /fc crate <id>");
                    return true;
                }
                plugin.crates().place(p, a[1].toLowerCase(Locale.ROOT));
                return true;
            }
            case "key" -> {
                if (plugin.crates() == null || a.length < 3) {
                    plugin.msg(s, "&cUsage: /fc key <player> <crate> [amount]");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    plugin.msg(s, "&cThat player isn't online.");
                    return true;
                }
                int n = a.length > 3 ? (int) Fmt.parse(a[3]) : 1;
                plugin.crates().addKeys(t.getUniqueId(), a[2].toLowerCase(Locale.ROOT), Math.max(1, n));
                plugin.msg(s, "&aGave &f" + t.getName() + " &a" + Math.max(1, n) + "x " + a[2] + " key(s).");
                return true;
            }
            case "keyall" -> {
                if (plugin.crates() == null || a.length < 2) {
                    plugin.msg(s, "&cUsage: /fc keyall <crate> [amount]");
                    return true;
                }
                plugin.crates().keyAll(a[1].toLowerCase(Locale.ROOT), a.length > 2 ? Math.max(1, (int) Fmt.parse(a[2])) : 1);
                return true;
            }
            default -> {
            }
        }
        if (sub.equals("reload")) {
            plugin.reloadAll();
            plugin.msg(s, "&aReloaded config and prices.");
            return true;
        }
        if (!List.of("give", "take", "set").contains(sub) || a.length < 4) {
            plugin.msg(s, "&cUsage: /fc " + sub + " <player> <money|shards> <amount>");
            return true;
        }
        UUID id = null;
        Player online = Bukkit.getPlayerExact(a[1]);
        if (online != null) {
            id = online.getUniqueId();
        } else {
            for (var en : plugin.store().all().entrySet()) {
                if (en.getValue().name.equalsIgnoreCase(a[1])) id = en.getKey();
            }
        }
        if (id == null) {
            plugin.msg(s, "&cPlayer not found.");
            return true;
        }
        double amt = Fmt.parse(a[3]);
        if (Double.isNaN(amt) || amt < 0) {
            plugin.msg(s, "&cInvalid amount.");
            return true;
        }
        boolean shards = a[2].equalsIgnoreCase("shards");
        if (!shards && !a[2].equalsIgnoreCase("money")) {
            plugin.msg(s, "&cType must be money or shards.");
            return true;
        }
        switch (sub) {
            case "give" -> {
                if (shards) plugin.eco().addShards(id, amt);
                else plugin.eco().add(id, amt);
            }
            case "take" -> {
                if (shards) plugin.eco().takeShards(id, amt);
                else plugin.eco().take(id, amt);
            }
            default -> {
                if (shards) plugin.eco().setShards(id, amt);
                else plugin.eco().set(id, amt);
            }
        }
        plugin.msg(s, "&aDone: " + sub + " " + a[3] + " " + a[2] + " for " + a[1] + ".");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("reload", "give", "take", "set", "hub", "airdrop", "lottery", "crate", "key", "keyall"));
        else if (a.length == 2) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (a.length == 3) out.addAll(List.of("money", "shards"));
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}

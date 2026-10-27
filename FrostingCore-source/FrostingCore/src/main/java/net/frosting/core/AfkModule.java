package net.frosting.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** /afk teleports to the AFK zone; standing in the zone earns shards over time. */
public final class AfkModule implements CommandExecutor {
    private final FrostingCore plugin;
    private final Map<UUID, Integer> counters = new HashMap<>();
    private final Map<UUID, Integer> keyCounters = new HashMap<>();

    public AfkModule(FrostingCore plugin) {
        this.plugin = plugin;
    }

    private boolean inZone(Player p, Location zone) {
        if (zone == null || !p.getWorld().equals(zone.getWorld())) return false;
        double r = plugin.getConfig().getDouble("afk.radius", 15);
        return p.getLocation().distanceSquared(zone) <= r * r;
    }

    public void tickSecond() {
        Location zone = plugin.serverData().loc("afk");
        int interval = Math.max(1, plugin.getConfig().getInt("afk.interval", 60));
        double reward = plugin.getConfig().getDouble("afk.shards", 5);
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!inZone(p, zone)) {
                counters.remove(id);
                keyCounters.remove(id);
                continue;
            }
            int keyEvery = plugin.getConfig().getInt("afk.key-interval", 600);
            if (keyEvery > 0 && plugin.crates() != null) {
                int kc = keyCounters.merge(id, 1, Integer::sum);
                if (kc >= keyEvery) {
                    keyCounters.put(id, 0);
                    String crate = plugin.getConfig().getString("afk.key-crate", "common");
                    plugin.crates().addKeys(id, crate, 1);
                    plugin.msg(p, "&dAFK reward: &7you got a &f" + crate + " &7crate key! Use &f/crates&7.");
                }
            }
            int c = counters.merge(id, 1, Integer::sum);
            if (c >= interval) {
                counters.put(id, 0);
                plugin.eco().addShards(id, reward);
                p.sendActionBar(Text.c("&b+" + Fmt.shortNum(reward) + " shards &7(AFK)"));
            } else {
                p.sendActionBar(Text.c("&7AFK zone &8- &b" + (interval - c) + "s &7until +" + Fmt.shortNum(reward) + " shards"));
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (cmd.getName().toLowerCase(Locale.ROOT).equals("setafk")) {
            if (!p.hasPermission("frostingcore.admin")) {
                plugin.msg(p, "&cYou don't have permission.");
                return true;
            }
            plugin.serverData().setLoc("afk", p.getLocation());
            plugin.msg(p, "&aAFK zone set here (radius " + plugin.getConfig().getDouble("afk.radius", 15) + ").");
            return true;
        }
        Location zone = plugin.serverData().loc("afk");
        if (zone == null) {
            plugin.msg(p, "&cThe AFK zone hasn't been set yet.");
            return true;
        }
        plugin.teleports().teleport(p, zone, "Teleporting to AFK", null);
        return true;
    }
}

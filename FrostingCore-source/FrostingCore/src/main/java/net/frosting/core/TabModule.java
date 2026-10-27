package net.frosting.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The tab list: header, footer, and each player's name with a rank prefix (rank = permission node). */
public final class TabModule implements Listener {
    private record Rank(String permission, String prefix) {
    }

    private final FrostingCore plugin;

    public TabModule(FrostingCore plugin) {
        this.plugin = plugin;
        long every = Math.max(10, plugin.getConfig().getLong("tab.update-ticks", 40));
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 40L, every);
    }

    private List<Rank> ranks() {
        List<Rank> out = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("tab.ranks")) {
            int bar = line.indexOf('|');
            if (bar > 0) out.add(new Rank(line.substring(0, bar).trim(), line.substring(bar + 1)));
        }
        return out;
    }

    private String prefixFor(Player p, List<Rank> ranks) {
        for (Rank r : ranks) {
            if (p.hasPermission(r.permission())) return r.prefix();
        }
        return plugin.getConfig().getString("tab.default-prefix", "&7");
    }

    private String fill(String text, Player p, String prefix) {
        PlayerStore.Data d = plugin.store().get(p.getUniqueId());
        double[] tps = Bukkit.getTPS();
        return text
                .replace("%prefix%", prefix)
                .replace("%player%", p.getName())
                .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%max%", String.valueOf(Bukkit.getMaxPlayers()))
                .replace("%ping%", String.valueOf(p.getPing()))
                .replace("%tps%", String.format(Locale.US, "%.1f", tps.length > 0 ? Math.min(20.0, tps[0]) : 20.0))
                .replace("%money%", Fmt.money(d.money))
                .replace("%shards%", Fmt.shortNum(d.shards))
                .replace("%address%", plugin.getConfig().getString("server-address", "dayglow.pixelforge.gg"));
    }

    public void refreshAll() {
        List<Rank> ranks = ranks();
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p, ranks);
    }

    private void refresh(Player p, List<Rank> ranks) {
        String prefix = prefixFor(p, ranks);
        String header = String.join("\n", plugin.getConfig().getStringList("tab.header"));
        String footer = String.join("\n", plugin.getConfig().getStringList("tab.footer"));
        p.sendPlayerListHeaderAndFooter(Text.c(fill(header, p, prefix)), Text.c(fill(footer, p, prefix)));
        p.playerListName(Text.c(fill(plugin.getConfig().getString("tab.name-format", "%prefix%%player%"), p, prefix)));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) refresh(p, ranks());
        }, 10L);
    }
}

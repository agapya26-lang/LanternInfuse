package net.frosting.core;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The sidebar: money, shards, kills, deaths, playtime, ping. Lines live in config.yml. */
public final class ScoreboardModule implements Listener, CommandExecutor {
    private static final class Board {
        final Scoreboard sb;
        final Team[] teams;

        Board(Scoreboard sb, Team[] teams) {
            this.sb = sb;
            this.teams = teams;
        }
    }

    private final FrostingCore plugin;
    private final Map<UUID, Board> boards = new HashMap<>();
    private String title;
    private List<String> lines;

    public ScoreboardModule(FrostingCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        title = plugin.getConfig().getString("scoreboard.title", "&d&lFROSTING SMP");
        lines = new ArrayList<>(plugin.getConfig().getStringList("scoreboard.lines"));
        if (lines.size() > 15) lines = new ArrayList<>(lines.subList(0, 15));
        // rebuild everyone's board on next tick
        for (Player p : Bukkit.getOnlinePlayers()) remove(p);
    }

    private Board create(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = sb.registerNewObjective("lcboard", Criteria.DUMMY, Text.c(title));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        obj.numberFormat(NumberFormat.blank());
        int n = lines.size();
        Team[] teams = new Team[n];
        for (int i = 0; i < n; i++) {
            String entry = "§" + Integer.toHexString(i) + "§r";
            Team t = sb.registerNewTeam("l" + i);
            t.addEntry(entry);
            obj.getScore(entry).setScore(n - i);
            teams[i] = t;
        }
        p.setScoreboard(sb);
        return new Board(sb, teams);
    }

    private void remove(Player p) {
        if (boards.remove(p.getUniqueId()) != null) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    private String fill(String line, Player p, PlayerStore.Data d) {
        return line
                .replace("%money%", Fmt.money(d.money))
                .replace("%shards%", Fmt.shortNum(d.shards))
                .replace("%kills%", String.valueOf(d.kills))
                .replace("%deaths%", String.valueOf(d.deaths))
                .replace("%playtime%", Fmt.duration(d.playSeconds))
                .replace("%ping%", String.valueOf(p.getPing()))
                .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%player%", p.getName())
                .replace("%address%", plugin.getConfig().getString("server-address", "dayglow.pixelforge.gg"));
    }

    public void tickSecond() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerStore.Data d = plugin.store().get(p.getUniqueId());
            if (!d.scoreboard) {
                remove(p);
                continue;
            }
            Board b = boards.get(p.getUniqueId());
            if (b == null) {
                b = create(p);
                boards.put(p.getUniqueId(), b);
            }
            for (int i = 0; i < b.teams.length; i++) {
                b.teams[i].prefix(Text.c(fill(lines.get(i), p, d)));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        PlayerStore.Data d = plugin.store().get(p.getUniqueId());
        d.scoreboard = !d.scoreboard;
        plugin.store().dirty();
        plugin.msg(p, "Scoreboard " + (d.scoreboard ? "&aenabled" : "&cdisabled") + "&7.");
        if (!d.scoreboard) remove(p);
        return true;
    }
}

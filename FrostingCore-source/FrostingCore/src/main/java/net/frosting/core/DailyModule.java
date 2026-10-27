package net.frosting.core;

import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.time.Duration;

/** /daily: a reward every day, bigger the longer your streak. Miss two days and it resets. */
public final class DailyModule implements CommandExecutor, Listener {
    private static final long COOLDOWN = 20L * 3_600_000L;
    private static final long RESET_AFTER = 48L * 3_600_000L;

    private final FrostingCore plugin;

    public DailyModule(FrostingCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        PlayerStore.Data d = plugin.store().get(p.getUniqueId());
        long now = System.currentTimeMillis();
        long since = now - d.lastDaily;
        if (d.lastDaily != 0 && since < COOLDOWN) {
            plugin.msg(p, "&cYour next daily reward is in &f" + Fmt.duration((COOLDOWN - since) / 1000) + "&c. Streak: &6" + d.streak);
            return true;
        }
        if (d.lastDaily != 0 && since > RESET_AFTER) d.streak = 0;
        d.streak++;
        d.lastDaily = now;

        double money = plugin.getConfig().getDouble("daily.money-base", 500)
                + plugin.getConfig().getDouble("daily.money-step", 250) * (Math.min(d.streak, 30) - 1);
        double shards = plugin.getConfig().getDouble("daily.shards-base", 5) + d.streak / 2;
        boolean weekly = d.streak % 7 == 0;
        if (weekly) {
            money += plugin.getConfig().getDouble("daily.weekly-bonus", 5000);
            String key = plugin.getConfig().getString("daily.weekly-key", "prime");
            if (plugin.crates() != null && !key.isBlank()) plugin.crates().addKeys(p.getUniqueId(), key, 1);
        }
        plugin.eco().add(p.getUniqueId(), money);
        plugin.eco().addShards(p.getUniqueId(), shards);
        plugin.store().dirty();

        p.showTitle(Title.title(Text.c("&d&l✦ DAILY REWARD ✦"),
                Text.c("&a+" + Fmt.money(money) + " &8• &b+" + Fmt.shortNum(shards) + " shards &8• &eDay " + d.streak),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(600))));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        if (weekly) {
            Bukkit.broadcast(Text.c(plugin.prefix() + "&e" + p.getName() + " &7hit a &6" + d.streak + "-day streak&7 and got a bonus!"));
            Fireworks.burst(p.getLocation().add(0, 1, 0));
        }
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            PlayerStore.Data d = plugin.store().get(p.getUniqueId());
            if (d.lastDaily == 0 || System.currentTimeMillis() - d.lastDaily >= COOLDOWN) {
                p.sendMessage(Text.c(plugin.prefix() + "&eYour daily reward is ready! ")
                        .append(Text.c("&a&l[CLAIM]").clickEvent(ClickEvent.runCommand("/daily"))));
            }
        }, 100L);
    }
}

package net.frosting.core;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;

/** Join / quit messages, the welcome title, and a firework for first-time players. */
public final class WelcomeModule implements Listener {
    private final FrostingCore plugin;

    public WelcomeModule(FrostingCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        boolean first = !p.hasPlayedBefore();
        if (first) {
            e.joinMessage(Text.c("&6✦ &fWelcome &e" + p.getName() + " &fto &6Frosting SMP&f! &7(player #"
                    + (plugin.store().all().size() + 1) + ")"));
        } else {
            e.joinMessage(Text.c("&8[&a+&8] &7" + p.getName()));
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.showTitle(Title.title(Text.c("&d&l✦ FROSTING SMP ✦"),
                    Text.c(first ? "&eWelcome, &f" + p.getName() + "&e!" : "&7Welcome back, &f" + p.getName()),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofMillis(800))));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
            plugin.msg(p, "Join our Discord with &b/discord&7!");
            if (first) Fireworks.burst(p.getLocation().add(0, 1, 0));
        }, 30L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        e.quitMessage(Text.c("&8[&c-&8] &7" + e.getPlayer().getName()));
    }
}

package net.frosting.core;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** The little toggles Donut players use every day: /nv (night vision) and /hidechat. */
public final class UtilityModule implements CommandExecutor, Listener {
    private final FrostingCore plugin;
    private final Set<UUID> chatHidden = new HashSet<>();

    public UtilityModule(FrostingCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("discord")) {
            String link = plugin.getConfig().getString("discord.link", "https://discord.gg/vPeQfEmyh");
            sender.sendMessage(Text.c(plugin.prefix() + "&d&lJoin our Discord!"));
            sender.sendMessage(Text.c("  &b&n" + link)
                    .clickEvent(ClickEvent.openUrl(link))
                    .hoverEvent(HoverEvent.showText(Text.c("&7Click to open the invite"))));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "nv" -> {
                if (p.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
                    p.removePotionEffect(PotionEffectType.NIGHT_VISION);
                    plugin.msg(p, "Night vision &coff&7.");
                } else {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false, false));
                    plugin.msg(p, "Night vision &aon&7.");
                }
            }
            case "hidechat" -> {
                if (!chatHidden.remove(p.getUniqueId())) {
                    chatHidden.add(p.getUniqueId());
                    plugin.msg(p, "Chat from other players is now &chidden&7. Use &f/hidechat &7again to show it.");
                } else {
                    plugin.msg(p, "Chat is &avisible &7again.");
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        if (chatHidden.isEmpty()) return;
        e.viewers().removeIf(a -> a instanceof Player v && chatHidden.contains(v.getUniqueId()) && !v.equals(e.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        chatHidden.remove(e.getPlayer().getUniqueId());
    }
}

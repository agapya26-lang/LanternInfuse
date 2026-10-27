package net.frosting.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** /tpa /tpahere /tpaccept /tpdeny with clickable buttons. */
public final class TpaModule implements CommandExecutor, TabCompleter {
    private record Request(UUID from, boolean here, long expires) {
    }

    private final FrostingCore plugin;
    private final Map<UUID, Request> incoming = new HashMap<>();

    public TpaModule(FrostingCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "tpa" -> request(p, args, false);
            case "tpahere" -> request(p, args, true);
            case "tpaccept" -> accept(p);
            case "tpdeny" -> deny(p);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void request(Player p, String[] args, boolean here) {
        if (args.length < 1) {
            plugin.msg(p, "&cUsage: /" + (here ? "tpahere" : "tpa") + " <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            plugin.msg(p, "&cThat player isn't online.");
            return;
        }
        if (target.equals(p)) {
            plugin.msg(p, "&cYou can't teleport to yourself.");
            return;
        }
        long expire = plugin.getConfig().getInt("tpa.expire-seconds", 60) * 1000L;
        incoming.put(target.getUniqueId(), new Request(p.getUniqueId(), here, System.currentTimeMillis() + expire));
        plugin.msg(p, "&aRequest sent to &f" + target.getName() + "&a.");
        Component line = Text.c(plugin.prefix() + "&f" + p.getName() + " &7wants "
                + (here ? "you to teleport to them" : "to teleport to you") + ". ")
                .append(Text.c("&a&l[ACCEPT]").clickEvent(ClickEvent.runCommand("/tpaccept")))
                .append(Text.c(" "))
                .append(Text.c("&c&l[DENY]").clickEvent(ClickEvent.runCommand("/tpdeny")));
        target.sendMessage(line);
    }

    private Request take(Player p) {
        Request r = incoming.remove(p.getUniqueId());
        if (r == null) {
            plugin.msg(p, "&cYou have no pending teleport requests.");
            return null;
        }
        if (System.currentTimeMillis() > r.expires()) {
            plugin.msg(p, "&cThat request has expired.");
            return null;
        }
        return r;
    }

    private void accept(Player me) {
        Request r = take(me);
        if (r == null) return;
        Player other = Bukkit.getPlayer(r.from());
        if (other == null) {
            plugin.msg(me, "&cThat player went offline.");
            return;
        }
        plugin.msg(me, "&aRequest accepted.");
        plugin.msg(other, "&f" + me.getName() + " &aaccepted your request.");
        if (r.here()) {
            plugin.teleports().teleport(me, other.getLocation(), "Teleporting to " + other.getName(), null);
        } else {
            plugin.teleports().teleport(other, me.getLocation(), "Teleporting to " + me.getName(), null);
        }
    }

    private void deny(Player me) {
        Request r = take(me);
        if (r == null) return;
        plugin.msg(me, "&cRequest denied.");
        Player other = Bukkit.getPlayer(r.from());
        if (other != null) plugin.msg(other, "&f" + me.getName() + " &cdenied your request.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        String n = cmd.getName().toLowerCase(Locale.ROOT);
        if (args.length == 1 && (n.equals("tpa") || n.equals("tpahere"))) {
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getName().toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(pl.getName());
            }
        }
        return out;
    }
}

package net.frosting.core;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** /lottery: everyone buys tickets, one winner takes the pot on a timer. */
public final class LotteryModule implements CommandExecutor, TabCompleter {
    private final FrostingCore plugin;
    private final Persist persist;
    private final Map<UUID, Integer> tickets = new LinkedHashMap<>();
    private final Random rng = new Random();
    private double pot;
    private long nextDraw;

    public LotteryModule(FrostingCore plugin) {
        this.plugin = plugin;
        this.persist = new Persist(plugin, "lottery.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(persist.file());
        pot = y.getDouble("pot", 0);
        nextDraw = y.getLong("next-draw", 0);
        ConfigurationSection t = y.getConfigurationSection("tickets");
        if (t != null) {
            for (String k : t.getKeys(false)) {
                try {
                    tickets.put(UUID.fromString(k), t.getInt(k));
                } catch (IllegalArgumentException ignored) {
                    // skip
                }
            }
        }
        if (nextDraw == 0) schedule();
    }

    private void schedule() {
        nextDraw = System.currentTimeMillis() + plugin.getConfig().getLong("lottery.interval-minutes", 60) * 60_000L;
        persist.dirty();
    }

    private String toYaml() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("pot", pot);
        y.set("next-draw", nextDraw);
        for (Map.Entry<UUID, Integer> en : tickets.entrySet()) y.set("tickets." + en.getKey(), en.getValue());
        return y.saveToString();
    }

    public void flushAsync() {
        persist.flushAsync(this::toYaml);
    }

    public void flushNow() {
        persist.flushNow(this::toYaml);
    }

    public void tickSecond() {
        if (System.currentTimeMillis() >= nextDraw) draw();
    }

    public void draw() {
        double refundPrice = plugin.getConfig().getDouble("lottery.ticket-price", 1000);
        int total = tickets.values().stream().mapToInt(Integer::intValue).sum();
        int minPlayers = plugin.getConfig().getInt("lottery.min-players", 2);
        if (tickets.size() < minPlayers) {
            if (total > 0) {
                for (Map.Entry<UUID, Integer> en : tickets.entrySet()) plugin.eco().add(en.getKey(), en.getValue() * refundPrice);
                Bukkit.broadcast(Text.c(plugin.prefix() + "&6Lottery &7cancelled: not enough players. Tickets were refunded."));
            }
            tickets.clear();
            pot = 0;
            schedule();
            return;
        }
        int pick = rng.nextInt(total);
        UUID winner = null;
        for (Map.Entry<UUID, Integer> en : tickets.entrySet()) {
            pick -= en.getValue();
            if (pick < 0) {
                winner = en.getKey();
                break;
            }
        }
        if (winner == null) {
            schedule();
            return;
        }
        double tax = plugin.getConfig().getDouble("lottery.tax-percent", 10) / 100.0;
        double payout = Math.round(pot * (1 - tax) * 100.0) / 100.0;
        double chance = 100.0 * tickets.get(winner) / total;
        plugin.eco().add(winner, payout);
        PlayerStore.Data wd = plugin.store().peek(winner);
        String name = wd == null || wd.name.isEmpty() ? "Someone" : wd.name;
        Bukkit.broadcast(Text.c(plugin.prefix() + "&d&l✦ LOTTERY &e" + name + " &7won &a" + Fmt.money(payout)
                + " &7(" + String.format(Locale.US, "%.1f", chance) + "% chance)!"));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(Title.title(Text.c("&d&l✦ LOTTERY ✦"), Text.c("&e" + name + " &7won &a" + Fmt.money(payout)),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(800))));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
        }
        Player wp = Bukkit.getPlayer(winner);
        if (wp != null) Fireworks.burst(wp.getLocation().add(0, 1, 0));
        tickets.clear();
        pot = plugin.getConfig().getDouble("lottery.seed", 0);
        schedule();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        double price = plugin.getConfig().getDouble("lottery.ticket-price", 1000);
        int max = plugin.getConfig().getInt("lottery.max-tickets-per-player", 50);
        int mine = tickets.getOrDefault(p.getUniqueId(), 0);
        if (args.length >= 1 && args[0].equalsIgnoreCase("buy")) {
            int n;
            try {
                n = args.length > 1 ? Integer.parseInt(args[1]) : 1;
            } catch (NumberFormatException ex) {
                plugin.msg(p, "&cUsage: /lottery buy <amount>");
                return true;
            }
            if (n < 1 || mine + n > max) {
                plugin.msg(p, "&cYou can hold at most &f" + max + " &ctickets (you have " + mine + ").");
                return true;
            }
            double cost = n * price;
            if (!plugin.eco().take(p.getUniqueId(), cost)) {
                plugin.msg(p, "&cThat costs &f" + Fmt.money(cost) + "&c.");
                return true;
            }
            tickets.merge(p.getUniqueId(), n, Integer::sum);
            pot += cost;
            persist.dirty();
            plugin.msg(p, "&aBought &f" + n + " &aticket(s) for &f" + Fmt.money(cost) + "&a. Pot: &6" + Fmt.money(pot));
            return true;
        }
        long left = Math.max(0, (nextDraw - System.currentTimeMillis()) / 1000);
        int total = tickets.values().stream().mapToInt(Integer::intValue).sum();
        plugin.msg(p, "&d&lLottery &8- &7Pot: &6" + Fmt.money(pot) + " &8| &7Draw in &f" + Fmt.duration(left));
        p.sendMessage(Text.c(" &7Your tickets: &f" + mine + "&7/" + max + " &8| &7All tickets: &f" + total
                + " &8| &7Price: &a" + Fmt.money(price)));
        p.sendMessage(Text.c(" &eBuy with &f/lottery buy <amount>"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1 && "buy".startsWith(args[0].toLowerCase(Locale.ROOT))) out.add("buy");
        return out;
    }
}

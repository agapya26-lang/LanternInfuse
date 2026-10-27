package net.frosting.core;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * FrostingCore: the economy / teleport / marketplace backbone for Frosting SMP.
 * Every module can be switched off in config.yml under "modules".
 */
public final class FrostingCore extends JavaPlugin {
    private PlayerStore store;
    private Economy economy;
    private ServerData serverData;
    private Teleports teleports;
    private CombatModule combat;
    private SpawnModule spawn;
    private StatsModule stats;
    private ScoreboardModule board;
    private AfkModule afk;
    private SellModule sell;
    private AuctionHouse auction;
    private HubModule hub;
    private GuardModule guard;
    private CratesModule crates;
    private BountyModule bounty;
    private OrdersModule orders;
    private LotteryModule lottery;
    private AirdropModule airdrop;
    private ShopModule shop;
    private long seconds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "worth.yml").exists()) saveResource("worth.yml", false);

        store = new PlayerStore(this);
        economy = new Economy(store);
        serverData = new ServerData(this);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new Gui.Events(), this);

        teleports = new Teleports(this);
        pm.registerEvents(teleports, this);

        if (on("combat")) {
            combat = new CombatModule(this);
            pm.registerEvents(combat, this);
        }
        if (on("stats")) {
            stats = new StatsModule(this);
            pm.registerEvents(stats, this);
            for (String c : new String[]{"bal", "pay", "baltop", "top", "shards", "stats"}) bind(c, stats);
        }
        if (on("spawn")) {
            spawn = new SpawnModule(this);
            pm.registerEvents(spawn, this);
            bind("spawn", spawn);
            bind("setspawn", spawn);
        }
        if (on("rtp")) bind("rtp", new RtpModule(this));
        if (on("homes")) {
            HomesModule homes = new HomesModule(this);
            for (String c : new String[]{"sethome", "home", "delhome", "homes"}) bind(c, homes);
        }
        if (on("tpa")) {
            TpaModule tpa = new TpaModule(this);
            for (String c : new String[]{"tpa", "tpahere", "tpaccept", "tpdeny"}) bind(c, tpa);
        }
        if (on("scoreboard")) {
            board = new ScoreboardModule(this);
            pm.registerEvents(board, this);
            bind("sb", board);
        }
        if (on("afk")) {
            afk = new AfkModule(this);
            bind("afk", afk);
            bind("setafk", afk);
        }
        if (on("sell")) {
            sell = new SellModule(this);
            bind("sell", sell);
            bind("worth", sell);
        }
        if (on("auction")) {
            auction = new AuctionHouse(this);
            pm.registerEvents(auction, this);
            bind("ah", auction);
        }
        if (on("welcome")) pm.registerEvents(new WelcomeModule(this), this);
        if (on("tab")) pm.registerEvents(new TabModule(this), this);
        if (on("daily")) {
            DailyModule daily = new DailyModule(this);
            pm.registerEvents(daily, this);
            bind("daily", daily);
        }
        if (on("hub")) hub = new HubModule(this);
        if (on("guard")) {
            guard = new GuardModule(this);
            pm.registerEvents(guard, this);
            bind("fg", guard);
        }
        if (on("shop")) {
            shop = new ShopModule(this);
            bind("shop", shop);
        }
        if (on("crates")) {
            crates = new CratesModule(this);
            pm.registerEvents(crates, this);
            bind("crates", crates);
            bind("keys", crates);
        }
        if (on("shardshop")) bind("shardshop", new ShardShopModule(this));
        if (on("bounty")) {
            bounty = new BountyModule(this);
            bind("bounty", bounty);
        }
        if (on("orders")) {
            orders = new OrdersModule(this);
            bind("orders", orders);
        }
        if (on("lottery")) {
            lottery = new LotteryModule(this);
            bind("lottery", lottery);
        }
        if (on("airdrop")) {
            airdrop = new AirdropModule(this);
            pm.registerEvents(airdrop, this);
            bind("airdrop", airdrop);
        }
        if (on("utility")) {
            UtilityModule util = new UtilityModule(this);
            pm.registerEvents(util, this);
            bind("nv", util);
            bind("hidechat", util);
            bind("discord", util);
        }
        bind("frostingcore", new AdminCommand(this));

        getServer().getScheduler().runTaskTimer(this, this::tickSecond, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, () -> {
            store.flushAsync();
            if (auction != null) auction.flushAsync();
            if (orders != null) orders.flushAsync();
            if (lottery != null) lottery.flushAsync();
        }, 20L * 60, 20L * 60);
        getLogger().info("FrostingCore enabled - Server of Frosting SMP team.");
    }

    @Override
    public void onDisable() {
        // close menus first so sell-menu items are returned/sold before we save
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Gui) p.closeInventory();
        }
        if (airdrop != null) airdrop.shutdown();
        if (store != null) store.flushNow();
        if (auction != null) auction.flushNow();
        if (orders != null) orders.flushNow();
        if (lottery != null) lottery.flushNow();
    }

    private void tickSecond() {
        seconds++;
        if (stats != null) stats.tickSecond();
        if (combat != null) combat.tickSecond();
        if (board != null) board.tickSecond();
        if (afk != null) afk.tickSecond();
        if (auction != null) auction.tickSecond(seconds);
        if (lottery != null) lottery.tickSecond();
        if (airdrop != null) airdrop.tickSecond();
        if (crates != null) crates.tickSecond();
    }

    public void reloadAll() {
        reloadConfig();
        if (sell != null) sell.load();
        if (shop != null) shop.load();
        if (board != null) board.reload();
    }

    private boolean on(String module) {
        return getConfig().getBoolean("modules." + module, true);
    }

    private void bind(String name, CommandExecutor ex) {
        PluginCommand pc = getCommand(name);
        if (pc == null) {
            getLogger().warning("Command missing in plugin.yml: " + name);
            return;
        }
        pc.setExecutor(ex);
        if (ex instanceof TabCompleter tc) pc.setTabCompleter(tc);
    }

    // ---- accessors (null when a module is switched off) ----

    public PlayerStore store() {
        return store;
    }

    public Economy eco() {
        return economy;
    }

    public ServerData serverData() {
        return serverData;
    }

    public Teleports teleports() {
        return teleports;
    }

    public CombatModule combat() {
        return combat;
    }

    public SpawnModule spawn() {
        return spawn;
    }

    public HubModule hub() {
        return hub;
    }

    public CratesModule crates() {
        return crates;
    }

    public BountyModule bounty() {
        return bounty;
    }

    public AirdropModule airdrop() {
        return airdrop;
    }

    public LotteryModule lottery() {
        return lottery;
    }

    public String prefix() {
        return getConfig().getString("prefix", "&8[&d&lFrosting&b&lSMP&8] &7");
    }

    public void msg(CommandSender to, String text) {
        to.sendMessage(Text.c(prefix() + text));
    }
}

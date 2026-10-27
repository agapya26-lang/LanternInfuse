package net.frosting.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Everything we keep per player: money, shards, stats, homes, settings. Saved to players.yml. */
public final class PlayerStore {
    public static final class Data {
        public String name = "";
        public double money;
        public double shards;
        public int kills;
        public int deaths;
        public long playSeconds;
        public boolean scoreboard = true;
        public long lastDaily;
        public int streak;
        public double bounty;
        public final Map<String, Integer> keys = new LinkedHashMap<>();
        public final Map<String, String> homes = new LinkedHashMap<>();
    }

    private final FrostingCore plugin;
    private final Persist persist;
    private final Map<UUID, Data> map = new HashMap<>();

    public PlayerStore(FrostingCore plugin) {
        this.plugin = plugin;
        this.persist = new Persist(plugin, "players.yml");
        load();
    }

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(persist.file());
        ConfigurationSection root = y.getConfigurationSection("players");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            Data d = new Data();
            d.name = s.getString("name", "");
            d.money = s.getDouble("money", 0);
            d.shards = s.getDouble("shards", 0);
            d.kills = s.getInt("kills", 0);
            d.deaths = s.getInt("deaths", 0);
            d.playSeconds = s.getLong("playtime", 0);
            d.scoreboard = s.getBoolean("scoreboard", true);
            d.lastDaily = s.getLong("last-daily", 0);
            d.streak = s.getInt("streak", 0);
            d.bounty = s.getDouble("bounty", 0);
            ConfigurationSection ks = s.getConfigurationSection("keys");
            if (ks != null) {
                for (String k : ks.getKeys(false)) d.keys.put(k, ks.getInt(k));
            }
            ConfigurationSection h = s.getConfigurationSection("homes");
            if (h != null) {
                for (String n : h.getKeys(false)) d.homes.put(n, h.getString(n, ""));
            }
            map.put(id, d);
        }
    }

    /** Gets (or creates, with the starting balance) a player's data. */
    public Data get(UUID id) {
        return map.computeIfAbsent(id, k -> {
            Data d = new Data();
            d.money = plugin.getConfig().getDouble("economy.starting-money", 1000);
            persist.dirty();
            return d;
        });
    }

    public Data peek(UUID id) {
        return map.get(id);
    }

    public Map<UUID, Data> all() {
        return map;
    }

    public void dirty() {
        persist.dirty();
    }

    public void flushAsync() {
        persist.flushAsync(this::toYaml);
    }

    public void flushNow() {
        persist.flushNow(this::toYaml);
    }

    private String toYaml() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Data> en : map.entrySet()) {
            String base = "players." + en.getKey();
            Data d = en.getValue();
            y.set(base + ".name", d.name);
            y.set(base + ".money", d.money);
            y.set(base + ".shards", d.shards);
            y.set(base + ".kills", d.kills);
            y.set(base + ".deaths", d.deaths);
            y.set(base + ".playtime", d.playSeconds);
            y.set(base + ".scoreboard", d.scoreboard);
            y.set(base + ".last-daily", d.lastDaily);
            y.set(base + ".streak", d.streak);
            y.set(base + ".bounty", d.bounty);
            for (Map.Entry<String, Integer> k : d.keys.entrySet()) {
                if (k.getValue() > 0) y.set(base + ".keys." + k.getKey(), k.getValue());
            }
            for (Map.Entry<String, String> h : d.homes.entrySet()) {
                y.set(base + ".homes." + h.getKey(), h.getValue());
            }
        }
        return y.saveToString();
    }
}

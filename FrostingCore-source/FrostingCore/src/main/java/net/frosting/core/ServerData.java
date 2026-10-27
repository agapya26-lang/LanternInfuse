package net.frosting.core;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

/** Tiny store for server-wide locations (spawn, afk zone...). */
public final class ServerData {
    private final JavaPlugin plugin;
    private final File file;
    private final YamlConfiguration y;

    public ServerData(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "server.yml");
        this.y = YamlConfiguration.loadConfiguration(file);
    }

    public Location loc(String key) {
        return Locs.dec(y.getString(key));
    }

    public String str(String key, String def) {
        return y.getString(key, def);
    }

    public void setStr(String key, String value) {
        y.set(key, value);
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save server.yml: " + ex.getMessage());
        }
    }

    public void setLoc(String key, Location l) {
        y.set(key, Locs.enc(l));
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save server.yml: " + ex.getMessage());
        }
    }
}

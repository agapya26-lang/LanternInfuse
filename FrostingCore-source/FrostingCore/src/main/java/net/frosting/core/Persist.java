package net.frosting.core;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;

/** Safe YAML saving: text is built on the main thread, written on another, via a temp file. */
public final class Persist {
    private final JavaPlugin plugin;
    private final File file;
    private volatile boolean dirty;
    private volatile boolean saving;

    public Persist(JavaPlugin plugin, String name) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), name);
    }

    public File file() {
        return file;
    }

    public void dirty() {
        dirty = true;
    }

    public void flushAsync(Supplier<String> yaml) {
        if (!dirty || saving) return;
        final String text = yaml.get();
        dirty = false;
        saving = true;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                write(text);
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not save " + file.getName() + ": " + ex.getMessage());
                dirty = true;
            } finally {
                saving = false;
            }
        });
    }

    public void flushNow(Supplier<String> yaml) {
        if (!dirty) return;
        try {
            write(yaml.get());
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save " + file.getName() + ": " + ex.getMessage());
        }
    }

    private synchronized void write(String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(file.getPath() + ".tmp");
        Files.writeString(tmp.toPath(), text, StandardCharsets.UTF_8);
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}

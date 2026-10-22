package net.lanternlight.infuse;

import org.bukkit.Color;
import org.bukkit.Material;

/** Per-effect values loaded from config.yml. Times are in seconds. */
public record EffectSettings(
        String name, String icon, Material material, String hex, Color color,
        int cooldown, int augCooldown, int duration, String passive, String spark) {
}

package net.frosting.core;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;

public final class Util {
    private Util() {
    }

    /** The player responsible for a damage source (melee or projectile), or null. */
    public static Player playerSource(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player shooter) return shooter;
        return null;
    }
}

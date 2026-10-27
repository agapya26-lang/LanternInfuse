package net.frosting.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** Location <-> "world;x;y;z;yaw;pitch". */
public final class Locs {
    private Locs() {
    }

    public static String enc(Location l) {
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
    }

    public static Location dec(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(";");
        if (p.length < 6) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    Float.parseFloat(p[4]), Float.parseFloat(p[5]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}

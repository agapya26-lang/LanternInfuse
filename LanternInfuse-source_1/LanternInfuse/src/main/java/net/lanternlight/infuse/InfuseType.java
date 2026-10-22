package net.lanternlight.infuse;

import java.util.Locale;

/** The 12 effects. Any effect can go in either of the player's two slots. */
public enum InfuseType {
    STRENGTH, THUNDER, HASTE, FROST, HEART, REGEN, FEATHER, INVIS, EMERALD, SPEED, FIRE, OCEAN;

    /** The two effect slots a player has. */
    public enum Slot {
        ONE, TWO;

        public int number() {
            return ordinal() + 1;
        }
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static InfuseType byId(String raw) {
        if (raw == null) return null;
        String s = raw.toLowerCase(Locale.ROOT);
        if (s.equals("invisibility")) s = "invis";
        if (s.equals("regeneration")) s = "regen";
        for (InfuseType t : values()) {
            if (t.id().equals(s)) return t;
        }
        return null;
    }
}

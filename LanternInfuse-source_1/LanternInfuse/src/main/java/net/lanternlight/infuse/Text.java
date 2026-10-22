package net.lanternlight.infuse;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Turns &-coded strings (and &#RRGGBB) into components with italics switched off. */
public final class Text {
    private static final LegacyComponentSerializer SER =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private Text() {
    }

    public static Component c(String s) {
        return SER.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }
}

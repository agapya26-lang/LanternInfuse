package net.frosting.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** &-coded strings (and &#RRGGBB) to components, italics off. */
public final class Text {
    private static final LegacyComponentSerializer SER =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private Text() {
    }

    public static Component c(String s) {
        return SER.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }
}

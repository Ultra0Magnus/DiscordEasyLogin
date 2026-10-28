package io.github.ultra0magnus.discordeasylogin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * MiniMessage rendering with {@code <placeholder>} substitution.
 */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Text() {
    }

    /**
     * @param placeholders alternating name/value pairs, substituted as literal text
     */
    public static Component render(String template, String... placeholders) {
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException("placeholders must be name/value pairs");
        }
        TagResolver[] resolvers = new TagResolver[placeholders.length / 2];
        for (int i = 0; i < resolvers.length; i++) {
            resolvers[i] = Placeholder.unparsed(placeholders[i * 2], placeholders[i * 2 + 1]);
        }
        return MINI.deserialize(template, TagResolver.resolver(resolvers));
    }

    /** Fills {@code <name>} placeholders in a plain-text template (used for the Discord message). */
    public static String fill(String template, String... placeholders) {
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException("placeholders must be name/value pairs");
        }
        String out = template;
        for (int i = 0; i < placeholders.length; i += 2) {
            out = out.replace("<" + placeholders[i] + ">", placeholders[i + 1]);
        }
        return out;
    }
}

package com.geyserextra.paper.util;

import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Diagnostic logging that actually reaches the server console when the
 * operator turns {@code debugMode} on.
 *
 * <p>The plugin's diagnostics were written against {@link Logger#fine}, and
 * none of them have ever been visible. Nothing in the plugin raises a log
 * level, so every {@code fine()} record is discarded before it reaches the
 * console — flipping {@code debugMode} to {@code true} changes only which
 * {@code fine()} calls are <em>reached</em>, not whether any of them print.
 * A deployed server's {@code logs/latest.log} shows it directly: of the calls
 * that run unconditionally during enable, every {@code info()} one is present
 * ("Using configured extension data folder", "Relocalised …") and every
 * {@code fine()} one is missing ("Config loaded from: …", "Player settings
 * manager initialized."). {@code CooldownBridgeListener#diagnostic} reached
 * the same conclusion independently and works around it the same way.</p>
 *
 * <p>That is why this emits at INFO rather than raising a level. A plugin can
 * set its own {@code Logger} level, but the record still has to pass the
 * handler on the server's root logger, which a plugin does not own; getting
 * FINE through reliably means reconfiguring the server's logging backend from
 * inside a plugin, which is not something to do on an operator's behalf. An
 * explicitly opt-in INFO line is the honest version of the same thing.</p>
 *
 * <p>Messages are prefixed so an operator can tell debug output from normal
 * operation, and take a {@link Supplier} so the cost of building a message is
 * not paid while debugging is off.</p>
 */
public final class DebugLog {

    private static final String PREFIX = "[debug] ";

    /**
     * Volatile because it is written once on the main thread during enable and
     * read from the async pack-build and scheduler threads.
     */
    private static volatile boolean enabled;

    private DebugLog() {}

    /** Mirrors {@code general.debugMode}; called once while the plugin enables. */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Logs {@code message} when debugging is on, and evaluates the supplier
     * only in that case.
     */
    public static void log(Logger logger, Supplier<String> message) {
        if (enabled) {
            logger.info(PREFIX + message.get());
        }
    }
}

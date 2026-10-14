package net.mtree.livingplayers.core;

import java.util.logging.Logger;

/** Runtime-toggleable debug logging (config.yml debug: true, or /mtreebots debug toggle). */
public final class Debug {
    private Debug() {}
    private static volatile boolean enabled;
    private static volatile Logger logger = Logger.getLogger("MTREELivingPlayers");

    public static void init(Logger l, boolean on) { logger = l; enabled = on; }
    public static boolean enabled() { return enabled; }
    public static boolean toggle() { enabled = !enabled; return enabled; }
    public static void log(String msg) { if (enabled) logger.info("[debug] " + msg); }
}

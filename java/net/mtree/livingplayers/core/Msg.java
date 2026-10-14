package net.mtree.livingplayers.core;

import org.bukkit.command.CommandSender;

/** Tiny chat helper (legacy colour codes in plain strings are still supported by Paper). */
public final class Msg {
    private Msg() {}
    private static final String PREFIX = "§8[§6MTREE Bots§8] ";

    public static void ok(CommandSender s, String m) { s.sendMessage(PREFIX + "§a" + m); }
    public static void info(CommandSender s, String m) { s.sendMessage(PREFIX + "§7" + m); }
    public static void err(CommandSender s, String m) { s.sendMessage(PREFIX + "§c" + m); }
    public static void raw(CommandSender s, String m) { s.sendMessage(m); }
}

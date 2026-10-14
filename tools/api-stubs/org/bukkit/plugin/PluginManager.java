package org.bukkit.plugin;
public interface PluginManager { boolean isPluginEnabled(String n); void registerEvents(org.bukkit.event.Listener l, Plugin p); void disablePlugin(Plugin p); }

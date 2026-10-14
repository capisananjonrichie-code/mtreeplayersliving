package org.bukkit.plugin;
public interface Plugin { java.util.logging.Logger getLogger(); boolean isEnabled(); java.io.File getDataFolder(); java.io.InputStream getResource(String n); void saveResource(String n, boolean r); }

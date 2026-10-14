package org.bukkit.plugin.java;
public abstract class JavaPlugin implements org.bukkit.plugin.Plugin {
 public java.util.logging.Logger getLogger(){return null;} public boolean isEnabled(){return true;} public java.io.File getDataFolder(){return null;} public java.io.InputStream getResource(String n){return null;}
 public void saveResource(String n, boolean r){} public org.bukkit.command.PluginCommand getCommand(String n){return null;} public org.bukkit.Server getServer(){return null;}
 public io.papermc.paper.plugin.configuration.PluginMeta getPluginMeta(){return null;} public void onEnable(){} public void onDisable(){} }

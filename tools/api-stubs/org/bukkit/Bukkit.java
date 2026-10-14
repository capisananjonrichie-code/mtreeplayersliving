package org.bukkit;
import java.util.*; import org.bukkit.entity.Player; import org.bukkit.scheduler.BukkitScheduler; import org.bukkit.plugin.PluginManager; import com.destroystokyo.paper.profile.PlayerProfile;
public final class Bukkit { public static List<World> getWorlds(){return null;} public static World getWorld(UUID u){return null;} public static World getWorld(String n){return null;}
 public static Collection<? extends Player> getOnlinePlayers(){return null;} public static Player getPlayer(UUID u){return null;} public static org.bukkit.entity.Entity getEntity(UUID u){return null;} public static Player getPlayerExact(String n){return null;} public static OfflinePlayer getOfflinePlayerIfCached(String n){return null;}
 public static BukkitScheduler getScheduler(){return null;} public static double[] getTPS(){return null;} public static String getMinecraftVersion(){return null;} public static boolean isPrimaryThread(){return true;}
 public static PlayerProfile createProfile(UUID u,String n){return null;} public static PlayerProfile createProfile(String n){return null;} public static PluginManager getPluginManager(){return null;} }

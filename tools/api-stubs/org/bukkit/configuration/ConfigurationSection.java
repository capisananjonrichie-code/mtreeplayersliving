package org.bukkit.configuration;
import java.util.*; public interface ConfigurationSection { int getInt(String p); int getInt(String p,int d); double getDouble(String p); double getDouble(String p,double d); boolean getBoolean(String p); boolean getBoolean(String p,boolean d);
 String getString(String p); String getString(String p,String d); List<String> getStringList(String p); List<Double> getDoubleList(String p); Set<String> getKeys(boolean deep);
 ConfigurationSection getConfigurationSection(String p); boolean contains(String p); List<Map<?,?>> getMapList(String p); }

package org.bukkit.persistence;
import org.bukkit.NamespacedKey; public interface PersistentDataContainer { <P,C> void set(NamespacedKey k, PersistentDataType<P,C> t, C v); <P,C> C get(NamespacedKey k, PersistentDataType<P,C> t); }

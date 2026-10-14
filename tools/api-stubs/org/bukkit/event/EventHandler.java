package org.bukkit.event;
import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) public @interface EventHandler { EventPriority priority() default EventPriority.NORMAL; boolean ignoreCancelled() default false; }

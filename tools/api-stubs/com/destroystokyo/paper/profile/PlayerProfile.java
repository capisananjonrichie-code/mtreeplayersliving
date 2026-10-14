package com.destroystokyo.paper.profile;
import java.util.*; public interface PlayerProfile { void setProperty(ProfileProperty p); void setProperties(Collection<ProfileProperty> p); Set<ProfileProperty> getProperties(); boolean hasTextures(); java.util.concurrent.CompletableFuture<PlayerProfile> update(); }

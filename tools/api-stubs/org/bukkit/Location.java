package org.bukkit;
import org.bukkit.block.Block;
public class Location {
 public Location(World w,double x,double y,double z){} public Location(World w,double x,double y,double z,float yaw,float pitch){}
 public World getWorld(){return null;} public double getX(){return 0;} public double getY(){return 0;} public double getZ(){return 0;}
 public float getYaw(){return 0;} public float getPitch(){return 0;} public int getBlockX(){return 0;} public int getBlockY(){return 0;} public int getBlockZ(){return 0;}
 public double distanceSquared(Location o){return 0;} public double distance(Location o){return 0;} public Location add(double x,double y,double z){return this;} public org.bukkit.util.Vector toVector(){return null;} public Block getBlock(){return null;} }

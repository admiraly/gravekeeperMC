package com.gravekeeper;

import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

/** A single gravestone: where it is, who owns it, and what it holds. */
public final class Grave {

    final UUID id;
    final UUID owner;
    final String ownerName;
    final UUID world;
    /** Mutable only so a grave can be relocated if its spot gets built over (see GraveStore#relocate). */
    int x, y, z;

    List<ItemStack> items;
    int exp;
    UUID label;

    Grave(UUID id, UUID owner, String ownerName, UUID world, int x, int y, int z,
          List<ItemStack> items, int exp, UUID label) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.items = items;
        this.exp = exp;
        this.label = label;
    }

    String key() {
        return key(world, x, y, z);
    }

    static String key(UUID world, int x, int y, int z) {
        return world + ";" + x + ";" + y + ";" + z;
    }

    static String key(Block block) {
        return key(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    Location center(World w) {
        return new Location(w, x + 0.5, y + 0.5, z + 0.5);
    }
}

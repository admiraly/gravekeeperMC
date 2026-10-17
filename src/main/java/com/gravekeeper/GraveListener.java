package com.gravekeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class GraveListener implements Listener {

    private static final Material GRAVE_BLOCK = Material.MOSSY_COBBLESTONE_WALL;
    /** How far up we look for free space if the player died inside solid blocks. */
    private static final int SEARCH_UP = 10;

    private final JavaPlugin plugin;
    private final GraveStore store;
    private final NamespacedKey labelKey;

    GraveListener(JavaPlugin plugin, GraveStore store) {
        this.plugin = plugin;
        this.store = store;
        this.labelKey = new NamespacedKey(plugin, "grave");
    }

    /** The grave registered at this block, but only while the grave block is actually still there. */
    private Grave graveAt(Block block) {
        return block.getType() == GRAVE_BLOCK ? store.at(block) : null;
    }

    // ---------------------------------------------------------------- death

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        List<ItemStack> items = new ArrayList<>();
        if (!event.getKeepInventory()) {
            for (ItemStack drop : event.getDrops()) {
                if (drop != null && !drop.getType().isAir()) {
                    items.add(drop.clone());
                }
            }
        }
        int exp = event.getKeepLevel() ? 0 : event.getDroppedExp();
        if (items.isEmpty() && exp <= 0) {
            return;
        }

        Block spot = findSpot(player.getLocation());
        if (spot == null) {
            // Nowhere to put a grave: leave vanilla drops alone rather than lose the items.
            return;
        }

        World world = spot.getWorld();
        Grave grave = new Grave(UUID.randomUUID(), player.getUniqueId(), player.getName(),
                world.getUID(), spot.getX(), spot.getY(), spot.getZ(), items, exp, null);

        boolean stored = false;
        try {
            spot.setType(GRAVE_BLOCK);
            grave.label = spawnLabel(grave, world);
            stored = store.add(grave);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create grave", e);
        }
        if (!stored) {
            // Undo the grave and keep vanilla drops, so nothing is lost or duplicated.
            removeBlockAndLabel(grave, world);
            return;
        }

        event.getDrops().clear();
        event.setDroppedExp(0);

        player.sendMessage(Component.text("Your items are safe in a gravestone at ", NamedTextColor.GRAY)
                .append(Component.text(spot.getX() + ", " + spot.getY() + ", " + spot.getZ(), NamedTextColor.YELLOW))
                .append(Component.text(". Right-click it to get them back.", NamedTextColor.GRAY)));
    }

    private UUID spawnLabel(Grave grave, World world) {
        Location at = new Location(world, grave.x + 0.5, grave.y + 1.6, grave.z + 0.5);
        TextDisplay display = world.spawn(at, TextDisplay.class, d -> {
            d.text(Component.text("R.I.P.\n", NamedTextColor.GRAY)
                    .append(Component.text(grave.ownerName, NamedTextColor.WHITE)));
            d.setBillboard(Display.Billboard.CENTER);
            d.getPersistentDataContainer().set(labelKey, PersistentDataType.STRING, grave.id.toString());
        });
        return display.getUniqueId();
    }

    /**
     * Finds a free, reachable block at or near the death location. Falls back to the
     * world spawn if the death spot is unusable (void, Nether roof). Null if nothing fits.
     */
    private Block findSpot(Location death) {
        World world = death.getWorld();
        int x = death.getBlockX();
        int z = death.getBlockZ();
        int y = death.getBlockY();
        if (y < world.getMinHeight()) {
            // Died in the void: use the surface of that column instead.
            y = world.getHighestBlockYAt(x, z) + 1;
        }

        Block spot = search(world, x, y, z);
        if (spot == null || !isReachable(spot)) {
            Location spawn = world.getSpawnLocation();
            spot = search(world, spawn.getBlockX(), world.getHighestBlockYAt(spawn) + 1, spawn.getBlockZ());
            if (spot != null && !isReachable(spot)) {
                spot = null;
            }
        }
        return spot;
    }

    private Block search(World world, int x, int y, int z) {
        int min = world.getMinHeight() + 1;
        int max = world.getMaxHeight() - 2;
        y = Math.max(min, Math.min(max, y));

        Block block = null;
        for (int i = 0; i <= SEARCH_UP && y + i <= max; i++) {
            Block candidate = world.getBlockAt(x, y + i, z);
            if (isFree(candidate) && candidate.getType() != Material.LAVA && store.at(candidate) == null) {
                block = candidate;
                break;
            }
        }
        if (block == null) {
            return null;
        }

        // Died in mid-air (e.g. shot off a cliff): let the grave settle onto the ground.
        while (block.getY() > min) {
            Block below = block.getRelative(0, -1, 0);
            if (!below.isEmpty() || store.at(below) != null) {
                break;
            }
            block = below;
        }
        return block;
    }

    /** True if a normal player can plausibly get to this spot: it has ground and isn't on the Nether roof. */
    private boolean isReachable(Block spot) {
        return spot.getY() < spot.getWorld().getLogicalHeight() && !spot.getRelative(0, -1, 0).isEmpty();
    }

    private boolean isFree(Block block) {
        return block.isEmpty() || block.isLiquid() || block.isReplaceable();
    }

    // ------------------------------------------------------------ collecting

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Grave grave = graveAt(event.getClickedBlock());
        if (grave == null) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        if (event.getHand() == EquipmentSlot.HAND) {
            tryCollect(event.getPlayer(), grave);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Grave grave = graveAt(event.getBlock());
        if (grave == null) {
            return;
        }
        event.setCancelled(true);
        tryCollect(event.getPlayer(), grave);
    }

    private void tryCollect(Player player, Grave grave) {
        if (!player.getUniqueId().equals(grave.owner) && !player.hasPermission(GraveKeeper.BYPASS_PERMISSION)) {
            player.sendMessage(Component.text("This is " + grave.ownerName + "'s gravestone.", NamedTextColor.RED));
            return;
        }

        if (grave.exp > 0) {
            player.giveExp(grave.exp);
            grave.exp = 0;
        }

        PlayerInventory inventory = player.getInventory();
        List<ItemStack> leftover = new ArrayList<>();
        for (ItemStack item : grave.items) {
            if (equipIfEmptyArmorSlot(inventory, item)) {
                continue;
            }
            leftover.addAll(inventory.addItem(item).values());
        }
        grave.items = leftover;

        // Get the items onto the player's disk data before the grave forgets them, so a
        // crash in between can't lose them.
        player.saveData();

        boolean done = leftover.isEmpty();
        boolean saved = done ? store.remove(grave) : store.save();

        World world = Bukkit.getWorld(grave.world);
        if (!saved) {
            player.sendMessage(Component.text(
                    "The gravestone could not be saved to disk. Check the server log and try again.",
                    NamedTextColor.RED));
        } else if (done) {
            removeBlockAndLabel(grave, world);
            if (world != null) {
                Location center = grave.center(world);
                world.playSound(center, Sound.ENTITY_ITEM_PICKUP, 1f, 0.8f);
                world.spawnParticle(Particle.SOUL, center, 12, 0.3, 0.4, 0.3, 0.02);
            }
            player.sendMessage(Component.text("You collected everything from the gravestone.", NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text(
                    "Your inventory is full. Make some room and right-click the gravestone again - the rest is still safe.",
                    NamedTextColor.YELLOW));
        }
    }

    private static boolean equipIfEmptyArmorSlot(PlayerInventory inventory, ItemStack item) {
        if (item.getAmount() != 1) {
            return false;
        }
        EquipmentSlot slot = item.getType().getEquipmentSlot();
        switch (slot) {
            case HEAD, CHEST, LEGS, FEET -> {
                ItemStack current = inventory.getItem(slot);
                if (current.getType().isAir()) {
                    inventory.setItem(slot, item);
                    return true;
                }
            }
            default -> { }
        }
        return false;
    }

    private void removeBlockAndLabel(Grave grave, World world) {
        if (world == null) {
            return;
        }
        Block block = world.getBlockAt(grave.x, grave.y, grave.z);
        if (block.getType() == GRAVE_BLOCK) {
            block.setType(Material.AIR);
        }
        Location center = grave.center(world);
        if (grave.label != null) {
            Entity label = Bukkit.getEntity(grave.label);
            if (label != null) {
                label.remove();
            }
        }
        // Also sweep by tag, in case the label's UUID lookup missed it.
        String id = grave.id.toString();
        for (TextDisplay td : world.getNearbyEntitiesByType(TextDisplay.class, center, 2)) {
            if (id.equals(td.getPersistentDataContainer().get(labelKey, PersistentDataType.STRING))) {
                td.remove();
            }
        }
    }

    // --------------------------------------------------------------- recovery

    /**
     * If something outside this plugin (WorldEdit, /fill, ...) removed a grave block, put it
     * back when its chunk loads, so the items stay reachable.
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        World world = chunk.getWorld();
        for (Grave grave : store.all()) {
            if (grave.world.equals(world.getUID()) && (grave.x >> 4) == chunk.getX() && (grave.z >> 4) == chunk.getZ()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!world.isChunkLoaded(chunk.getX(), chunk.getZ())) {
                        return;
                    }
                    Block block = world.getBlockAt(grave.x, grave.y, grave.z);
                    if (block.getType() != GRAVE_BLOCK && isFree(block)) {
                        block.setType(GRAVE_BLOCK);
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------ protection

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> graveAt(block) != null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> graveAt(block) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (graveAt(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> graveAt(block) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> graveAt(block) != null)) {
            event.setCancelled(true);
        }
    }
}

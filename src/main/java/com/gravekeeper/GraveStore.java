package com.gravekeeper;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Holds every gravestone and persists them to graves.yml. Items live here, in
 * the plugin's data, never as dropped item entities, so they cannot despawn.
 *
 * Saves are atomic (temp file + move) and keep a graves.yml.bak of the previous
 * version. Graves that fail to load are kept verbatim and written back, never dropped.
 */
public final class GraveStore {

    private final JavaPlugin plugin;
    private final File file;
    private final File backup;
    private final File temp;
    private final Map<String, Grave> graves = new LinkedHashMap<>();
    /** Raw data of graves we could not parse, preserved so a save never deletes them. */
    private final Map<String, Map<String, Object>> unreadable = new LinkedHashMap<>();

    GraveStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "graves.yml");
        this.backup = new File(plugin.getDataFolder(), "graves.yml.bak");
        this.temp = new File(plugin.getDataFolder(), "graves.yml.tmp");
    }

    Grave at(Block block) {
        return graves.get(Grave.key(block));
    }

    Collection<Grave> all() {
        return graves.values();
    }

    /** Registers and persists a grave. Returns false (and registers nothing) if that failed. */
    boolean add(Grave grave) {
        if (graves.containsKey(grave.key())) {
            return false;
        }
        try {
            encode(grave.items);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not serialize items for a new grave", e);
            return false;
        }
        graves.put(grave.key(), grave);
        if (!save()) {
            graves.remove(grave.key());
            return false;
        }
        return true;
    }

    /** Removes and persists. If the write fails the grave stays registered and false is returned. */
    boolean remove(Grave grave) {
        graves.remove(grave.key());
        if (save()) {
            return true;
        }
        graves.put(grave.key(), grave);
        return false;
    }

    void load() {
        YamlConfiguration yaml = read(file);
        if (yaml == null && file.exists()) {
            File bad = new File(plugin.getDataFolder(), "graves.yml.corrupt-" + System.currentTimeMillis());
            plugin.getLogger().severe("graves.yml is unreadable; moving it to " + bad.getName()
                    + " and trying graves.yml.bak");
            file.renameTo(bad);
            yaml = read(backup);
        }
        if (yaml == null) {
            return;
        }
        ConfigurationSection root = yaml.getConfigurationSection("graves");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            try {
                String encoded = s.getString("items", "");
                List<ItemStack> items = encoded.isEmpty()
                        ? new ArrayList<>()
                        : new ArrayList<>(Arrays.asList(
                                ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(encoded))));
                String label = s.getString("label");
                Grave grave = new Grave(
                        UUID.fromString(id),
                        UUID.fromString(s.getString("owner", "")),
                        s.getString("ownerName", "?"),
                        UUID.fromString(s.getString("world", "")),
                        s.getInt("x"), s.getInt("y"), s.getInt("z"),
                        items,
                        s.getInt("exp"),
                        label == null ? null : UUID.fromString(label));
                graves.put(grave.key(), grave);
            } catch (RuntimeException e) {
                unreadable.put(id, s.getValues(false));
                plugin.getLogger().log(Level.SEVERE,
                        "Could not load grave " + id + "; keeping its data untouched in graves.yml", e);
            }
        }
        plugin.getLogger().info("Loaded " + graves.size() + " gravestone(s).");
    }

    private YamlConfiguration read(File f) {
        if (!f.exists()) {
            return null;
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(f);
            return yaml;
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not read " + f.getName(), e);
            return null;
        }
    }

    /** Writes everything to disk atomically. Returns whether it succeeded. */
    boolean save() {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            for (Grave g : graves.values()) {
                String path = "graves." + g.id;
                yaml.set(path + ".owner", g.owner.toString());
                yaml.set(path + ".ownerName", g.ownerName);
                yaml.set(path + ".world", g.world.toString());
                yaml.set(path + ".x", g.x);
                yaml.set(path + ".y", g.y);
                yaml.set(path + ".z", g.z);
                yaml.set(path + ".exp", g.exp);
                yaml.set(path + ".label", g.label == null ? null : g.label.toString());
                yaml.set(path + ".items", encode(g.items));
            }
            for (Map.Entry<String, Map<String, Object>> e : unreadable.entrySet()) {
                yaml.createSection("graves." + e.getKey(), e.getValue());
            }
            plugin.getDataFolder().mkdirs();
            yaml.save(temp);
            if (file.exists()) {
                Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temp.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save graves.yml - gravestones may be lost on restart!", e);
            return false;
        }
    }

    private static String encode(Collection<ItemStack> items) {
        return items.isEmpty() ? "" : Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items));
    }
}

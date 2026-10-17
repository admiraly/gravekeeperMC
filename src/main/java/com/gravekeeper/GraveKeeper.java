package com.gravekeeper;

import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class GraveKeeper extends JavaPlugin {

    public static final String BYPASS_PERMISSION = "gravekeeper.bypass";

    @Override
    public void onEnable() {
        PluginManager plugins = getServer().getPluginManager();
        if (plugins.getPermission(BYPASS_PERMISSION) == null) {
            plugins.addPermission(new Permission(
                    BYPASS_PERMISSION, "Open and break other players' gravestones", PermissionDefault.OP));
        }

        GraveStore store = new GraveStore(this);
        store.load();
        GraveListener listener = new GraveListener(this, store);
        plugins.registerEvents(listener, this);
        // Chunks loaded before we enabled (spawn area) never fire ChunkLoadEvent for us.
        getServer().getScheduler().runTask(this, listener::restoreAll);
    }

    @Override
    public void onDisable() {
        getServer().getPluginManager().removePermission(BYPASS_PERMISSION);
    }
}

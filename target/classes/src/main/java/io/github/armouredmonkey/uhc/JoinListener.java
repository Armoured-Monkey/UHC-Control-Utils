package io.github.armouredmonkey.uhc;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class JoinListener implements Listener {

    private final JavaPlugin plugin;

    public JoinListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (plugin.getConfig().getBoolean("disableMinimapOnJoin", false)) {
            // Send the disable minimap string on join
            event.getPlayer().sendMessage("§n§o§m§i§n§i§m§a§p");
        }
    }
}

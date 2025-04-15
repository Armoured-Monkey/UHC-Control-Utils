package io.github.armouredmonkey;

import io.github.armouredmonkey.calculators.TeamCalculator;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ContextCalculator;
import net.luckperms.api.context.ContextManager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class LPTeamSync extends JavaPlugin implements CommandExecutor, Listener {

    private ContextManager contextManager;
    private LuckPerms luckPerms;
    private final List<ContextCalculator<Player>> registeredCalculators = new ArrayList<>();

    @Override
    public void onEnable() {
        this.luckPerms = getServer().getServicesManager().load(LuckPerms.class);
        if (luckPerms == null) {
            throw new IllegalStateException("LuckPerms API not loaded.");
        }
        this.contextManager = luckPerms.getContextManager();

        saveDefaultConfig();
        setup();

        // Register listener to catch team changes
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        unregisterAll();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        unregisterAll();
        reloadConfig();
        setup();
        sender.sendMessage(ChatColor.GREEN + "LPTeamSync configuration reloaded.");
        return true;
    }

    private void setup() {
        register("team", null, TeamCalculator::new);
    }

    private void register(String option, String requiredPlugin, Supplier<ContextCalculator<Player>> calculatorSupplier) {
        if (getConfig().getBoolean(option, false)) {
            if (requiredPlugin != null && getServer().getPluginManager().getPlugin(requiredPlugin) == null) {
                getLogger().info(requiredPlugin + " not present. Skipping registration of '" + option + "'...");
            } else {
                getLogger().info("Registering '" + option + "' calculator.");
                ContextCalculator<Player> calculator = calculatorSupplier.get();
                this.contextManager.registerCalculator(calculator);
                this.registeredCalculators.add(calculator);
            }
        }
    }

    private void unregisterAll() {
        this.registeredCalculators.forEach(c -> this.contextManager.unregisterCalculator(c));
        this.registeredCalculators.clear();
    }

    // Called when player joins – already triggers context
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        refreshContext(event.getPlayer());
    }

    // Called when /trigger uhc.team is used
    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().toLowerCase();
        if (message.startsWith("/trigger uhc.team")) {
            Bukkit.getScheduler().runTaskLater(this, () -> refreshContext(event.getPlayer()), 1L);
        }
    }

    private void refreshContext(Player player) {
        if (player != null && player.isOnline()) {
            contextManager.signalContextUpdate(player);
            runDiscordUpdate(player);
        }
    }

    // Forces DiscordSRV to resync the player's Discord role based on their LuckPerms group
    public static void runDiscordUpdate(Player player) {
        Bukkit.getScheduler().runTask(JavaPlugin.getProvidingPlugin(LPTeamSync.class), () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "discordsrv update " + player.getName());
        });
    }
}

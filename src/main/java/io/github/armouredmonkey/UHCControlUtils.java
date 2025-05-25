package io.github.armouredmonkey;

import io.github.armouredmonkey.calculators.TeamCalculator;
import io.github.armouredmonkey.uhc.JoinListener;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.node.NodeEqualityPredicate;
import net.luckperms.api.node.types.InheritanceNode;

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
import java.util.concurrent.CompletableFuture;

public class UHCControlUtils extends JavaPlugin implements CommandExecutor, Listener {

    private ContextManager contextManager;
    private LuckPerms luckPerms;
    private final List<net.luckperms.api.context.ContextCalculator<Player>> registeredCalculators = new ArrayList<>();

    @Override
    public void onEnable() {
        this.luckPerms = getServer().getServicesManager().load(LuckPerms.class);
        if (luckPerms == null) {
            throw new IllegalStateException("LuckPerms API not loaded.");
        }
        this.contextManager = luckPerms.getContextManager();

        saveDefaultConfig();
        setup();

        // Register listener
        getServer().getPluginManager().registerEvents(this, this);

        // Register command handlers
        getCommand("reload").setExecutor(this);
        getCommand("sync").setExecutor(this);
        getCommand("setupuhcgroups").setExecutor(this);

        // Register the JoinListener
        getServer().getPluginManager().registerEvents(new JoinListener(this), this);
    }

    @Override
    public void onDisable() {
        unregisterAll();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("reload")) {
            unregisterAll();
            reloadConfig();
            setup();
            sender.sendMessage(ChatColor.GREEN + "UHC Control Utils configuration reloaded.");
            return true;
        }

        if (cmd.equals("sync")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                refreshContext(player);
            }
            sender.sendMessage(ChatColor.YELLOW + "Contexts refreshed. Running DiscordSRV resync...");

            // Delay to allow context refresh before syncing roles
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "discordsrv resync");
            }, 40L); // 2 seconds delay (20L = 1 second)

            return true;
        }

        if (cmd.equals("setupuhcgroups")) {
            if (!sender.hasPermission("uhccontrol.setupgroups")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to run this command.");
                return true;
            }
            sender.sendMessage(ChatColor.YELLOW + "Starting LuckPerms UHC groups setup...");
            setupUHCGroups(sender);
            return true;
        }

        return false;
    }

    private void setup() {
        register("team", null, TeamCalculator::new);
    }

    private void register(String option, String requiredPlugin, Supplier<net.luckperms.api.context.ContextCalculator<Player>> calculatorSupplier) {
        if (getConfig().getBoolean(option, false)) {
            if (requiredPlugin != null && getServer().getPluginManager().getPlugin(requiredPlugin) == null) {
                getLogger().info(requiredPlugin + " not present. Skipping registration of '" + option + "'...");
            } else {
                getLogger().info("Registering '" + option + "' calculator.");
                net.luckperms.api.context.ContextCalculator<Player> calculator = calculatorSupplier.get();
                this.contextManager.registerCalculator(calculator);
                this.registeredCalculators.add(calculator);
            }
        }
    }

    private void unregisterAll() {
        this.registeredCalculators.forEach(c -> this.contextManager.unregisterCalculator(c));
        this.registeredCalculators.clear();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        refreshContext(event.getPlayer());
    }

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

    public static void runDiscordUpdate(Player player) {
        Bukkit.getScheduler().runTask(JavaPlugin.getProvidingPlugin(UHCControlUtils.class), () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "discordsrv resync " + player.getName());
        });
    }

    private void setupUHCGroups(CommandSender sender) {
        if (luckPerms == null) {
            sender.sendMessage(ChatColor.RED + "LuckPerms API is not loaded!");
            return;
        }

        // Create or confirm team groups
        for (int i = 1; i <= 12; i++) {
            String teamGroupName = "team" + i;
            Group teamGroup = luckPerms.getGroupManager().getGroup(teamGroupName);
            if (teamGroup == null) {
                teamGroup = luckPerms.getGroupManager().createAndLoadGroup(teamGroupName).join();
                sender.sendMessage(ChatColor.GREEN + "Created group " + teamGroupName);
            } else {
                sender.sendMessage(ChatColor.YELLOW + "Group " + teamGroupName + " already exists");
            }
        }

        // Create or confirm uhc group
        Group uhcGroup = luckPerms.getGroupManager().getGroup("uhc");
        if (uhcGroup == null) {
            uhcGroup = luckPerms.getGroupManager().createAndLoadGroup("uhc").join();
            sender.sendMessage(ChatColor.GREEN + "Created group uhc");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Group uhc already exists");
        }

        // Add team groups as parents with context to uhc group
        for (int i = 1; i <= 12; i++) {
            String teamGroupName = "team" + i;
            ImmutableContextSet context = ImmutableContextSet.builder()
                    .add("team", "uhc." + i)
                    .build();

            InheritanceNode node = InheritanceNode.builder(teamGroupName)
                    .withContexts(context)
                    .build();

            NodeMap data = uhcGroup.data();
            if (!data.contains(node, NodeEqualityPredicate.EXACT)) {
                data.add(node);
                sender.sendMessage(ChatColor.GREEN + "Added parent " + teamGroupName + " with context team=uhc." + i + " to uhc");
            } else {
                sender.sendMessage(ChatColor.YELLOW + "uhc group already has parent " + teamGroupName + " with context team=uhc." + i);
            }
        }

        CompletableFuture<Void> uhcSaveFuture = luckPerms.getGroupManager().saveGroup(uhcGroup);

        // Add uhc as parent to default group
        Group defaultGroup = luckPerms.getGroupManager().getGroup("default");
        if (defaultGroup == null) {
            sender.sendMessage(ChatColor.RED + "Default group not found! Cannot add uhc as parent.");
            return;
        }

        InheritanceNode uhcParentNode = InheritanceNode.builder("uhc").build();

        NodeMap defaultData = defaultGroup.data();
        if (!defaultData.contains(uhcParentNode, NodeEqualityPredicate.EXACT)) {
            defaultData.add(uhcParentNode);
            sender.sendMessage(ChatColor.GREEN + "Added uhc as parent to default group");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Default group already has uhc as parent");
        }

        CompletableFuture<Void> defaultSaveFuture = luckPerms.getGroupManager().saveGroup(defaultGroup);

        CompletableFuture.allOf(uhcSaveFuture, defaultSaveFuture).thenRun(() -> {
            sender.sendMessage(ChatColor.GREEN + "UHC groups setup complete!");
        }).exceptionally(ex -> {
            sender.sendMessage(ChatColor.RED + "Error saving groups: " + ex.getMessage());
            ex.printStackTrace();
            return null;
        });
    }
}

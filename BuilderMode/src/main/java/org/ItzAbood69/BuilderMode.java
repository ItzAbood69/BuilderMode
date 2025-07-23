package org.ItzAbood69;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BuilderMode extends JavaPlugin implements Listener {

    private final Map<UUID, PlayerState> playerStates = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack[]> survivalInventories = new ConcurrentHashMap<>();
    private Scoreboard scoreboard;
    private Material lockedHelmet;
    private Material lockedBoots;
    private final List<Material> blockedItems = new ArrayList<>();
    private final List<Material> builderItems = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfiguration();
        getServer().getPluginManager().registerEvents(this, this);
        setupScoreboard();
        getLogger().info(ChatColor.GREEN + "BuilderMode enabled! Created by ItzAbood69");
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isInBuilderMode(player)) {
                disableBuilderMode(player);
            }
        }
        playerStates.clear();
        survivalInventories.clear();
        getLogger().info(ChatColor.RED + "BuilderMode disabled!");
    }

    private void reloadConfiguration() {
        reloadConfig();
        FileConfiguration config = getConfig();

        lockedHelmet = getMaterial(config.getString("locked-armor.helmet", "GOLD_HELMET"));
        lockedBoots = getMaterial(config.getString("locked-armor.boots", "GOLD_BOOTS"));

        blockedItems.clear();
        for (String itemName : config.getStringList("restrictions.blocked-items")) {
            Material mat = getMaterial(itemName);
            if (mat != null && mat.isItem()) {
                blockedItems.add(mat);
            }
        }

        builderItems.clear();
        for (String itemName : config.getStringList("builder-items")) {
            Material mat = Material.getMaterial(itemName.toUpperCase());
            if (mat != null) builderItems.add(mat);
        }
    }

    private Material getMaterial(String name) {
        if (name == null) return null;
        String upperName = name.toUpperCase();

        // Handle legacy material names
        Map<String, String> legacyMap = new HashMap<>();
        legacyMap.put("GOLD_HELMET", "GOLDEN_HELMET");
        legacyMap.put("GOLD_BOOTS", "GOLDEN_BOOTS");

        if (legacyMap.containsKey(upperName)) {
            upperName = legacyMap.get(upperName);
        }

        return Material.getMaterial(upperName);
    }

    private void setupScoreboard() {
        if (Bukkit.getScoreboardManager() != null) {
            scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
            Team team = scoreboard.getTeam("BuilderMode");
            if (team == null) {
                team = scoreboard.registerNewTeam("BuilderMode");
            }
            team.setColor(ChatColor.GOLD);
            team.setSuffix(ChatColor.DARK_BLUE + " [BUILDER]");
        }
    }

    private static class PlayerState {
        long activationTime;
        boolean couldFly;
        boolean wasFlying;
    }

    public boolean isInBuilderMode(Player player) {
        return playerStates.containsKey(player.getUniqueId());
    }

    public void enableBuilderMode(Player player) {
        if (isInBuilderMode(player)) return;

        PlayerState state = new PlayerState();
        state.activationTime = System.currentTimeMillis();

        // Save flight state
        state.couldFly = player.getAllowFlight();
        state.wasFlying = player.isFlying();

        // Save survival inventory
        survivalInventories.put(player.getUniqueId(), player.getInventory().getContents());

        playerStates.put(player.getUniqueId(), state);

        // Setup builder mode
        player.getInventory().clear();
        applyLockedArmor(player);
        loadBuilderInventory(player);

        // Apply flight if enabled
        if (getConfig().getBoolean("allow-flight", true)) {
            player.setAllowFlight(true);
            player.setFlying(true);
        }

        // Apply scoreboard tag
        if (scoreboard != null) {
            Team team = scoreboard.getTeam("BuilderMode");
            if (team != null && !team.hasEntry(player.getName())) {
                team.addEntry(player.getName());
            }
        }

        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("messages.enabled", "&aBuilder Mode activated!")));

        // Schedule auto-deactivation
        int minutes = getConfig().getInt("auto-deactivate-minutes", 30);
        if (minutes > 0) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (isInBuilderMode(player)) {
                    disableBuilderMode(player);
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            getConfig().getString("messages.auto-disabled", "&eBuilder Mode automatically disabled!")));
                }
            }, minutes * 60 * 20L);
        }
    }

    public void disableBuilderMode(Player player) {
        if (!isInBuilderMode(player)) return;

        PlayerState state = playerStates.remove(player.getUniqueId());

        // Restore flight state
        player.setAllowFlight(state.couldFly);
        player.setFlying(state.wasFlying);

        // Restore survival inventory
        if (survivalInventories.containsKey(player.getUniqueId())) {
            player.getInventory().clear();
            player.getInventory().setContents(survivalInventories.remove(player.getUniqueId()));
        }

        // Remove scoreboard tag
        if (scoreboard != null) {
            Team team = scoreboard.getTeam("BuilderMode");
            if (team != null && team.hasEntry(player.getName())) {
                team.removeEntry(player.getName());
            }
        }

        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("messages.disabled", "&cBuilder Mode deactivated!")));
    }

    private void applyLockedArmor(Player player) {
        if (lockedHelmet != null) {
            player.getInventory().setHelmet(createLockedArmor(lockedHelmet));
        }
        if (lockedBoots != null) {
            player.getInventory().setBoots(createLockedArmor(lockedBoots));
        }
    }

    private ItemStack createLockedArmor(Material material) {
        if (material == null) material = Material.LEATHER_HELMET;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            try {
                meta.setUnbreakable(true);
            } catch (NoSuchMethodError ignored) {
                try {
                    meta.getClass().getMethod("spigot").invoke(meta).getClass()
                            .getMethod("setUnbreakable", boolean.class).invoke(meta, true);
                } catch (Exception ignored2) {}
            }

            try {
                meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            } catch (NoClassDefFoundError | NoSuchMethodError ignored) {}

            item.setItemMeta(meta);
        }
        return item;
    }

    private void loadBuilderInventory(Player player) {
        for (Material mat : builderItems) {
            player.getInventory().addItem(new ItemStack(mat));
        }
    }

    // Event Handlers
    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (event.getNewGameMode() == GameMode.CREATIVE &&
                getConfig().getBoolean("auto-activation") &&
                !isInBuilderMode(player)) {
            enableBuilderMode(player);
        } else if (isInBuilderMode(player) &&
                event.getNewGameMode() != GameMode.CREATIVE) {
            disableBuilderMode(player);
        }
    }

    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        if (isInBuilderMode(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onContainerOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player) {
            Player player = (Player) event.getPlayer();
            if (isInBuilderMode(player) &&
                    event.getInventory().getType() != InventoryType.PLAYER &&
                    getConfig().getBoolean("restrictions.block-container-access", true)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "Container access is restricted in Builder Mode!");
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!isInBuilderMode(player)) return;

        // Prevent moving locked armor
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        if (isLockedArmor(current) || isLockedArmor(cursor)) {
            event.setCancelled(true);
        }

        // Prevent moving items to armor slots
        if (event.getSlotType() == InventoryType.SlotType.ARMOR) {
            event.setCancelled(true);
        }
    }

    private boolean isLockedArmor(ItemStack item) {
        if (item == null) return false;
        Material type = item.getType();
        return (lockedHelmet != null && type == lockedHelmet) ||
                (lockedBoots != null && type == lockedBoots);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!isInBuilderMode(player)) return;

        if (blockedItems.contains(event.getBlockPlaced().getType())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "This block is restricted in Builder Mode!");
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isInBuilderMode(player)) return;

        if (event.getItem() != null && blockedItems.contains(event.getItem().getType())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "This item is restricted in Builder Mode!");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (isInBuilderMode(player)) {
            disableBuilderMode(player);
        }
    }

    @EventHandler
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();

        if (isInBuilderMode(player) && blockedItems.contains(event.getItem().getItemStack().getType())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You can't pick up this item in Builder Mode!");
        }
    }

    // Command Handler
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "on":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.RED + "Only players can use this command!");
                    return true;
                }
                Player playerOn = (Player) sender;
                if (!playerOn.hasPermission("buildermode.use")) {
                    playerOn.sendMessage(ChatColor.RED + "You don't have permission for this!");
                    return true;
                }
                enableBuilderMode(playerOn);
                break;

            case "off":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.RED + "Only players can use this command!");
                    return true;
                }
                Player playerOff = (Player) sender;
                if (!playerOff.hasPermission("buildermode.use")) {
                    playerOff.sendMessage(ChatColor.RED + "You don't have permission for this!");
                    return true;
                }
                disableBuilderMode(playerOff);
                break;

            case "check":
                if (!sender.hasPermission("buildermode.check")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission for this!");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /buildermode check <player>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found!");
                    return true;
                }
                sender.sendMessage(ChatColor.YELLOW + target.getName() + " is " +
                        (isInBuilderMode(target) ? "in" : "not in") + " Builder Mode");
                break;

            case "list":
                if (!sender.hasPermission("buildermode.admin")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission for this!");
                    return true;
                }
                StringBuilder builderList = new StringBuilder();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (isInBuilderMode(p)) {
                        builderList.append(p.getName()).append(", ");
                    }
                }
                if (builderList.length() > 0) {
                    builderList.setLength(builderList.length() - 2);
                } else {
                    builderList.append("None");
                }
                sender.sendMessage(ChatColor.GOLD + "Players in Builder Mode: " + ChatColor.WHITE + builderList);
                break;

            case "blocked":
                if (!sender.hasPermission("buildermode.admin")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission for this!");
                    return true;
                }
                StringBuilder blockedList = new StringBuilder();
                for (Material mat : blockedItems) {
                    blockedList.append(mat.name()).append(", ");
                }
                if (blockedList.length() > 0) {
                    blockedList.setLength(blockedList.length() - 2);
                } else {
                    blockedList.append("None");
                }
                sender.sendMessage(ChatColor.GOLD + "Blocked items: " + ChatColor.WHITE + blockedList);
                break;

            case "reload":
                if (!sender.hasPermission("buildermode.admin")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission for this!");
                    return true;
                }
                reloadConfiguration();
                sender.sendMessage(ChatColor.GREEN + "Configuration reloaded!");
                break;

            case "fly":
                handleFlyCommand(sender, args);
                break;

            case "flyall":
                if (!sender.hasPermission("buildermode.flyall")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission for this!");
                    return true;
                }
                toggleAllBuilderFlight(sender);
                break;

            case "help":
            default:
                showHelp(sender);
                break;
        }

        return true;
    }

    private void handleFlyCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("buildermode.fly")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission for this!");
            return;
        }

        Player targetPlayer;
        if (args.length > 1) {
            if (!sender.hasPermission("buildermode.fly.others")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to toggle flight for others!");
                return;
            }
            targetPlayer = Bukkit.getPlayer(args[1]);
            if (targetPlayer == null) {
                sender.sendMessage(ChatColor.RED + "Player not found!");
                return;
            }
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Console must specify a player!");
                return;
            }
            targetPlayer = (Player) sender;
        }

        if (!isInBuilderMode(targetPlayer)) {
            sender.sendMessage(ChatColor.RED + "This player is not in Builder Mode!");
            return;
        }

        boolean newState = !targetPlayer.getAllowFlight();
        targetPlayer.setAllowFlight(newState);
        if (newState) {
            targetPlayer.setFlying(true);
            sender.sendMessage(ChatColor.GREEN + "Enabled flight for " + targetPlayer.getName());
        } else {
            targetPlayer.setFlying(false);
            sender.sendMessage(ChatColor.GREEN + "Disabled flight for " + targetPlayer.getName());
        }
    }

    private void toggleAllBuilderFlight(CommandSender sender) {
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isInBuilderMode(player)) {
                boolean newState = !player.getAllowFlight();
                player.setAllowFlight(newState);
                player.setFlying(newState);
                count++;
            }
        }
        sender.sendMessage(ChatColor.GREEN + "Toggled flight for " + count + " builders");
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== BuilderMode Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/buildermode on" + ChatColor.WHITE + " - Enable Builder Mode");
        sender.sendMessage(ChatColor.YELLOW + "/buildermode off" + ChatColor.WHITE + " - Disable Builder Mode");
        sender.sendMessage(ChatColor.YELLOW + "/buildermode check <player>" + ChatColor.WHITE + " - Check player's mode");
        sender.sendMessage(ChatColor.YELLOW + "/buildermode list" + ChatColor.WHITE + " - List players in Builder Mode");
        sender.sendMessage(ChatColor.YELLOW + "/buildermode blocked" + ChatColor.WHITE + " - Show blocked items");
        sender.sendMessage(ChatColor.YELLOW + "/buildermode reload" + ChatColor.WHITE + " - Reload configuration");
        sender.sendMessage(ChatColor.YELLOW + "/buildermode fly [player]" + ChatColor.WHITE + " - Toggle flight for builder");
        sender.sendMessage(ChatColor.YELLOW + "/buildermode flyall" + ChatColor.WHITE + " - Toggle flight for all builders");
        sender.sendMessage(ChatColor.YELLOW + "/buildermode help" + ChatColor.WHITE + " - Show this help");
    }
}
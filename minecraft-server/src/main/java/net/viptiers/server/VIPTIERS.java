
package net.viptiers.server;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.ChunkGenerator.BiomeGrid;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VIPTIERS extends JavaPlugin
        implements Listener, CommandExecutor {

    private HttpClient http;
    private String backendUrl;
    private String apiSecret;
    private World verificationWorld;

    private static final String MODE_MENU =
            ChatColor.DARK_PURPLE + "VIPTIERS | Gamemodes";

    private static final String REGION_MENU =
            ChatColor.DARK_PURPLE + "VIPTIERS | Region";

    private final Set<UUID> verifiedPlayers = new HashSet<>();

    private final Map<UUID, QueueEntry> queuedPlayers =
            new HashMap<>();

    private final Map<String, List<UUID>> testingQueues =
            new HashMap<>();

    private static final String[] GAMEMODES = {
            "Crystal", "UHC", "Pot", "NethOP", "DiaSMP",
            "Sword", "Axe", "Mace", "Spear Mace", "Cart"
    };

    private static final Material[] GAMEMODE_ITEMS = {
            Material.END_CRYSTAL,
            Material.IRON_SWORD,
            Material.POTION,
            Material.DIAMOND_CHESTPLATE,
            Material.DIAMOND_SWORD,
            Material.IRON_SWORD,
            Material.IRON_AXE,
            Material.MACE,
            Material.TRIDENT,
            Material.MINECART
    };

    private static final int[] GAMEMODE_SLOTS = {
            10, 12, 14, 16, 19, 21, 23, 25, 37, 43
    };

    private static final String[] REGIONS = {
            "North America",
            "Europe",
            "Asia",
            "South America",
            "Oceania"
    };

    private static final int[] REGION_SLOTS = {
            10, 12, 14, 28, 30
    };

    private static class QueueEntry {
        private final String gamemode;
        private final String region;

        private QueueEntry(String gamemode, String region) {
            this.gamemode = gamemode;
            this.region = region;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        backendUrl = getConfig().getString("backend-url", "")
                .replaceAll("/+$", "");

        apiSecret = getConfig().getString("api-secret", "");

        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        if (backendUrl.isBlank() || apiSecret.isBlank()) {
            getLogger().severe("Backend URL or API secret is missing!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ChunkGenerator voidGenerator = new ChunkGenerator() {
            @Override
            public ChunkData generateChunkData(
                    World world,
                    Random random,
                    int x,
                    int z,
                    BiomeGrid biome
            ) {
                return createChunkData(world);
            }
        };

        verificationWorld = new WorldCreator("viptiers_void")
                .environment(World.Environment.NORMAL)
                .generator(voidGenerator)
                .createWorld();

        if (verificationWorld == null) {
            getLogger().severe("Could not create verification void world!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        verificationWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        verificationWorld.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        verificationWorld.setTime(6000);

        getServer().getPluginManager().registerEvents(this, this);

        String[] commands = {
                "verify", "verified", "verifyinfo",
                "unverify", "tiertest"
        };

        for (String name : commands) {
            if (getCommand(name) != null) {
                getCommand(name).setExecutor(this);
            } else {
                getLogger().severe(
                        "Command missing from plugin.yml: " + name
                );
            }
        }

        getLogger().info("VIPTIERS testing and verification enabled!");
    }

    private HttpRequest.Builder authenticatedRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiSecret)
                .header("Content-Type", "application/json");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        verifiedPlayers.remove(player.getUniqueId());

        player.setGameMode(GameMode.ADVENTURE);
        player.setInvulnerable(true);
        player.setCollidable(false);
        player.setWalkSpeed(0);
        player.setAllowFlight(true);
        player.setFlying(true);

        player.teleport(new Location(
                verificationWorld, 0.5, 100, 0.5
        ));

        player.sendMessage("");
        player.sendMessage(ChatColor.LIGHT_PURPLE.toString()
                + ChatColor.BOLD + "VIPTIERS");
        player.sendMessage(ChatColor.YELLOW
                + "Checking your account verification...");
        player.sendMessage("");

        checkPlayerStatus(player.getUniqueId());
    }

    private void checkPlayerStatus(UUID uuid) {
        HttpRequest request = authenticatedRequest(
                backendUrl + "/api/verification/status/" + uuid
        ).GET().build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200
                            && response.body().contains("\"verified\":true")) {

                        verifiedPlayers.add(uuid);

                        runForPlayer(uuid, this::releaseVerifiedPlayer);

                    } else if (response.statusCode() == 404) {
                        sendPlayerMessage(uuid, ChatColor.YELLOW
                                + "Visit the VIPTIERS website to get your code.");
                        sendPlayerMessage(uuid, ChatColor.GRAY
                                + "Then enter: " + ChatColor.WHITE
                                + "/verify <code>");

                    } else {
                        getLogger().warning("Status request failed: "
                                + response.statusCode());

                        sendPlayerMessage(uuid, ChatColor.RED
                                + "Could not check verification. Please try again.");
                    }
                })
                .exceptionally(error -> {
                    getLogger().warning("Backend connection failed: "
                            + error.getMessage());

                    sendPlayerMessage(uuid, ChatColor.RED
                            + "Verification service unavailable.");

                    return null;
                });
    }

    private void releaseVerifiedPlayer(Player player) {
        if (!player.isOnline()) {
            return;
        }

        player.setGameMode(GameMode.SURVIVAL);
        player.setInvulnerable(false);
        player.setCollidable(true);
        player.setWalkSpeed(0.2f);
        player.setAllowFlight(false);
        player.setFlying(false);

        World mainWorld = null;

        for (World world : Bukkit.getWorlds()) {
            if (!world.equals(verificationWorld)) {
                mainWorld = world;
                break;
            }
        }

        if (mainWorld != null) {
            player.teleport(mainWorld.getSpawnLocation());
        }

        player.sendMessage("");
        player.sendMessage(ChatColor.LIGHT_PURPLE.toString()
                + ChatColor.BOLD + "VIPTIERS");
        player.sendMessage(ChatColor.GREEN
                + "Your account is verified!");
        player.sendMessage(ChatColor.GRAY
                + "Use " + ChatColor.WHITE + "/tiertest"
                + ChatColor.GRAY + " to join the testing queue.");
        player.sendMessage("");
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        String name = command.getName().toLowerCase();

        if (name.equals("verify")) {
            return handleVerify(sender, args);
        }

        if (name.equals("tiertest")) {
            return handleTierTest(sender, args);
        }

        if (!sender.hasPermission("viptiers.admin")) {
            sender.sendMessage(ChatColor.RED
                    + "You don't have permission to use this command.");
            return true;
        }

        switch (name) {
            case "verified" -> listVerified(sender);
            case "verifyinfo" -> playerInfo(sender, args);
            case "unverify" -> unverifyPlayer(sender, args);
            default -> {
                return false;
            }
        }

        return true;
    }

    private boolean handleTierTest(
            CommandSender sender,
            String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (!player.hasPermission("viptiers.test")) {
            player.sendMessage(ChatColor.RED
                    + "You don't have permission to use the testing queue.");
            return true;
        }

        if (!verifiedPlayers.contains(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED
                    + "You must verify your account before joining the queue.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("leave")) {
            leaveQueue(player);
            return true;
        }

        if (args.length > 0) {
            player.sendMessage(ChatColor.RED
                    + "Usage: /tiertest or /tiertest leave");
            return true;
        }

        if (queuedPlayers.containsKey(player.getUniqueId())) {
            QueueEntry entry = queuedPlayers.get(player.getUniqueId());

            player.sendMessage(ChatColor.YELLOW
                    + "You are already in the testing queue.");
            player.sendMessage(ChatColor.GRAY
                    + "Gamemode: " + ChatColor.WHITE + entry.gamemode);
            player.sendMessage(ChatColor.GRAY
                    + "Region: " + ChatColor.WHITE + entry.region);
            player.sendMessage(ChatColor.GRAY
                    + "Use " + ChatColor.WHITE + "/tiertest leave"
                    + ChatColor.GRAY + " to leave.");
            return true;
        }

        openGamemodeMenu(player);
        return true;
    }

    private void openGamemodeMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(
                null, 54, MODE_MENU
        );

        for (int i = 0; i < GAMEMODES.length; i++) {
            ItemStack item = new ItemStack(GAMEMODE_ITEMS[i]);
            ItemMeta meta = item.getItemMeta();

            meta.setDisplayName(ChatColor.LIGHT_PURPLE
                    + ChatColor.BOLD + GAMEMODES[i]);

            meta.setLore(List.of(
                    ChatColor.GRAY + "Click to select this gamemode."
            ));

            item.setItemMeta(meta);
            inventory.setItem(GAMEMODE_SLOTS[i], item);
        }

        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = info.getItemMeta();

        infoMeta.setDisplayName(ChatColor.YELLOW + "Testing Information");
        infoMeta.setLore(List.of(
                ChatColor.GRAY + "Choose a gamemode to continue.",
                ChatColor.GRAY + "You will select your region next."
        ));

        info.setItemMeta(infoMeta);
        inventory.setItem(49, info);

        player.openInventory(inventory);
    }

    private void openRegionMenu(Player player, String gamemode) {
        Inventory inventory = Bukkit.createInventory(
                null, 54, REGION_MENU
        );

        Material[] regionItems = {
                Material.BLUE_WOOL,
                Material.LIME_WOOL,
                Material.YELLOW_WOOL,
                Material.RED_WOOL,
                Material.ORANGE_WOOL
        };

        for (int i = 0; i < REGIONS.length; i++) {
            ItemStack item = new ItemStack(regionItems[i]);
            ItemMeta meta = item.getItemMeta();

            meta.setDisplayName(ChatColor.AQUA
                    + ChatColor.BOLD + REGIONS[i]);

            meta.setLore(List.of(
                    ChatColor.GRAY + "Gamemode: "
                            + ChatColor.WHITE + gamemode,
                    ChatColor.GRAY + "Click to join this queue."
            ));

            item.setItemMeta(meta);
            inventory.setItem(REGION_SLOTS[i], item);
        }

        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = info.getItemMeta();

        infoMeta.setDisplayName(ChatColor.YELLOW + "Selected Gamemode");
        infoMeta.setLore(List.of(
                ChatColor.WHITE + gamemode
        ));

        info.setItemMeta(infoMeta);
        inventory.setItem(49, info);

        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();

        if (!title.equals(MODE_MENU)
                && !title.equals(REGION_MENU)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.getClickedInventory() == null
                || !event.getClickedInventory()
                .equals(event.getView().getTopInventory())) {
            return;
        }

        int slot = event.getSlot();

        if (title.equals(MODE_MENU)) {
            for (int i = 0; i < GAMEMODE_SLOTS.length; i++) {
                if (slot == GAMEMODE_SLOTS[i]) {
                    openRegionMenu(player, GAMEMODES[i]);
                    return;
                }
            }
        }

        if (title.equals(REGION_MENU)) {
            for (int i = 0; i < REGION_SLOTS.length; i++) {
                if (slot == REGION_SLOTS[i]) {
                    ItemStack info = event.getView()
                            .getTopInventory().getItem(49);

                    if (info == null || !info.hasItemMeta()) {
                        return;
                    }

                    String selectedMode = ChatColor.stripColor(
                            info.getItemMeta().getLore().get(0)
                    );

                    joinQueue(player, selectedMode, REGIONS[i]);
                    return;
                }
            }
        }
    }

    private void joinQueue(
            Player player,
            String gamemode,
            String region
    ) {
        UUID uuid = player.getUniqueId();

        if (!verifiedPlayers.contains(uuid)) {
            player.closeInventory();
            player.sendMessage(ChatColor.RED
                    + "You must be verified to join the queue.");
            return;
        }

        if (queuedPlayers.containsKey(uuid)) {
            player.closeInventory();
            player.sendMessage(ChatColor.YELLOW
                    + "You are already in a testing queue.");
            return;
        }

        QueueEntry entry = new QueueEntry(gamemode, region);
        queuedPlayers.put(uuid, entry);

        String queueKey = gamemode + "|" + region;

        testingQueues.computeIfAbsent(
                queueKey, key -> new ArrayList<>()
        ).add(uuid);

        int position = testingQueues.get(queueKey).size();

        player.closeInventory();

        player.sendMessage("");
        player.sendMessage(ChatColor.LIGHT_PURPLE.toString()
                + ChatColor.BOLD + "VIPTIERS QUEUE");
        player.sendMessage(ChatColor.GRAY
                + "Gamemode: " + ChatColor.WHITE + gamemode);
        player.sendMessage(ChatColor.GRAY
                + "Region: " + ChatColor.WHITE + region);
        player.sendMessage(ChatColor.GRAY
                + "Queue position: " + ChatColor.WHITE + position);
        player.sendMessage(ChatColor.GREEN
                + "You have joined the testing queue!");
        player.sendMessage(ChatColor.GRAY
                + "Use /tiertest leave to leave the queue.");
        player.sendMessage("");
    }

    private void leaveQueue(Player player) {
        UUID uuid = player.getUniqueId();
        QueueEntry entry = queuedPlayers.remove(uuid);

        if (entry == null) {
            player.sendMessage(ChatColor.RED
                    + "You are not in a testing queue.");
            return;
        }

        String queueKey = entry.gamemode + "|" + entry.region;

        List<UUID> queue = testingQueues.get(queueKey);

        if (queue != null) {
            queue.remove(uuid);

            if (queue.isEmpty()) {
                testingQueues.remove(queueKey);
            }
        }

        player.sendMessage(ChatColor.YELLOW
                + "You have left the testing queue.");
    }

    private boolean handleVerify(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length != 1 || !args[0].matches("\\d{6}")) {
            player.sendMessage(ChatColor.RED
                    + "Usage: /verify <6-digit-code>");
            return true;
        }

        UUID uuid = player.getUniqueId();
        String username = player.getName();
        String code = args[0];

        player.sendMessage(ChatColor.YELLOW
                + "Checking your verification code...");

        String body = "{\"uuid\":\"" + uuid
                + "\",\"username\":\"" + username
                + "\",\"code\":\"" + code + "\"}";

        HttpRequest request = authenticatedRequest(
                backendUrl + "/api/verification/complete"
        )
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200
                            && response.body().contains("\"verified\":true")) {

                        sendPlayerMessage(uuid, ChatColor.GREEN
                                + "Your account has been verified! "
                                + "Reconnect to enter VIPTIERS.");

                        runForPlayer(uuid, Player::kickPlayer);
                    } else {
                        sendPlayerMessage(uuid, ChatColor.RED
                                + "Incorrect or expired verification code.");
                    }
                })
                .exceptionally(error -> {
                    getLogger().warning("Verification request failed: "
                            + error.getMessage());

                    sendPlayerMessage(uuid, ChatColor.RED
                            + "Could not contact verification service.");

                    return null;
                });

        return true;
    }

    private void listVerified(CommandSender sender) {
        sender.sendMessage(ChatColor.LIGHT_PURPLE.toString()
                + ChatColor.BOLD + "VIPTIERS VERIFIED PLAYERS");

        HttpRequest request = authenticatedRequest(
                backendUrl + "/api/verification/players"
        ).GET().build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        sendSenderMessage(sender, ChatColor.RED
                                + "Could not load verified players.");
                        return;
                    }

                    Matcher countMatcher = Pattern.compile(
                            "\"count\"\\s*:\\s*(\\d+)"
                    ).matcher(response.body());

                    String count = countMatcher.find()
                            ? countMatcher.group(1) : "?";

                    Matcher objects = Pattern.compile(
                            "\\{[^{}]*\\}"
                    ).matcher(response.body());

                    StringBuilder result = new StringBuilder();
                    int number = 0;

                    while (objects.find()) {
                        String object = objects.group();

                        Matcher uuidMatcher = Pattern.compile(
                                "\"uuid\"\\s*:\\s*\"([^\"]+)\""
                        ).matcher(object);

                        Matcher nameMatcher = Pattern.compile(
                                "\"username\"\\s*:\\s*\"([^\"]+)\""
                        ).matcher(object);

                        if (uuidMatcher.find() && nameMatcher.find()) {
                            number++;
                            result.append(ChatColor.GRAY)
                                    .append(number)
                                    .append(". ")
                                    .append(ChatColor.WHITE)
                                    .append(nameMatcher.group(1))
                                    .append(ChatColor.DARK_GRAY)
                                    .append(" (")
                                    .append(uuidMatcher.group(1))
                                    .append(")\n");
                        }
                    }

                    String output = result.toString();

                    Bukkit.getScheduler().runTask(this, () -> {
                        sender.sendMessage(ChatColor.GRAY
                                + "Total verified: "
                                + ChatColor.GREEN + count);

                        if (output.isEmpty()) {
                            sender.sendMessage(ChatColor.GRAY
                                    + "No verified players found.");
                        } else {
                            for (String line : output.split("\n")) {
                                sender.sendMessage(line);
                            }
                        }
                    });
                })
                .exceptionally(error -> {
                    getLogger().warning("Could not load players: "
                            + error.getMessage());

                    sendSenderMessage(sender, ChatColor.RED
                            + "Backend connection failed.");

                    return null;
                });
    }

    private void playerInfo(CommandSender sender, String[] args) {
        if (args.length != 1
                || !args[0].matches("[a-zA-Z0-9_]{3,16}")) {
            sender.sendMessage(ChatColor.RED
                    + "Usage: /verifyinfo <player>");
            return;
        }

        String username = args[0];

        HttpRequest request = authenticatedRequest(
                backendUrl + "/api/verification/player/" + username
        ).GET().build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        String body = response.body();

                        Matcher uuidMatcher = Pattern.compile(
                                "\"uuid\"\\s*:\\s*\"([^\"]+)\""
                        ).matcher(body);

                        Matcher nameMatcher = Pattern.compile(
                                "\"username\"\\s*:\\s*\"([^\"]+)\""
                        ).matcher(body);

                        if (uuidMatcher.find() && nameMatcher.find()) {
                            sendSenderMessage(sender,
                                    ChatColor.LIGHT_PURPLE
                                            + "VIPTIERS PLAYER INFO");

                            sendSenderMessage(sender,
                                    ChatColor.GRAY + "Username: "
                                            + ChatColor.WHITE
                                            + nameMatcher.group(1));

                            sendSenderMessage(sender,
                                    ChatColor.GRAY + "UUID: "
                                            + ChatColor.WHITE
                                            + uuidMatcher.group(1));

                            sendSenderMessage(sender,
                                    ChatColor.GRAY + "Status: "
                                            + ChatColor.GREEN + "VERIFIED");
                        }
                    } else if (response.statusCode() == 404) {
                        sendSenderMessage(sender, ChatColor.RED
                                + "That player is not verified.");
                    } else {
                        sendSenderMessage(sender, ChatColor.RED
                                + "Could not check player information.");
                    }
                })
                .exceptionally(error -> {
                    sendSenderMessage(sender, ChatColor.RED
                            + "Backend connection failed.");
                    return null;
                });
    }

    private void unverifyPlayer(CommandSender sender, String[] args) {
        if (args.length != 1
                || !args[0].matches("[a-zA-Z0-9_]{3,16}")) {
            sender.sendMessage(ChatColor.RED
                    + "Usage: /unverify <player>");
            return;
        }

        String username = args[0];

        HttpRequest request = authenticatedRequest(
                backendUrl + "/api/verification/player/" + username
        ).DELETE().build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        sendSenderMessage(sender, ChatColor.GREEN
                                + username + " has been unverified.");

                        Player online = Bukkit.getPlayerExact(username);

                        if (online != null) {
                            verifiedPlayers.remove(online.getUniqueId());
                            leaveQueueIfQueued(online.getUniqueId());
                            online.kickPlayer(ChatColor.RED
                                    + "Your VIPTIERS verification was revoked.");
                        }
                    } else if (response.statusCode() == 404) {
                        sendSenderMessage(sender, ChatColor.RED
                                + "That player is not verified.");
                    } else {
                        sendSenderMessage(sender, ChatColor.RED
                                + "Could not revoke verification.");
                    }
                })
                .exceptionally(error -> {
                    sendSenderMessage(sender, ChatColor.RED
                            + "Backend connection failed.");
                    return null;
                });
    }

    private void leaveQueueIfQueued(UUID uuid) {
        QueueEntry entry = queuedPlayers.remove(uuid);

        if (entry == null) {
            return;
        }

        String key = entry.gamemode + "|" + entry.region;
        List<UUID> queue = testingQueues.get(key);

        if (queue != null) {
            queue.remove(uuid);

            if (queue.isEmpty()) {
                testingQueues.remove(key);
            }
        }
    }

    private void sendPlayerMessage(UUID uuid, String message) {
        runForPlayer(uuid, player -> player.sendMessage(message));
    }

    private void sendSenderMessage(
            CommandSender sender,
            String message
    ) {
        Bukkit.getScheduler().runTask(this, () ->
                sender.sendMessage(message)
        );
    }

    private void runForPlayer(UUID uuid, Consumer<Player> action) {
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(uuid);

            if (player != null && player.isOnline()) {
                action.accept(player);
            }
        });
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (!player.getWorld().equals(verificationWorld)
                || verifiedPlayers.contains(player.getUniqueId())) {
            return;
        }

        if (event instanceof PlayerTeleportEvent) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null) {
            return;
        }

        if (from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.getPlayer().getWorld().equals(verificationWorld)
                && !verifiedPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.getPlayer().getWorld().equals(verificationWorld)
                && !verifiedPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getPlayer().getWorld().equals(verificationWorld)
                && !verifiedPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (event.getPlayer().getWorld().equals(verificationWorld)
                && !verifiedPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && player.getWorld().equals(verificationWorld)
                && !verifiedPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}

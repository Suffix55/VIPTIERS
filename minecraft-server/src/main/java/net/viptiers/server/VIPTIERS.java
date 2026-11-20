
package net.viptiers.server;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.ChunkGenerator.BiomeGrid;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.function.Consumer;

public final class VIPTIERS extends JavaPlugin
        implements Listener, CommandExecutor {

    private HttpClient http;
    private String backendUrl;
    private String apiSecret;
    private World verificationWorld;

    private final Set<UUID> verifiedPlayers =
            ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        backendUrl = getConfig()
                .getString("backend-url", "")
                .replaceAll("/+$", "");

        apiSecret = getConfig()
                .getString("api-secret", "");

        if (backendUrl.isBlank() || apiSecret.isBlank()) {
            getLogger().severe("Backend URL or API secret is missing!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        // Completely empty void generator.
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

        // Separate empty verification world.
        verificationWorld = new WorldCreator("viptiers_void_clean")
                .environment(World.Environment.NORMAL)
                .generator(voidGenerator)
                .createWorld();

        if (verificationWorld == null) {
            getLogger().severe("Could not create verification world!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        verificationWorld.setGameRule(
                GameRule.DO_MOB_SPAWNING, false
        );

        verificationWorld.setGameRule(
                GameRule.DO_DAYLIGHT_CYCLE, false
        );

        verificationWorld.setGameRule(
                GameRule.DO_WEATHER_CYCLE, false
        );

        verificationWorld.setTime(6000);

        // Load the spawn chunk. No blocks are placed.
        verificationWorld.getChunkAt(0, 0).load();
        verificationWorld.setSpawnLocation(0, 150, 0);

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("verify") != null) {
            getCommand("verify").setExecutor(this);
        } else {
            getLogger().severe("Missing verify command in plugin.yml!");
        }

        if (getCommand("unverify") != null) {
            getCommand("unverify").setExecutor(this);
        } else {
            getLogger().severe("Missing unverify command in plugin.yml!");
        }

        getLogger().info("VIPTIERS enabled!");
        getLogger().info("Empty verification sky created at Y=150!");
    }

    @Override
    public void onDisable() {
        getLogger().info("VIPTIERS disabled!");
    }

    // VERIFICATION SPAWN

    private Location getVerificationSpawn() {
        return new Location(
                verificationWorld,
                0.5,
                150,
                0.5,
                0,
                0
        );
    }

    // HTTP REQUESTS

    private HttpRequest.Builder authenticatedRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiSecret)
                .header("Content-Type", "application/json");
    }

    // PLAYER JOIN

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        verifiedPlayers.remove(uuid);

        player.setGameMode(GameMode.ADVENTURE);
        player.setInvulnerable(true);
        player.setCollidable(false);
        player.setWalkSpeed(0);
        player.setAllowFlight(true);
        player.setFlying(true);

        player.teleport(getVerificationSpawn());

        player.sendMessage("");
        player.sendMessage(ChatColor.LIGHT_PURPLE.toString()
                + ChatColor.BOLD + "VIPTIERS");
        player.sendMessage(ChatColor.YELLOW
                + "Checking your account verification...");
        player.sendMessage("");

        checkPlayerStatus(uuid);
    }

    // CHECK VERIFICATION STATUS

    private void checkPlayerStatus(UUID uuid) {
        HttpRequest request = authenticatedRequest(
                backendUrl + "/api/verification/status/" + uuid
        ).GET().build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {

                    if (response.statusCode() == 200
                            && response.body().contains("\"verified\":true")) {

                        runForPlayer(uuid, player -> {
                            verifiedPlayers.add(uuid);
                            releaseVerifiedPlayer(player);
                        });

                    } else if (response.statusCode() == 404) {

                        sendPlayerMessage(uuid, ChatColor.YELLOW
                                + "Visit the VIPTIERS website to get your code.");

                        sendPlayerMessage(uuid, ChatColor.GRAY
                                + "Then enter: " + ChatColor.WHITE
                                + "/verify <code>");

                    } else {

                        getLogger().warning(
                                "Verification status request failed: "
                                        + response.statusCode()
                        );

                        sendPlayerMessage(uuid, ChatColor.RED
                                + "Could not check verification. Please try again.");
                    }
                })
                .exceptionally(error -> {
                    getLogger().warning(
                            "Backend connection failed: "
                                    + error.getMessage()
                    );

                    sendPlayerMessage(uuid, ChatColor.RED
                            + "Verification service unavailable.");

                    return null;
                });
    }

    // RELEASE VERIFIED PLAYER

    private void releaseVerifiedPlayer(Player player) {
        if (!player.isOnline()) {
            return;
        }

        player.setFlying(false);
        player.setAllowFlight(false);
        player.setWalkSpeed(0.2f);
        player.setCollidable(true);
        player.setInvulnerable(false);
        player.setGameMode(GameMode.SURVIVAL);

        World mainWorld = Bukkit.getWorld("world");

        if (mainWorld == null) {
            for (World world : Bukkit.getWorlds()) {
                if (!world.equals(verificationWorld)) {
                    mainWorld = world;
                    break;
                }
            }
        }

        if (mainWorld != null) {
            player.teleport(mainWorld.getSpawnLocation());
        } else {
            getLogger().warning("Could not find the main world!");
        }

        player.sendMessage("");
        player.sendMessage(ChatColor.LIGHT_PURPLE.toString()
                + ChatColor.BOLD + "VIPTIERS");
        player.sendMessage(ChatColor.GREEN
                + "Your account is verified!");
        player.sendMessage("");
    }

    // COMMAND HANDLER

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

        if (name.equals("unverify")) {
            return handleUnverify(sender, args);
        }

        return false;
    }

    // /VERIFY <CODE>

    private boolean handleVerify(
            CommandSender sender,
            String[] args
    ) {
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
                                + "Reconnect to enter the server.");

                        runForPlayer(uuid, onlinePlayer ->
                                onlinePlayer.kickPlayer(
                                        ChatColor.GREEN
                                                + "Your account has been verified! "
                                                + "Reconnect to enter the server."
                                )
                        );

                    } else {
                        sendPlayerMessage(uuid, ChatColor.RED
                                + "Incorrect or expired verification code.");
                    }
                })
                .exceptionally(error -> {
                    getLogger().warning(
                            "Verification request failed: "
                                    + error.getMessage()
                    );

                    sendPlayerMessage(uuid, ChatColor.RED
                            + "Could not contact verification service.");

                    return null;
                });

        return true;
    }

    // /UNVERIFY <PLAYER>

    private boolean handleUnverify(
            CommandSender sender,
            String[] args
    ) {
        if (!sender.hasPermission("viptiers.admin")) {
            sender.sendMessage(ChatColor.RED
                    + "You don't have permission to use this command.");
            return true;
        }

        if (args.length != 1
                || !args[0].matches("[a-zA-Z0-9_]{3,16}")) {
            sender.sendMessage(ChatColor.RED
                    + "Usage: /unverify <player>");
            return true;
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

                        Bukkit.getScheduler().runTask(this, () -> {
                            Player online = Bukkit.getPlayerExact(username);

                            if (online != null) {
                                verifiedPlayers.remove(
                                        online.getUniqueId()
                                );

                                online.kickPlayer(ChatColor.RED
                                        + "Your VIPTIERS verification was revoked.");
                            }
                        });

                    } else if (response.statusCode() == 404) {

                        sendSenderMessage(sender, ChatColor.RED
                                + "That player is not verified.");

                    } else {

                        sendSenderMessage(sender, ChatColor.RED
                                + "Could not revoke verification.");
                    }
                })
                .exceptionally(error -> {
                    getLogger().warning(
                            "Unverify request failed: "
                                    + error.getMessage()
                    );

                    sendSenderMessage(sender, ChatColor.RED
                            + "Backend connection failed.");

                    return null;
                });

        return true;
    }

    // PLAYER MOVEMENT LOCK

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (!player.getWorld().equals(verificationWorld)
                || verifiedPlayers.contains(player.getUniqueId())) {
            return;
        }

        // Allow plugin teleports.
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

    // MAIN THREAD HELPERS

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

    private void runForPlayer(
            UUID uuid,
            Consumer<Player> action
    ) {
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(uuid);

            if (player != null && player.isOnline()) {
                action.accept(player);
            }
        });
    }
}


package net.viptiers.server;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VIPTIERS extends JavaPlugin implements Listener, CommandExecutor {

    private HttpClient http;
    private String backendUrl;
    private String apiSecret;

    private World verificationWorld;

    private final Set<UUID> verifiedPlayers =
            ConcurrentHashMap.newKeySet();

    private static final int SPAWN_Y = 150;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        backendUrl = getConfig().getString("backend-url", "");
        apiSecret = getConfig().getString("api-secret", "");

        while (backendUrl.endsWith("/")) {
            backendUrl = backendUrl.substring(0, backendUrl.length() - 1);
        }

        if (backendUrl.isBlank() || apiSecret.isBlank()) {
            getLogger().severe("Backend URL or API secret is missing!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        createVerificationWorld();

        getServer().getPluginManager().registerEvents(this, this);

        String[] commands = {"verify", "verified", "unverify"};

        for (String command : commands) {
            if (getCommand(command) != null) {
                getCommand(command).setExecutor(this);
            } else {
                getLogger().severe("Missing command in plugin.yml: " + command);
            }
        }

        getLogger().info("VIPTIERS enabled!");
    }

    private void createVerificationWorld() {
        ChunkGenerator voidGenerator = new ChunkGenerator() {
            @Override
            public ChunkData generateChunkData(
                    World world,
                    java.util.Random random,
                    int x,
                    int z,
                    BiomeGrid biome
            ) {
                return createChunkData(world);
            }
        };

        // Use a new world name so old terrain is not reused.
        verificationWorld = new WorldCreator("viptiers_void_final")
                .environment(World.Environment.NORMAL)
                .type(WorldType.NORMAL)
                .generator(voidGenerator)
                .createWorld();

        if (verificationWorld == null) {
            getLogger().severe("Could not create verification world!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        verificationWorld.setGameRule(
                org.bukkit.GameRule.DO_MOB_SPAWNING, false);

        verificationWorld.setGameRule(
                org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);

        verificationWorld.setGameRule(
                org.bukkit.GameRule.DO_WEATHER_CYCLE, false);

        verificationWorld.setTime(6000);

        verificationWorld.getChunkAt(0, 0).load();

        verificationWorld.setSpawnLocation(0, SPAWN_Y, 0);

        getLogger().info("Empty sky verification world created!");
    }

    private Location getVerificationSpawn() {
        return new Location(
                verificationWorld,
                0.5,
                SPAWN_Y,
                0.5
        );
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

        player.teleport(getVerificationSpawn());

        player.sendMessage(ChatColor.DARK_PURPLE + "VIPTIERS");
        player.sendMessage(ChatColor.GRAY + "Checking your verification...");

        checkPlayerStatus(player);
    }

    private void checkPlayerStatus(Player player) {
        UUID uuid = player.getUniqueId();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                        backendUrl + "/api/verification/status/" + uuid))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiSecret)
                .GET()
                .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {

                    if (error != null) {
                        getLogger().warning(
                                "Verification check failed: " + error.getMessage());

                        runForPlayer(uuid, online ->
                                online.sendMessage(
                                        ChatColor.RED + "Could not check verification. Try again later."));
                        return;
                    }

                    if (response.statusCode() == 200
                            && response.body().contains("\"verified\":true")) {

                        runForPlayer(uuid, online -> {
                            verifiedPlayers.add(uuid);
                            releaseVerifiedPlayer(online);
                        });

                    } else if (response.statusCode() == 404) {

                        runForPlayer(uuid, online -> {
                            online.sendMessage(ChatColor.YELLOW
                                    + "You are not verified yet.");
                            online.sendMessage(ChatColor.GRAY
                                    + "Visit the VIPTIERS website and enter /verify <code>.");
                        });

                    } else {
                        runForPlayer(uuid, online ->
                                online.sendMessage(
                                        ChatColor.RED + "Verification service unavailable."));
                    }
                });
    }

    private void releaseVerifiedPlayer(Player player) {
        player.setInvulnerable(false);
        player.setCollidable(true);
        player.setWalkSpeed(0.2f);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setGameMode(GameMode.SURVIVAL);

        World destination = Bukkit.getWorld("world");

        if (destination == null) {
            for (World world : Bukkit.getWorlds()) {
                if (!world.equals(verificationWorld)) {
                    destination = world;
                    break;
                }
            }
        }

        if (destination != null) {
            player.teleport(destination.getSpawnLocation());
        }

        player.sendMessage(ChatColor.GREEN
                + "Your VIPTIERS verification is complete!");
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        String name = command.getName().toLowerCase();

        switch (name) {
            case "verify":
                return handleVerify(sender, args);

            case "verified":
                return handleVerified(sender);

            case "unverify":
                return handleUnverify(sender, args);

            default:
                return false;
        }
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

        String code = args[0];

        String body = "{\"uuid\":\"" + player.getUniqueId()
                + "\",\"username\":\"" + player.getName()
                + "\",\"code\":\"" + code + "\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                        backendUrl + "/api/verification/complete"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiSecret)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        player.sendMessage(ChatColor.YELLOW
                + "Checking your verification code...");

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {

                    if (error != null) {
                        runForPlayer(player.getUniqueId(), online ->
                                online.sendMessage(ChatColor.RED
                                        + "Verification service unavailable."));
                        return;
                    }

                    if (response.statusCode() == 200
                            && response.body().contains("\"verified\":true")) {

                        runForPlayer(player.getUniqueId(), online -> {
                            online.sendMessage(ChatColor.GREEN
                                    + "Verification successful! Rejoin the server.");
                            online.kickPlayer(
                                    ChatColor.GREEN + "Verified! Please reconnect.");
                        });

                    } else {
                        runForPlayer(player.getUniqueId(), online ->
                                online.sendMessage(ChatColor.RED
                                        + "Invalid or expired verification code."));
                    }
                });

        return true;
    }

    private boolean handleVerified(CommandSender sender) {
        if (!sender.hasPermission("viptiers.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                        backendUrl + "/api/verification/players"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiSecret)
                .GET()
                .build();

        sender.sendMessage(ChatColor.YELLOW
                + "Loading verified players...");

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {

                    if (error != null || response.statusCode() != 200) {
                        sendSenderMessage(sender, ChatColor.RED
                                + "Could not load verified players.");
                        return;
                    }

                    String json = response.body();

                    Matcher matcher = Pattern.compile(
                            "\"username\"\\s*:\\s*\"([^\"]+)\"")
                            .matcher(json);

                    sendSenderMessage(sender, ChatColor.DARK_PURPLE
                            + "----- Verified Players -----");

                    int count = 0;

                    while (matcher.find()) {
                        count++;
                        sendSenderMessage(sender, ChatColor.GREEN
                                + "- " + matcher.group(1));
                    }

                    sendSenderMessage(sender, ChatColor.GRAY
                            + "Total: " + count);
                });

        return true;
    }

    private boolean handleUnverify(CommandSender sender, String[] args) {
        if (!sender.hasPermission("viptiers.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED
                    + "Usage: /unverify <player>");
            return true;
        }

        String username = args[0];

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                        backendUrl + "/api/verification/player/" + username))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiSecret)
                .DELETE()
                .build();

        sender.sendMessage(ChatColor.YELLOW
                + "Removing verification for " + username + "...");

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {

                    if (error != null || response.statusCode() < 200
                            || response.statusCode() >= 300) {
                        sendSenderMessage(sender, ChatColor.RED
                                + "Could not unverify " + username + ".");
                        return;
                    }

                    Player target = Bukkit.getPlayerExact(username);

                    if (target != null) {
                        runForPlayer(target.getUniqueId(), online -> {
                            verifiedPlayers.remove(online.getUniqueId());
                            online.kickPlayer(
                                    ChatColor.RED + "Your verification was removed.");
                        });
                    }

                    sendSenderMessage(sender, ChatColor.GREEN
                            + username + " has been unverified.");
                });

        return true;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) {
            return;
        }

        Player player = event.getPlayer();

        if (player.getWorld().equals(verificationWorld)
                && !verifiedPlayers.contains(player.getUniqueId())
                && event.getTo() != null
                && (event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ())) {

            event.setTo(event.getFrom());
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

    private void runForPlayer(UUID uuid, java.util.function.Consumer<Player> action) {
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                action.accept(player);
            }
        });
    }

    private void sendSenderMessage(
            CommandSender sender,
            String message
    ) {
        Bukkit.getScheduler().runTask(this,
                () -> sender.sendMessage(message));
    }
}


package net.viptiers.server;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.generator.ChunkGenerator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VIPTIERS extends JavaPlugin
        implements Listener, CommandExecutor {

    private HttpClient http;
    private String backendUrl;
    private String apiSecret;
    private World verificationWorld;

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
        }

        // Create an empty verification world.
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

        if (getCommand("verify") != null) {
            getCommand("verify").setExecutor(this);
        }

        getLogger().info("VIPTIERS verification plugin enabled!");
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

        player.setGameMode(GameMode.ADVENTURE);
        player.setInvulnerable(true);
        player.setCollidable(false);
        player.setWalkSpeed(0);
        player.setAllowFlight(true);
        player.setFlying(true);

        player.teleport(new Location(verificationWorld, 0.5, 100, 0.5));

        player.sendMessage("");
        player.sendMessage(ChatColor.LIGHT_PURPLE.toString()
                + ChatColor.BOLD + "VIPTIERS");
        player.sendMessage(ChatColor.YELLOW
                + "Account verification required.");
        player.sendMessage(ChatColor.GRAY
                + "Get your verification code from the website.");
        player.sendMessage(ChatColor.GRAY
                + "Then enter: " + ChatColor.WHITE + "/verify <code>");
        player.sendMessage("");

        checkPlayerStatus(player.getUniqueId(), player.getName());
    }

    private void checkPlayerStatus(UUID uuid, String username) {
        if (backendUrl.isBlank() || apiSecret.isBlank()) {
            sendPlayerMessage(uuid,
                    ChatColor.RED + "Verification service is not configured.");
            return;
        }

        HttpRequest request = authenticatedRequest(
                backendUrl + "/api/verification/status/" + uuid
        ).GET().build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200
                            && response.body().contains("\"verified\":true")) {
                        runForPlayer(uuid, player ->
                                player.kickPlayer(ChatColor.GREEN
                                        + "You are already verified!")
                        );
                    } else if (response.statusCode() == 404
                            || (response.statusCode() == 200
                            && response.body().contains("\"verified\":false"))) {
                        requestCode(uuid, username);
                    } else {
                        getLogger().warning(
                                "Status request failed: " + response.statusCode()
                        );
                        sendPlayerMessage(uuid,
                                ChatColor.RED
                                + "Could not check verification. Please try again.");
                    }
                })
                .exceptionally(error -> {
                    getLogger().warning(
                            "Backend connection failed: " + error.getMessage()
                    );
                    sendPlayerMessage(uuid,
                            ChatColor.RED + "Verification service unavailable.");
                    return null;
                });
    }

    private void requestCode(UUID uuid, String username) {
        String body = "{\"uuid\":\"" + uuid
                + "\",\"username\":\"" + username + "\"}";

        HttpRequest request = authenticatedRequest(
                backendUrl + "/api/verification/start"
        )
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        Matcher matcher = Pattern.compile(
                                "\"code\"\\s*:\\s*\"(\\d{6})\""
                        ).matcher(response.body());

                        if (matcher.find()) {
                            String code = matcher.group(1);

                            sendPlayerMessage(uuid, "");
                            sendPlayerMessage(uuid,
                                    ChatColor.LIGHT_PURPLE.toString()
                                    + ChatColor.BOLD + "YOUR CODE: "
                                    + ChatColor.WHITE + code);
                            sendPlayerMessage(uuid,
                                    ChatColor.YELLOW
                                    + "Enter /verify " + code);
                            sendPlayerMessage(uuid, "");
                        }
                    } else {
                        getLogger().warning(
                                "Code request failed: " + response.statusCode()
                        );
                        sendPlayerMessage(uuid,
                                ChatColor.RED + "Could not generate your code.");
                    }
                })
                .exceptionally(error -> {
                    getLogger().warning(
                            "Could not generate code: " + error.getMessage()
                    );
                    sendPlayerMessage(uuid,
                            ChatColor.RED + "Could not contact verification service.");
                    return null;
                });
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
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

        if (backendUrl.isBlank() || apiSecret.isBlank()) {
            player.sendMessage(ChatColor.RED
                    + "Verification service is not configured.");
            return true;
        }

        UUID uuid = player.getUniqueId();
        String code = args[0];

        player.sendMessage(ChatColor.YELLOW
                + "Checking your verification code...");

        String body = "{\"uuid\":\"" + uuid
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
                        runForPlayer(uuid, onlinePlayer ->
                                onlinePlayer.kickPlayer(
                                        ChatColor.GREEN
                                        + "Your account has been verified!"
                                )
                        );
                    } else {
                        sendPlayerMessage(uuid,
                                ChatColor.RED
                                + "Incorrect or expired verification code.");
                    }
                })
                .exceptionally(error -> {
                    getLogger().warning(
                            "Verification request failed: " + error.getMessage()
                    );
                    sendPlayerMessage(uuid,
                            ChatColor.RED
                            + "Could not contact verification service.");
                    return null;
                });

        return true;
    }

    private void sendPlayerMessage(UUID uuid, String message) {
        runForPlayer(uuid, player -> player.sendMessage(message));
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
        if (!event.getPlayer().getWorld().equals(verificationWorld)) {
            return;
        }

        if (event instanceof PlayerTeleportEvent) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null) return;

        if (from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.getPlayer().getWorld().equals(verificationWorld)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.getPlayer().getWorld().equals(verificationWorld)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getPlayer().getWorld().equals(verificationWorld)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (event.getPlayer().getWorld().equals(verificationWorld)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && player.getWorld().equals(verificationWorld)) {
            event.setCancelled(true);
        }
    }
}

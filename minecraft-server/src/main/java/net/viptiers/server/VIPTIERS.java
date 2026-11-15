
package net.viptiers.server;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VIPTIERS extends JavaPlugin
        implements Listener, CommandExecutor {

    private HttpClient http;
    private String backendUrl;
    private String apiSecret;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        backendUrl = getConfig().getString("backend-url", "")
                .replaceAll("/+$", "");
        apiSecret = getConfig().getString("api-secret", "");

        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        if (backendUrl.isBlank() || apiSecret.isBlank()) {
            getLogger().severe("Backend URL or API secret is missing!");
        }

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("verify") != null) {
            getCommand("verify").setExecutor(this);
        }

        getLogger().info("VIPTIERS verification plugin enabled!");
    }

    private HttpRequest.Builder authenticatedRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
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

        World world = player.getWorld();
        player.teleport(new Location(world, 0.5, 200, 0.5));

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

        checkPlayerStatus(player);
    }

    private void checkPlayerStatus(Player player) {
        if (backendUrl.isBlank() || apiSecret.isBlank()) {
            return;
        }

        String uuid = player.getUniqueId().toString();

        HttpRequest request = authenticatedRequest(
                backendUrl + "/api/verification/status/" + uuid
        ).GET().build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (!player.isOnline()) return;

                    if (response.statusCode() == 200
                            && response.body().contains("\"verified\":true")) {
                        Bukkit.getScheduler().runTask(this, () -> {
                            if (player.isOnline()) {
                                player.kickPlayer(
                                        ChatColor.GREEN
                                        + "You are already verified!"
                                );
                            }
                        });
                    } else if (response.statusCode() == 404
                            || (response.statusCode() == 200
                            && response.body().contains("\"verified\":false"))) {
                        requestCode(player);
                    } else {
                        sendPlayerMessage(player,
                                ChatColor.RED
                                + "Could not check verification. Please try again.");
                        getLogger().warning(
                                "Verification status request failed: "
                                + response.statusCode()
                        );
                    }
                })
                .exceptionally(error -> {
                    getLogger().warning(
                            "Backend connection failed: " + error.getMessage()
                    );
                    sendPlayerMessage(player,
                            ChatColor.RED + "Verification service unavailable.");
                    return null;
                });
    }

    private void requestCode(Player player) {
        String uuid = player.getUniqueId().toString();
        String username = player.getName();

        String body = "{\"uuid\":\"" + uuid
                + "\",\"username\":\"" + username + "\"}";

        HttpRequest request = authenticatedRequest(
                backendUrl + "/api/verification/start"
        )
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (!player.isOnline()) return;

                    if (response.statusCode() == 200) {
                        Matcher matcher = Pattern.compile(
                                "\"code\"\\s*:\\s*\"(\\d{6})\""
                        ).matcher(response.body());

                        if (matcher.find()) {
                            String code = matcher.group(1);

                            sendPlayerMessage(player, "");
                            sendPlayerMessage(player,
                                    ChatColor.LIGHT_PURPLE.toString()
                                    + ChatColor.BOLD + "YOUR CODE: "
                                    + ChatColor.WHITE + code);
                            sendPlayerMessage(player,
                                    ChatColor.YELLOW
                                    + "Enter /verify " + code);
                            sendPlayerMessage(player, "");
                        }
                    } else {
                        sendPlayerMessage(player,
                                ChatColor.RED + "Could not generate your code.");
                    }
                })
                .exceptionally(error -> {
                    getLogger().warning(
                            "Could not generate code: " + error.getMessage()
                    );
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

        player.sendMessage(ChatColor.YELLOW
                + "Checking your verification code...");

        String uuid = player.getUniqueId().toString();
        String code = args[0];

        String body = "{\"uuid\":\"" + uuid
                + "\",\"code\":\"" + code + "\"}";

        HttpRequest request = authenticatedRequest(
                backendUrl + "/api/verification/complete"
        )
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (!player.isOnline()) return;

                    if (response.statusCode() == 200
                            && response.body().contains("\"verified\":true")) {
                        Bukkit.getScheduler().runTask(this, () -> {
                            if (player.isOnline()) {
                                player.kickPlayer(
                                        ChatColor.GREEN
                                        + "Your account has been verified!"
                                );
                            }
                        });
                    } else {
                        sendPlayerMessage(player,
                                ChatColor.RED
                                + "Incorrect or expired verification code.");
                    }
                })
                .exceptionally(error -> {
                    sendPlayerMessage(player,
                            ChatColor.RED + "Could not contact verification service.");
                    getLogger().warning(
                            "Verification request failed: " + error.getMessage()
                    );
                    return null;
                });

        return true;
    }

    private void sendPlayerMessage(Player player, String message) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                player.sendMessage(message);
            }
        });
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) return;
        if (event.getTo() == null) return;

        Location from = event.getFrom();
        Location to = event.getTo();

        if (from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ()) {
            event.setTo(from);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Nothing needed here yet.
    }
}

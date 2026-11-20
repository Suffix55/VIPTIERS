
package net.viptiers.verification;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class VIPTIERSVerification extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        saveDefaultConfig();

        getServer().getPluginManager().registerEvents(this, this);

        createVerificationPlatform();

        getLogger().info("VIPTIERS Verification enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("VIPTIERS Verification disabled!");
    }

    private Location getVerificationSpawn() {
        String worldName = getConfig().getString("spawn.world", "world");

        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            getLogger().warning("Verification world not found: " + worldName);
            world = Bukkit.getWorlds().get(0);
        }

        double x = getConfig().getDouble("spawn.x", 0.5);
        double y = getConfig().getDouble("spawn.y", 150);
        double z = getConfig().getDouble("spawn.z", 0.5);

        float yaw = (float) getConfig().getDouble("spawn.yaw", 0);
        float pitch = (float) getConfig().getDouble("spawn.pitch", 0);

        return new Location(world, x, y, z, yaw, pitch);
    }

    private void createVerificationPlatform() {
        Location spawn = getVerificationSpawn();

        World world = spawn.getWorld();

        if (world == null) return;

        int radius = getConfig().getInt("spawn.platform-radius", 4);

        Material material = Material.GLASS;

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {

                Location blockLocation = new Location(
                    world,
                    spawn.getBlockX() + x,
                    spawn.getBlockY() - 1,
                    spawn.getBlockZ() + z
                );

                blockLocation.getBlock().setType(material);
            }
        }

        getLogger().info("Verification platform created!");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        Bukkit.getScheduler().runTask(this, () -> {
            Location spawn = getVerificationSpawn();

            player.teleport(spawn);

            player.sendMessage("");
            player.sendMessage(ChatColor.LIGHT_PURPLE + "      VIPTIERS");
            player.sendMessage(ChatColor.GRAY + "Welcome, " + player.getName() + "!");
            player.sendMessage(ChatColor.YELLOW + "Account verification is required.");
            player.sendMessage(ChatColor.GRAY + "Please verify at:");
            player.sendMessage(ChatColor.AQUA + "https://suffix55.github.io/VIPTIERS/");
            player.sendMessage("");
        });
    }
}

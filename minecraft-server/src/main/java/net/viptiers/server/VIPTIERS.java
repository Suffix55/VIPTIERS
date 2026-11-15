
package net.viptiers.server;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class VIPTIERS extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("VIPTIERS Minecraft Server plugin enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("VIPTIERS plugin disabled!");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        player.sendMessage("");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "VIPTIERS");
        player.sendMessage(ChatColor.GRAY + "Welcome, " + player.getName() + "!");
        player.sendMessage("");
        player.sendMessage(ChatColor.YELLOW + "Account verification is required.");
        player.sendMessage(ChatColor.GRAY + "Please visit the VIPTIERS website to verify.");
        player.sendMessage("");
    }
}

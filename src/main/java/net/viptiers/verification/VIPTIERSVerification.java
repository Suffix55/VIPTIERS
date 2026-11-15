
package net.viptiers.verification;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class VIPTIERSVerification extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("VIPTIERS Verification enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("VIPTIERS Verification disabled!");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        player.sendMessage("");
        player.sendMessage(ChatColor.LIGHT_PURPLE + "      VIPTIERS");
        player.sendMessage(ChatColor.GRAY + "Welcome, " + player.getName() + "!");
        player.sendMessage(ChatColor.YELLOW + "Account verification is required.");
        player.sendMessage(ChatColor.GRAY + "Please wait for the verification system.");
        player.sendMessage("");
    }
}

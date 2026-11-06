package com.example.crosschatbackend;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import me.clip.placeholderapi.PlaceholderAPI;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

/**
 * Companion backend plugin for Crosschat (optional).
 *
 * <p>Local chat is no longer intercepted: the proxy lets the original chat packet
 * through, so this backend displays the message normally through whatever chat
 * plugin you use (LuckPerms, TAB, ...). The only job of this plugin is to
 * publish each player's PlaceholderAPI-resolved LuckPerms suffix to the proxy
 * over {@code crosschat:metadata}, so that the copy relayed to other servers
 * shows the same suffix.</p>
 *
 * <p>No special {@code enforce-secure-profiles} setting is required any more,
 * because the proxy does not modify or strip chat signatures.</p>
 */
public class CrossChatBackend extends JavaPlugin {

    private static final String METADATA_CHANNEL = "crosschat:metadata";

    @Override
    public void onEnable() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, METADATA_CHANNEL);
        getServer().getScheduler().runTaskTimer(this, this::publishMetadata, 20L, 20L);
        getLogger().info("CrossChatBackend enabled - publishing suffix metadata on: " + METADATA_CHANNEL);
    }

    private void publishMetadata() {
        LuckPerms luckPerms = getServer().getPluginManager().isPluginEnabled("LuckPerms")
                ? LuckPermsProvider.get() : null;
        boolean placeholderApiEnabled =
                getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");

        for (Player player : Bukkit.getOnlinePlayers()) {
            String suffix = "";
            if (luckPerms != null) {
                User user = luckPerms.getUserManager().getUser(player.getUniqueId());
                if (user != null && user.getCachedData().getMetaData().getSuffix() != null) {
                    suffix = user.getCachedData().getMetaData().getSuffix();
                }
            }
            if (placeholderApiEnabled) {
                suffix = PlaceholderAPI.setPlaceholders(player, suffix);
            }

            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream output = new DataOutputStream(bytes);
                output.writeLong(player.getUniqueId().getMostSignificantBits());
                output.writeLong(player.getUniqueId().getLeastSignificantBits());
                output.writeUTF(suffix);
                player.sendPluginMessage(this, METADATA_CHANNEL, bytes.toByteArray());
            } catch (Exception e) {
                getLogger().warning("Failed to publish metadata for " + player.getName());
            }
        }
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, METADATA_CHANNEL);
        getLogger().info("CrossChatBackend disabled.");
    }
}

package com.example.velocityglobalchat;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.regex.Pattern;

/**
 * Relays chat to the OTHER participating backend servers.
 *
 * <p>The original {@link PlayerChatEvent} is left untouched, so Velocity forwards
 * the (possibly signed) chat packet to the sender's backend server exactly as
 * received. The backend then renders the local message itself, including any
 * LuckPerms / TAB / chat-plugin formatting. This listener only sends the
 * formatted copy to players connected to <em>other</em> participating servers,
 * so nobody ever sees the same message twice.</p>
 */
public class GlobalChatListener {

    /**
     * Detects common MiniMessage tag patterns such as {@code <red>}, {@code </bold>},
     * {@code <#ff0000>}, {@code <!italic>}, {@code <gradient:red:blue>}.
     * Used to auto-select the correct Adventure deserializer.
     */
    private static final Pattern MINI_MESSAGE_TAG =
            Pattern.compile("<[!/]?[a-zA-Z#][a-zA-Z0-9_:#.-]*(?::[^>]*)?>", Pattern.CASE_INSENSITIVE);

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private final ProxyServer server;
    private final Config config;
    private final LuckPermsHook luckPerms;

    public GlobalChatListener(ProxyServer server, Config config, LuckPermsHook luckPerms) {
        this.server   = server;
        this.config   = config;
        this.luckPerms = luckPerms;
    }

    // -------------------------------------------------------------------------
    // Event handler
    // -------------------------------------------------------------------------

    /**
     * Runs at {@link PostOrder#LAST} so that we see the final result of every
     * other proxy plugin (mutes, chat filters, SignedVelocity, ...). If someone
     * already denied the message we must not relay it either.
     *
     * <p>This handler NEVER modifies the event result. Leaving the event alone
     * is what keeps signed chat (1.19.1+) intact and lets the backend display
     * the original message.</p>
     */
    @Subscribe(order = PostOrder.LAST)
    public void onPlayerChat(PlayerChatEvent event) {
        if (!config.isEnabled()) return;

        // Message was denied by another plugin -> nothing to relay.
        if (!event.getResult().isAllowed()) return;

        Player player = event.getPlayer();

        // Resolve current backend server
        String serverName = player.getCurrentServer()
                .map(conn -> conn.getServerInfo().getName())
                .orElse(null);

        if (serverName == null) return;

        // Sender's server must itself participate in cross-server chat
        if (!isParticipating(serverName)) return;

        String prefix      = luckPerms.getPrefix(player);
        String suffix      = luckPerms.getSuffix(player);
        String group       = luckPerms.getGroup(player);
        String displayName = config.getDisplayName(serverName);

        // If another plugin rewrote the message, relay the rewritten text.
        String message = event.getResult().getMessage().orElse(event.getMessage());

        Component formatted = buildComponent(displayName, player.getUsername(), prefix, suffix, group, message);

        broadcastToOtherServers(formatted, serverName);

        // Intentionally NO event.setResult(...) here.
    }

    // -------------------------------------------------------------------------
    // Formatting
    // -------------------------------------------------------------------------

    /**
     * Builds the final Adventure Component.
     *
     * <p>The format string is split at {@code {message}} so the player's message
     * content is always inserted as a plain-text Component — this prevents
     * players from injecting colour codes or MiniMessage tags into the chat
     * format and impersonating prefixes or formatting other players' names.</p>
     */
    private Component buildComponent(String serverName, String playerName,
                                     String prefix, String suffix, String group, String message) {
        String format = config.getFormat();

        // Split at the {message} placeholder (max 2 parts)
        String[] parts = format.split("\\{message\\}", 2);

        String prefixPart = applyPlaceholders(parts[0], serverName, playerName, prefix, suffix, group);
        String suffixPart = parts.length > 1
            ? applyPlaceholders(parts[1], serverName, playerName, prefix, suffix, group)
                : "";

        Component prefixComp  = deserialize(prefixPart);
        Component messageComp = Component.text(message);   // always plain — no injection
        Component suffixComp  = suffixPart.isEmpty()
                ? Component.empty()
                : deserialize(suffixPart);

        return prefixComp.append(messageComp).append(suffixComp);
    }

    private static String applyPlaceholders(String text, String serverName,
                            String playerName, String prefix,
                            String suffix, String group) {
        return text
                .replace("{server}",  serverName)
                .replace("{player}",  playerName)
            .replace("{prefix}",  prefix)
            .replace("{suffix}",  suffix)
            .replace("{group}",   group);
    }

    /**
     * Auto-detects the format used in the string and returns the appropriate
     * Adventure Component:
     * <ul>
     *   <li>MiniMessage — if the text contains {@code <tag>} patterns</li>
     *   <li>Legacy {@code &} codes — otherwise (the default config style)</li>
     * </ul>
     */
    private static Component deserialize(String text) {
        if (text.isEmpty()) return Component.empty();
        if (MINI_MESSAGE_TAG.matcher(text).find()) {
            return MINI_MESSAGE.deserialize(text);
        }
        return LEGACY.deserialize(text);
    }

    // -------------------------------------------------------------------------
    // Broadcasting
    // -------------------------------------------------------------------------

    /** True if the given server takes part in cross-server chat (empty list = all). */
    private boolean isParticipating(String serverName) {
        return config.getServers().isEmpty()
                || config.getServers().contains(serverName.toLowerCase());
    }

    /**
     * Sends the component to every player that is connected to a participating
     * server OTHER than {@code senderServerName}.
     *
     * <p>Players on the sender's server are skipped on purpose: they already
     * receive the original message from the backend, so including them would
     * produce a duplicate. The sender is on that server too, so the sender is
     * never sent a copy either.</p>
     */
    private void broadcastToOtherServers(Component component, String senderServerName) {
        for (Player p : server.getAllPlayers()) {
            p.getCurrentServer().ifPresent(conn -> {
                String targetServer = conn.getServerInfo().getName();

                // Same backend as the sender -> they see the local message already.
                if (targetServer.equalsIgnoreCase(senderServerName)) return;

                // Other server must participate in cross-server chat.
                if (!isParticipating(targetServer)) return;

                p.sendMessage(component);
            });
        }
    }
}

package com.lawlessmc.discordchatguard;

import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Plugin(
        id = "discordchatguard",
        name = "DiscordChatGuard",
        version = "1.0.0",
        description = "Silently drops webhook-bound chat spam. In-game chat is not touched.",
        authors = {"wilderop"},
        dependencies = {@Dependency(id = "discord")}
)
public class DiscordChatGuard {

    private static final String VD_CLASS = "ooo.foooooooooooo.velocitydiscord.VelocityDiscord";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private ChatLimiter limiter;
    private String bypassPermission = "discordchatguard.bypass";
    private Object originalListener;
    private Method onPlayerChat;
    private Method onConnect;
    private Method onDisconnect;
    private Method onProxyShutdown;
    private boolean hooked;

    @Inject
    public DiscordChatGuard(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe(order = PostOrder.LAST)
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadConfig();
        try {
            hookVelocityDiscord();
        } catch (Exception e) {
            logger.error("Could not hook Velocity Discord Bridge; webhook chat is unfiltered", e);
        }
    }

    private void loadConfig() {
        try {
            Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve("config.yml");
            if (!Files.exists(file)) {
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in != null) {
                        Files.copy(in, file);
                    }
                }
            }
            Map<String, String> yaml = Files.exists(file) ? parseSimpleYaml(file) : Map.of();
            int playerWindow = asInt(yaml.get("player-window-seconds"), 8);
            int playerMax = asInt(yaml.get("player-max-per-window"), 4);
            int repeatWindow = asInt(yaml.get("repeat-window-seconds"), 15);
            int globalWindow = asInt(yaml.get("global-window-seconds"), 3);
            int globalMax = asInt(yaml.get("global-max-per-window"), 12);
            String bypass = yaml.get("bypass-permission");
            if (bypass != null && !bypass.isBlank()) {
                bypassPermission = bypass.trim();
            }
            limiter = new ChatLimiter(playerWindow, playerMax, repeatWindow, globalWindow, globalMax);
        } catch (IOException e) {
            logger.warn("Using built-in limiter defaults: {}", e.getMessage());
            limiter = new ChatLimiter(8, 4, 15, 3, 12);
        }
    }

    private static Map<String, String> parseSimpleYaml(Path file) throws IOException {
        Map<String, String> out = new HashMap<>();
        for (String raw : Files.readAllLines(file)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            out.put(key, value);
        }
        return out;
    }

    private static int asInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void hookVelocityDiscord() throws Exception {
        Class<?> vd = Class.forName(VD_CLASS);
        originalListener = vd.getMethod("getListener").invoke(null);
        if (originalListener == null) {
            logger.warn("Velocity Discord Bridge has no listener yet; webhook chat unfiltered");
            return;
        }
        Optional<PluginContainer> container = server.getPluginManager().getPlugin("discord");
        if (container.isEmpty() || container.get().getInstance().isEmpty()) {
            logger.warn("Velocity Discord Bridge instance missing; webhook chat unfiltered");
            return;
        }
        Object discordPlugin = container.get().getInstance().get();
        Class<?> listenerType = originalListener.getClass();
        onPlayerChat = listenerType.getMethod("onPlayerChat", PlayerChatEvent.class);
        onConnect = listenerType.getMethod("onConnect", ServerConnectedEvent.class);
        onDisconnect = listenerType.getMethod("onDisconnect", DisconnectEvent.class);
        onProxyShutdown = listenerType.getMethod("onProxyShutdown", ProxyShutdownEvent.class);

        server.getEventManager().unregisterListener(discordPlugin, originalListener);
        hooked = true;
        logger.info("Discord webhook chat is filtered; in-game chat is unchanged");
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onPlayerChat(PlayerChatEvent event) {
        if (!hooked) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission(bypassPermission)) {
            invoke(onPlayerChat, event);
            return;
        }
        UUID uuid = player.getUniqueId();
        if (limiter.allow(uuid, event.getMessage())) {
            invoke(onPlayerChat, event);
        }
        // Denied: Minecraft chat still flows. Do not cancel, do not notify.
    }

    @Subscribe
    public void onConnect(ServerConnectedEvent event) {
        invoke(onConnect, event);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        invoke(onDisconnect, event);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        invoke(onProxyShutdown, event);
    }

    private void invoke(Method method, Object event) {
        if (!hooked || method == null || originalListener == null) {
            return;
        }
        try {
            method.invoke(originalListener, event);
        } catch (Exception e) {
            logger.warn("Failed to forward {} to Velocity Discord Bridge: {}", method.getName(), e.getMessage());
        }
    }
}

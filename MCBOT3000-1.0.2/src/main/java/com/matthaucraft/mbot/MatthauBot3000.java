package com.matthaucraft.mbot;

import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class MatthauBot3000 implements ModInitializer {
    public static final String MOD_ID = "matthau_bot_3000";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = Path.of("config", "matthau-bot-3000.json");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private BotConfig config;
    private DiscordBridge discord;

    @Override
    public void onInitialize() {
        config = loadConfig();
        LOGGER.info("[MatthauBot 3000] Configuration loaded from {}.", CONFIG_PATH);
        registerMinecraftEvents();
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> currentServer = server);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            currentServer = null;
            if (discord != null) discord.shutdown();
        });
        if (config != null && config.isConfigured()) {
            LOGGER.info("[MatthauBot 3000] Configuration accepted. Starting Discord bridge.");
            discord = new DiscordBridge(config);
            discord.start();
        } else {
            LOGGER.warn("MatthauBot 3000 is not configured. Edit {} and restart.", CONFIG_PATH);
        }
    }

    private void registerMinecraftEvents() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, bound) -> {
            if (discord != null) {
                String text = message.decoratedContent().getString();
                if (!text.isBlank()) {
                    discord.sendMinecraftChat(sender.getGameProfile().name(), text);
                }
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            if (discord != null) discord.sendJoin(player.getGameProfile().name());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.player;
            if (discord != null) discord.sendLeave(player.getGameProfile().name());
        });
    }

    private BotConfig loadConfig() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            if (!Files.exists(CONFIG_PATH)) {
                BotConfig cfg = BotConfig.defaults();
                Files.writeString(CONFIG_PATH, GSON.toJson(cfg), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
                LOGGER.info("Created default configuration at {}.", CONFIG_PATH);
                return cfg;
            }

            BotConfig cfg = GSON.fromJson(Files.readString(CONFIG_PATH), BotConfig.class);
            if (cfg == null) {
                LOGGER.error("Configuration at {} is empty or contains JSON null. "
                        + "The file was preserved; the Discord bridge will remain disabled.", CONFIG_PATH);
                return null;
            }

            if (cfg.joinMessages == null) {
                LOGGER.warn("joinMessages is null in {}. Login notifications are disabled.", CONFIG_PATH);
                cfg.joinMessages = new ArrayList<>();
            }
            if (cfg.leaveMessages == null) {
                LOGGER.warn("leaveMessages is null in {}. Logout notifications are disabled.", CONFIG_PATH);
                cfg.leaveMessages = new ArrayList<>();
            }
            if (cfg.botToken == null) cfg.botToken = "";
            if (cfg.channelId == null) cfg.channelId = "";

            return cfg;
        } catch (Exception e) {
            LOGGER.error("Could not load configuration at {} ({}). "
                    + "The existing file was not overwritten; the Discord bridge will remain disabled.",
                    CONFIG_PATH, e.getClass().getSimpleName());
            return null;
        }
    }

    private static class BotConfig {
        String botToken = "";
        String channelId = "";
        List<String> joinMessages = new ArrayList<>();
        List<String> leaveMessages = new ArrayList<>();
        boolean randomJoinMessages = true;
        boolean randomLeaveMessages = true;
        boolean avoidImmediateRepeat = true;

        boolean isConfigured() {
            return botToken != null && !botToken.isBlank()
                    && !botToken.contains("PUT_NEW_DISCORD_BOT_TOKEN")
                    && channelId != null && !channelId.isBlank();
        }

        static BotConfig defaults() {
            BotConfig c = new BotConfig();
            c.joinMessages = List.of(
                    "%player% joined the server.",
                    "%player% has arrived.",
                    "Welcome, %player%!",
                    "%player% connected.",
                    "%player% is now online."
            );
            c.leaveMessages = List.of(
                    "%player% left the server.",
                    "%player% has left the game.",
                    "Goodbye, %player%!",
                    "%player% disconnected.",
                    "%player% is now offline."
            );
            return c;
        }
    }

    private class DiscordBridge {
        private final BotConfig cfg;
        private volatile WebSocket socket;
        private volatile long heartbeatIntervalMs = 45000;
        private final AtomicBoolean reconnecting = new AtomicBoolean(false);
        private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> reconnectTask;
        private final AtomicLong sequenceNumber = new AtomicLong(-1);
        private volatile ScheduledFuture<?> heartbeatTask;
        private volatile int lastJoinIndex = -1, lastLeaveIndex = -1;
        private final Random random = new Random();

        DiscordBridge(BotConfig cfg) { this.cfg = cfg; }

        void start() {
            shuttingDown.set(false);
            LOGGER.info("[MatthauBot 3000] Connecting to Discord...");
            scheduler.execute(this::connect);
        }

        private void connect() {
            if (shuttingDown.get()) return;
            cancelHeartbeat();
            sequenceNumber.set(-1);
            socket = null;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://discord.com/api/v10/gateway/bot"))
                        .header("Authorization", "Bot " + cfg.botToken).GET().build();
                HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    LOGGER.error("[MatthauBot 3000] Discord gateway lookup failed: HTTP {} {}", response.statusCode(), response.body());
                    scheduleReconnect();
                    return;
                }
                if (shuttingDown.get()) return;
                String gateway = JsonParser.parseString(response.body()).getAsJsonObject()
                        .get("url").getAsString();
                LOGGER.info("[MatthauBot 3000] Discord Gateway located. Opening WebSocket...");

                http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15))
                        .buildAsync(URI.create(gateway + "?v=10&encoding=json"), new DiscordListener())
                        .exceptionally(ex -> { 
                            if (!shuttingDown.get()) {
                                LOGGER.error("[MatthauBot 3000] Discord WebSocket connection failed: {}", ex.getMessage(), ex);
                                scheduleReconnect();
                            }
                            return null; 
                        });
            } catch (Exception e) {
                LOGGER.error("[MatthauBot 3000] Discord connection error: {}", e.getMessage(), e);
                scheduleReconnect();
            }
        }

        private void identify() {
            JsonObject data = new JsonObject();
            data.addProperty("token", cfg.botToken);
            data.addProperty("intents", 1 | 512 | 32768); // GUILDS + GUILD_MESSAGES + MESSAGE_CONTENT
            JsonObject props = new JsonObject();
            props.addProperty("os", "linux");
            props.addProperty("browser", MOD_ID);
            props.addProperty("device", MOD_ID);
            data.add("properties", props);
            sendGateway(2, data);
        }

        private void sendGateway(int op, JsonElement data) {
            WebSocket ws = socket;
            if (ws == null) return;

            JsonObject payload = new JsonObject();
            payload.addProperty("op", op);
            if (op == 1) {
                long seq = sequenceNumber.get();
                payload.add("d", seq >= 0 ? new JsonPrimitive(seq) : JsonNull.INSTANCE);
            } else {
                payload.add("d", data == null ? JsonNull.INSTANCE : data);
            }
            // Client-to-server Gateway payloads only need op + d.
            ws.sendText(GSON.toJson(payload), true);
        }

        private synchronized void startHeartbeat() {
            cancelHeartbeat();
            long interval = Math.max(1000, heartbeatIntervalMs);
            heartbeatTask = scheduler.scheduleAtFixedRate(
                    () -> sendGateway(1, null),
                    interval, interval, TimeUnit.MILLISECONDS);
        }

        private synchronized void cancelHeartbeat() {
            if (heartbeatTask != null) {
                heartbeatTask.cancel(false);
                heartbeatTask = null;
            }
        }

        synchronized void shutdown() {
            if (!shuttingDown.compareAndSet(false, true)) return;
            LOGGER.info("[MatthauBot 3000] Shutting down Discord bridge...");
            cancelHeartbeat();
            if (reconnectTask != null) {
                reconnectTask.cancel(false);
                reconnectTask = null;
            }
            reconnecting.set(false);
            WebSocket ws = socket;
            socket = null;
            if (ws != null) {
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "Minecraft server stopping");
                } catch (Exception ignored) {
                    ws.abort();
                }
            }
            scheduler.shutdownNow();
            LOGGER.info("[MatthauBot 3000] Discord bridge shut down.");
        }

        void sendMinecraftChat(String player, String text) {
            sendDiscord("**" + sanitize(player) + "**: " + sanitize(text));
        }

        void sendJoin(String player) {
            String msg = choose(cfg.joinMessages, true);
            if (msg != null) sendDiscord(msg.replace("%player%", sanitize(player)));
        }

        void sendLeave(String player) {
            String msg = choose(cfg.leaveMessages, false);
            if (msg != null) sendDiscord(msg.replace("%player%", sanitize(player)));
        }

        private String choose(List<String> list, boolean join) {
            if (list == null || list.isEmpty()) return null;
            if ((join ? cfg.randomJoinMessages : cfg.randomLeaveMessages) && list.size() > 1) {
                int idx;
                do { idx = random.nextInt(list.size()); }
                while (cfg.avoidImmediateRepeat && idx == (join ? lastJoinIndex : lastLeaveIndex));
                if (join) lastJoinIndex = idx; else lastLeaveIndex = idx;
                return list.get(idx);
            }
            return list.get(0);
        }

        private String sanitize(String s) {
            return s.replace("@everyone", "@ everyone").replace("@here", "@ here");
        }

        private void sendDiscord(String content) {
            if (content == null || content.isBlank()) return;
            JsonObject body = new JsonObject();
            body.addProperty("content", content);
            JsonObject allowedMentions = new JsonObject();
            allowedMentions.add("parse", new JsonArray());
            body.add("allowed_mentions", allowedMentions); // no pings
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("https://discord.com/api/v10/channels/" + cfg.channelId + "/messages"))
                    .header("Authorization", "Bot " + cfg.botToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(r -> {
                        if (r.statusCode() / 100 != 2) LOGGER.warn("Discord message failed: HTTP {} {}", r.statusCode(), r.body());
                    })
                    .exceptionally(ex -> { LOGGER.warn("Discord message request failed", ex); return null; });
        }

        private class DiscordListener implements WebSocket.Listener {
            private final StringBuilder buffer = new StringBuilder();

            @Override public void onOpen(WebSocket webSocket) {
                socket = webSocket;
                LOGGER.info("[MatthauBot 3000] Discord WebSocket connected; awaiting Gateway Hello...");
                webSocket.request(1);
            }

            @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    String json = buffer.toString();
                    buffer.setLength(0);
                    handleGateway(json);
                }
                webSocket.request(1);
                return null;
            }

            @Override public void onError(WebSocket webSocket, Throwable error) {
                if (shuttingDown.get()) return;
                LOGGER.error("[MatthauBot 3000] Discord WebSocket error: {}", error.getMessage(), error);
                scheduleReconnect();
            }

            @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                LOGGER.warn("[MatthauBot 3000] Discord WebSocket closed: {} {}", statusCode, reason);
                cancelHeartbeat();
                if (socket == webSocket) socket = null;
                if (!shuttingDown.get()) scheduleReconnect();
                return CompletableFuture.completedFuture(null);
            }

            private void handleGateway(String json) {
                try {
                    JsonObject p = JsonParser.parseString(json).getAsJsonObject();
                    JsonElement seq = p.get("s");
                    if (seq != null && !seq.isJsonNull()) {
                        sequenceNumber.set(seq.getAsLong());
                    }
                    int op = p.get("op").getAsInt();
                    JsonElement d = p.get("d");
                    switch (op) {
                        case 0 -> handleDispatch(p.get("t").isJsonNull() ? "" : p.get("t").getAsString(), d);
                        case 1 -> sendGateway(1, JsonNull.INSTANCE);
                        case 7 -> {
                            cancelHeartbeat();
                            if (socket != null) socket.abort();
                            scheduleReconnect();
                        }
                        case 9 -> {
                            cancelHeartbeat();
                            if (socket != null) socket.abort();
                            scheduleReconnect();
                        }
                        case 10 -> {
                            heartbeatIntervalMs = d.getAsJsonObject().get("heartbeat_interval").getAsLong();
                            identify();
                            startHeartbeat();
                        }
                        case 11 -> { /* heartbeat acknowledged */ }
                        default -> { }
                    }
                } catch (Exception e) {
                    LOGGER.warn("Invalid Discord gateway payload", e);
                }
            }

            private void handleDispatch(String type, JsonElement d) {
                if ("READY".equals(type)) {
                    LOGGER.info("[MatthauBot 3000] Discord authentication successful. Bot is online.");
                    return;
                }
                if (!"MESSAGE_CREATE".equals(type)) return;
                JsonObject m = d.getAsJsonObject();
                String channel = m.has("channel_id") ? m.get("channel_id").getAsString() : "";
                if (!cfg.channelId.equals(channel)) return;
                JsonObject author = m.getAsJsonObject("author");
                if (author != null && author.has("bot") && author.get("bot").getAsBoolean()) return;
                String content = m.has("content") ? m.get("content").getAsString() : "";
                if (content.isBlank()) return;
                String name = author != null && author.has("global_name") && !author.get("global_name").isJsonNull()
                        ? author.get("global_name").getAsString()
                        : (author != null ? author.get("username").getAsString() : "Discord");
                String line = "[Discord] " + name + ": " + content.replace("@everyone", "@ everyone").replace("@here", "@ here");
                MinecraftServer server = getServer();
                if (server != null) server.execute(() -> server.getPlayerList().broadcastSystemMessage(
                        net.minecraft.network.chat.Component.literal(line), false));
            }
        }

        private synchronized void scheduleReconnect() {
            if (shuttingDown.get()) return;
            if (!reconnecting.compareAndSet(false, true)) return;
            reconnectTask = scheduler.schedule(() -> {
                reconnectTask = null;
                reconnecting.set(false);
                if (!shuttingDown.get()) connect();
            }, 5, TimeUnit.SECONDS);
        }
    }

    // Fabric does not expose a global server singleton. This is filled from the first server tick.
    private volatile MinecraftServer currentServer;
    private MinecraftServer getServer() { return currentServer; }
}

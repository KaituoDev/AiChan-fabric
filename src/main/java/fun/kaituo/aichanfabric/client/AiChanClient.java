package fun.kaituo.aichanfabric.client;

import com.google.gson.Gson;
import fun.kaituo.aichanfabric.AiChanfabric;
import fun.kaituo.aichanfabric.AiChanConfig;
import fun.kaituo.aichanfabric.Utils;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AiChanClient extends WebSocketClient {

    public static final Gson GSON = new Gson();
    private final AiChanfabric plugin;
    private final ScheduledExecutorService scheduler;

    private final String serverName;
    private final String trigger;
    private final String broadcastTrigger;

    public AiChanClient(AiChanfabric plugin, URI uri) {
        super(uri);
        this.plugin = plugin;

        AiChanConfig config = plugin.getConfig();
        this.serverName = config.server_name;
        this.trigger = config.trigger;
        this.broadcastTrigger = config.broadcast_trigger;
        int heartbeatInterval = config.heart_beat_interval;

        // 使用原生 Java 线程池替代 Bukkit Scheduler
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        CompletableFuture.runAsync(this::connect);
        this.scheduler.scheduleAtFixedRate(this::keepAlive, heartbeatInterval, heartbeatInterval, TimeUnit.SECONDS);
    }

    public void shutdownScheduler() {
        if (this.scheduler != null && !this.scheduler.isShutdown()) {
            this.scheduler.shutdown();
        }
    }

    public void sendPacket(SocketPacket packet) {
        if (!isOpen()) {
            AiChanfabric.LOGGER.warn("连接处于关闭状态，发送包失败");
            keepAlive();
            return;
        }
        String data = GSON.toJson(packet);
        String encryptedData = plugin.getFernetManager().encrypt(data);
        send(encryptedData);
    }

    private void keepAlive() {
        if (!isOpen()) {
            reconnect();
        }
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        AiChanfabric.LOGGER.info("已连接到WebSocket服务器: {}", getRemoteSocketAddress());

        SocketPacket helloPacket = new SocketPacket(SocketPacket.PacketType.SERVER_HELLO_TO_BOT);
        helloPacket.add(0, serverName);
        helloPacket.add(1, trigger);
        helloPacket.add(2, broadcastTrigger);
        sendPacket(helloPacket);
    }

    @Override
    public void onMessage(String s) {
        String data = plugin.getFernetManager().decrypt(s);
        SocketPacket packet = SocketPacket.fromJsonString(data);

        switch (packet.getPacketType()) {
            case BOT_CHAT_TO_SERVER -> {
                if (packet.get(0).equals(this.trigger)) {
                    break;
                }
                AiChanConfig config = plugin.getConfig();
                if (!config.sync_chat) {
                    break;
                }
                String message = Utils.fixMinecraftColor(packet.get(1));
                // 回到主线程广播
                plugin.getServer().execute(() -> plugin.getServer().getPlayerManager().broadcast(Text.literal(message), false));
            }
            case BOT_LIST_REQUEST_TO_SERVER -> {
                SocketPacket listPacket = new SocketPacket(SocketPacket.PacketType.SERVER_COMMAND_FEEDBACK_TO_BOT);
                String contextJson = packet.get(0);
                listPacket.add(0, contextJson);

                // 回到主线程获取玩家列表（Fabric线程安全要求）
                plugin.getServer().execute(() -> {
                    List<ServerPlayerEntity> players = plugin.getServer().getPlayerManager().getPlayerList();
                    if (players.isEmpty()) {
                        listPacket.add(1, String.format("%s无人在线", this.serverName));
                    } else {
                        StringJoiner listMessage = new StringJoiner(", ");
                        for (ServerPlayerEntity player : players) {
                            listMessage.add(player.getName().getString());
                        }
                        listPacket.add(1, String.format("%s有 %d 人在线: %s", this.serverName, players.size(), listMessage));
                    }
                    plugin.getClient().sendPacket(listPacket);
                });
            }
            case BOT_COMMAND_TO_SERVER -> {
                String contextJson = packet.get(0);
                if (packet.get(1).equals(this.trigger) || packet.get(1).equals(this.broadcastTrigger)) {
                    plugin.executeBotCommand(packet.get(2), contextJson); // 内部已做线程同步
                }
            }
            case BOT_PLAYER_LOOKUP_RESULT_TO_SERVER -> {
                AiChanConfig config = plugin.getConfig();
                if (!config.enable_whitelist) {
                    break;
                }
                String mcId = packet.get(0);
                String sessionId = packet.get(1);
                boolean isAuthorized = Boolean.parseBoolean(packet.get(2));
                boolean isBanned = Boolean.parseBoolean(packet.get(3));

                if (isBanned) {
                    plugin.rejectLogin(sessionId, config.banned_message);
                } else if (!isAuthorized) {
                    plugin.rejectLogin(sessionId, config.not_whitelisted_message);
                } else {
                    plugin.acceptLogin(sessionId);
                }
            }
            case BOT_PLAYER_BAN_TO_SERVER -> {
                AiChanConfig config = plugin.getConfig();
                if (!config.enable_whitelist) {
                    break;
                }
                String mcId = packet.get(0);
                plugin.getServer().execute(() ->
                    plugin.kickPlayerIfOnline(mcId, config.banned_message)
                );
            }
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        AiChanfabric.LOGGER.warn("连接断开，状态码为 {}，额外信息为 {}", code, reason);
    }

    @Override
    public void onError(Exception e) {
        AiChanfabric.LOGGER.warn("发生内部错误: {}", e.getMessage());
    }
}
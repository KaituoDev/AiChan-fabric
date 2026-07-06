package fun.kaituo.aichanfabric;

import fun.kaituo.aichanfabric.client.AiChanClient;
import fun.kaituo.aichanfabric.client.SocketPacket;
import fun.kaituo.aichanfabric.mixin.ServerLoginNetworkHandlerAccessor;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class AiChanfabric implements DedicatedServerModInitializer {

	public static final Logger LOGGER = LoggerFactory.getLogger("aichan-fabric");
	private FernetManager fernetManager;
	private AiChanClient client;
	private AiChanConfig config;
	private MinecraftServer server;
	private final ConcurrentHashMap<String, ServerLoginNetworkHandler> pendingLogins = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, CompletableFuture<Void>> loginFutures = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<ServerLoginNetworkHandler, String> sessionIds = new ConcurrentHashMap<>();
	private final AtomicLong sessionCounter = new AtomicLong(0);
	private ScheduledExecutorService whitelistScheduler;

	@Override
	public void onInitializeServer() {
		this.config = AiChanConfig.load();

		try {
			this.fernetManager = new FernetManager(config.fernet_key);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to initialize fernet manager!");
		}

		// 获取 Server 实例
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			this.server = server;
			this.whitelistScheduler = Executors.newSingleThreadScheduledExecutor();
		});

		// 服务器启动后连接 WebSocket
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			try {
				String uriString = "ws://" + config.ip + ":" + config.port;
				this.client = new AiChanClient(this, new URI(uriString));
			} catch (URISyntaxException e) {
				throw new RuntimeException(e);
			}
			LOGGER.info("AiChanFabric 已加载！");
		});

		// 服务器关闭时断开连接
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (this.client != null) {
				this.client.close();
				this.client.shutdownScheduler();
			}
			if (this.whitelistScheduler != null) {
				this.whitelistScheduler.shutdown();
			}
			LOGGER.info("AiChanFabric 已卸载！");
		});

		registerEvents();
		registerWhitelistEvents();
	}

	private void registerEvents() {
		// 监听聊天事件
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			SocketPacket packet = new SocketPacket(SocketPacket.PacketType.SERVER_CHAT_TO_BOT);
			String msg = String.format("%s: %s", sender.getName().getString(), message.getContent().getString());
			msg = Utils.fixMinecraftColor(msg);
			packet.add(0, config.trigger);
			packet.add(1, config.server_prefix + " " + msg);
			this.client.sendPacket(packet);
		});

		// 监听进退服事件
		if (config.notify_on_join_and_quit) {
			ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
				SocketPacket packet = new SocketPacket(SocketPacket.PacketType.SERVER_INFORMATION_TO_BOT);
				String welcomeMessage = String.format("%s%s[+]", config.server_prefix, handler.getPlayer().getName().getString());
				packet.add(0, welcomeMessage);
				this.client.sendPacket(packet);
			});

			ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
				SocketPacket packet = new SocketPacket(SocketPacket.PacketType.SERVER_INFORMATION_TO_BOT);
				packet.add(0, String.format("%s%s[-]", config.server_prefix, handler.getPlayer().getName().getString()));
				this.client.sendPacket(packet);
			});
		}
	}

	public void executeBotCommand(String cmd, String contextJson) {
		// 确保在主线程执行指令
		server.execute(() -> {
			// Fabric 的黑魔法：创建一个拦截输出的 CommandOutput
			CommandOutput output = new CommandOutput() {
				@Override
				public void sendMessage(Text message) {
					SocketPacket packet = new SocketPacket(SocketPacket.PacketType.SERVER_COMMAND_FEEDBACK_TO_BOT);
					String plainText = message.getString();
					packet.add(0, contextJson);
					packet.add(1, Utils.fixMinecraftColor(config.server_prefix + " " + plainText));
					client.sendPacket(packet);
				}

				@Override
				public boolean shouldReceiveFeedback() { return true; }
				@Override
				public boolean shouldTrackOutput() { return true; }
				@Override
				public boolean shouldBroadcastConsoleToOps() { return false; }
			};

			// 使用包装后的 Output 构造执行者，强制以最高权限(4级)静默运行
			ServerCommandSource customSource = server.getCommandSource()
					.withOutput(output)
					.withLevel(4);

			server.getCommandManager().executeWithPrefix(customSource, cmd);
		});
	}

	public void kickPlayerIfOnline(String name, String message) {
		if (server.getPlayerManager().getPlayer(name) == null) {
			return;
		}
		server.getCommandManager().executeWithPrefix(
			server.getCommandSource().withLevel(4),
			"kick " + name + " " + message
		);
	}

	public boolean rejectLogin(String sessionId, String message) {
		ServerLoginNetworkHandler handler = pendingLogins.remove(sessionId);
		CompletableFuture<Void> future = loginFutures.remove(sessionId);
		if (handler != null) {
			handler.disconnect(Text.literal(message));
			sessionIds.remove(handler);
		}
		if (future != null) {
			future.complete(null);
		}
		return handler != null;
	}

	public void acceptLogin(String sessionId) {
		ServerLoginNetworkHandler handler = pendingLogins.remove(sessionId);
		CompletableFuture<Void> future = loginFutures.remove(sessionId);
		if (handler != null) {
			sessionIds.remove(handler);
		}
		if (future != null) {
			future.complete(null);
		}
	}

	private void registerWhitelistEvents() {
		if (!config.enable_whitelist) {
			return;
		}

		ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> {
			String name = ((ServerLoginNetworkHandlerAccessor) handler).getProfileName().toLowerCase();
			String sessionId = String.valueOf(sessionCounter.incrementAndGet());

			CompletableFuture<Void> future = new CompletableFuture<>();
			synchronizer.waitFor(future);

			pendingLogins.put(sessionId, handler);
			loginFutures.put(sessionId, future);
			sessionIds.put(handler, sessionId);

			SocketPacket packet = new SocketPacket(SocketPacket.PacketType.SERVER_PLAYER_LOOKUP_REQUEST_TO_BOT);
			packet.add(0, name);
			packet.add(1, sessionId);
			client.sendPacket(packet);

			whitelistScheduler.schedule(
					() -> server.execute(() -> rejectLogin(sessionId, config.timeout_message)),
					config.whitelist_timeout, TimeUnit.SECONDS
			);
		});

		ServerLoginConnectionEvents.DISCONNECT.register((handler, server) -> {
			String sessionId = sessionIds.remove(handler);
			if (sessionId != null) {
				pendingLogins.remove(sessionId);
				CompletableFuture<Void> future = loginFutures.remove(sessionId);
				if (future != null) {
					future.complete(null);
				}
			}
		});
	}

	public AiChanConfig getConfig() { return config; }
	public FernetManager getFernetManager() { return fernetManager; }
	public AiChanClient getClient() { return client; }
	public MinecraftServer getServer() { return server; }
}

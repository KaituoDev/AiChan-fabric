package fun.kaituo.aichanfabric;

import fun.kaituo.aichanfabric.client.AiChanClient;
import fun.kaituo.aichanfabric.client.SocketPacket;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;

public class AiChanfabric implements DedicatedServerModInitializer {

	public static final Logger LOGGER = LoggerFactory.getLogger("aichan-fabric");
	private FernetManager fernetManager;
	private AiChanClient client;
	private AiChanConfig config;
	private MinecraftServer server;

	@Override
	public void onInitializeServer() {
		this.config = AiChanConfig.load();

		try {
			this.fernetManager = new FernetManager(config.fernet_key);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to initialize fernet manager!");
		}

		// 获取 Server 实例
		ServerLifecycleEvents.SERVER_STARTING.register(server -> this.server = server);

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
			LOGGER.info("AiChanFabric 已卸载！");
		});

		registerEvents();
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

	public AiChanConfig getConfig() { return config; }
	public FernetManager getFernetManager() { return fernetManager; }
	public AiChanClient getClient() { return client; }
	public MinecraftServer getServer() { return server; }
}
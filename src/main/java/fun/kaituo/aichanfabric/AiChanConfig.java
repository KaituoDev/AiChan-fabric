package fun.kaituo.aichanfabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

public class AiChanConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("aichan-fabric.json");
    private static final File CONFIG_FILE = CONFIG_PATH.toFile();

    public String ip = "127.0.0.1";
    public int port = 23333;
    public String server_name = "Fabric服";
    public String server_prefix = "§8[§bFabric§8]§r";
    public String trigger = "f";
    public String broadcast_trigger = "all";
    public String fernet_key = "YOUR_FERNET_KEY_HERE";
    public boolean enable_whitelist = true;
    public int whitelist_timeout = 1;
    public String not_whitelisted_message = "§c请加QQ群 594048732 自助获取白名单！";
    public String banned_message = "§c你已被封禁！请加QQ群 594048732 咨询解封！";
    public String timeout_message = "§c白名单认证超时，请稍后重试！";
    public boolean notify_on_join_and_quit = true;
    public int heart_beat_interval = 900;

    public static AiChanConfig load() {
        if (!CONFIG_FILE.getParentFile().exists()) {
            CONFIG_FILE.getParentFile().mkdirs();
        }
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                return GSON.fromJson(reader, AiChanConfig.class);
            } catch (IOException e) {
                AiChanfabric.LOGGER.error("Failed to read config file!", e);
            }
        }

        AiChanConfig config = new AiChanConfig();
        config.save();
        return config;
    }

    public void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            AiChanfabric.LOGGER.error("Failed to save config file!", e);
        }
    }
}
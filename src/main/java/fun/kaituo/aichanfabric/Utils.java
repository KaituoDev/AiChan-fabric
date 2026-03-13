package fun.kaituo.aichanfabric;

public class Utils {
    public static String fixMinecraftColor(String message) {
        return message.replaceAll("&([0-9a-fk-or])","§$1" );
    }
}
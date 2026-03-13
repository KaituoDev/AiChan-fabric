package fun.kaituo.aichanfabric.client;


import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

// Remember to update Packet on the other end
public class SocketPacket {

    public static final Gson GSON = new Gson();
    @Expose
    @SerializedName("packetType")
    private final PacketType packetType;
    @Expose
    @SerializedName("content")
    private final List<String> content = new ArrayList<>();

    public SocketPacket(PacketType packetType) {
        this.packetType = packetType;
    }

    @SuppressWarnings("unused")
    public static SocketPacket fromJsonString(String string) {
        return GSON.fromJson(string, SocketPacket.class);
    }

    @SuppressWarnings("unused")
    public String toJsonString() {
        return GSON.toJson(this);
    }

    public PacketType getPacketType() {
        return packetType;
    }

    @SuppressWarnings("unused")
    public List<String> getContent() {
        return new ArrayList<>(content);
    }

    @SuppressWarnings("unused")
    public String get(int index) {
        return content.get(index);
    }

    @SuppressWarnings("unused")
    public void add(int index, String data) {
        this.content.add(index, data);
    }

    @SuppressWarnings("unused")
    public enum PacketType {
        SERVER_HELLO_TO_BOT, SERVER_HEARTBEAT_TO_BOT, SERVER_CHAT_TO_BOT,
        SERVER_COMMAND_FEEDBACK_TO_BOT, SERVER_INFORMATION_TO_BOT,
        BOT_LIST_REQUEST_TO_SERVER, BOT_COMMAND_TO_SERVER,
        BOT_CHAT_TO_SERVER
    }
}
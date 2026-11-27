package mcagent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Carries snapshots taken on the integrated server in gameplay packet order. */
public final class ServerActionEvents {
    private ServerActionEvents() {}

    public record Payload(String event, String json) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("minecraft_agent", "player_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, Payload::event, ByteBufCodecs.STRING_UTF8, Payload::json, Payload::new);

        @Override
        public Type<Payload> type() {
            return TYPE;
        }

        public JsonObject data() {
            return JsonParser.parseString(json).getAsJsonObject();
        }
    }

    public static boolean isLocalPlayer(ServerPlayer player) {
        if (!(player.level().getServer() instanceof IntegratedServer server) || server.isPublished()) return false;
        var owner = server.getSingleplayerProfile();
        return owner != null && server.getPlayerList().getPlayer(owner.id()) == player
            && player.connection.isAcceptingMessages();
    }

    public static void send(ServerPlayer player, String event, JsonObject data) {
        player.connection.send(new ClientboundCustomPayloadPacket(new Payload(event, data.toString())));
    }

    public static JsonObject blockPosition(Level level, BlockPos pos) {
        var data = new JsonObject();
        data.addProperty("dimension", level.dimension().identifier().toString());
        var position = new JsonObject();
        position.addProperty("x", pos.getX());
        position.addProperty("y", pos.getY());
        position.addProperty("z", pos.getZ());
        data.add("position", position);
        return data;
    }

    public static JsonObject blockState(BlockState state) {
        var data = new JsonObject();
        data.addProperty("id", blockId(state));
        state.getValues().forEach(value -> addProperty(data, value));
        return data;
    }

    public static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    public static String itemId(ItemStack stack) {
        return stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static <T extends Comparable<T>> void addProperty(JsonObject data, Property.Value<T> propertyValue) {
        var property = propertyValue.property();
        var name = property.getName();
        var value = propertyValue.value();
        switch (value) {
            case Boolean bool -> data.addProperty(name, bool);
            case Number number -> data.addProperty(name, number);
            default -> data.addProperty(name, property.getName(value));
        }
    }
}

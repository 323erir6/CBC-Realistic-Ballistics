package dev.cbcrealisticballistics;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public record NuclearVisualPayload(
        UUID projectileId,
        double x,
        double y,
        double z,
        double velocityX,
        double velocityY,
        double velocityZ,
        int profile,
        boolean active
) implements CustomPacketPayload {
    public static final Type<NuclearVisualPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    CBCRealisticBallistics.MOD_ID, "nuclear_visual"
            )
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, NuclearVisualPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeUUID(payload.projectileId);
                        buf.writeDouble(payload.x);
                        buf.writeDouble(payload.y);
                        buf.writeDouble(payload.z);
                        buf.writeDouble(payload.velocityX);
                        buf.writeDouble(payload.velocityY);
                        buf.writeDouble(payload.velocityZ);
                        buf.writeVarInt(payload.profile);
                        buf.writeBoolean(payload.active);
                    },
                    buf -> new NuclearVisualPayload(
                            buf.readUUID(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readVarInt(),
                            buf.readBoolean()
                    )
            );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package dev.cbcrealisticballistics;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

final class NuclearVisualNetwork {
    private NuclearVisualNetwork() {
    }

    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(
                        NuclearVisualPayload.TYPE,
                        NuclearVisualPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(
                                () -> NuclearVisualClient.accept(payload)
                        )
                );
    }
}

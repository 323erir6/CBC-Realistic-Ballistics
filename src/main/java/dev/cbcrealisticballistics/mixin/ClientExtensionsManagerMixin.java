package dev.cbcrealisticballistics.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;

/**
 * Keeps NeoForge's duplicate-registration check, but treats the same client
 * extension implementation being installed twice as an idempotent operation.
 *
 * <p>Azimuth 1.4.7 can flush Registrate's queued listeners twice when mod
 * constructors race. Create then installs two fresh instances of the same
 * extension class and NeoForge aborts startup. Different implementations for
 * the same target remain an error.</p>
 */
@Mixin(
        targets = "net.neoforged.neoforge.client.extensions.common.ClientExtensionsManager",
        remap = false
)
public abstract class ClientExtensionsManagerMixin {
    @Redirect(
            method = "register",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
            ),
            remap = false
    )
    private static Object cbcRealisticBallistics$ignoreEquivalentDuplicate(
            Map<Object, Object> extensions,
            Object target,
            Object replacement
    ) {
        Object previous = extensions.put(target, replacement);
        if (previous != null && previous.getClass() == replacement.getClass()) {
            return null;
        }
        return previous;
    }
}

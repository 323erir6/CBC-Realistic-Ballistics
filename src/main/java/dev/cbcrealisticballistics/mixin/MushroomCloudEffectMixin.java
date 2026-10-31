package dev.cbcrealisticballistics.mixin;

import com.example.cbcenhancedshells.CBCESClientConfig;
import com.example.cbcenhancedshells.CBCESSoundEvents;
import com.example.cbcenhancedshells.NukeFxProfile;
import com.example.cbcenhancedshells.client.fx.FxContext;
import com.example.cbcenhancedshells.client.fx.FxGeom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.example.cbcenhancedshells.client.fx.MushroomCloudEffect", remap = false)
public abstract class MushroomCloudEffectMixin {
    private static final float SOUND_BLOCKS_PER_TICK = 343.0f / 20.0f;
    private static final int MAX_SOUND_DELAY_TICKS =
            (int) Math.ceil((1024.0 * 16.0) / SOUND_BLOCKS_PER_TICK);

    @Shadow @Final private NukeFxProfile profile;
    @Shadow @Final private float ox;
    @Shadow @Final private float oy;
    @Shadow @Final private float oz;
    @Shadow @Final private float gy;
    @Shadow @Final private float playerDist;
    @Shadow private float t;
    @Shadow private float fade;
    @Shadow private float growth;
    @Shadow private float cx;
    @Shadow private float cz;
    @Shadow private float Rm;
    @Shadow private float rt;

    @ModifyConstant(method = "<init>", constant = @Constant(doubleValue = 12.0))
    private double cbcRealisticBallistics$useRealSoundSpeed(double original) {
        return SOUND_BLOCKS_PER_TICK;
    }

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/Mth;clamp(III)I"
            ),
            index = 2
    )
    private int cbcRealisticBallistics$allowLongSoundDelay(int original) {
        return MAX_SOUND_DELAY_TICKS;
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/example/cbcenhancedshells/CBCESClientConfig;renderDistance()I"
            )
    )
    private int cbcRealisticBallistics$extendCloudRenderDistance() {
        return Math.max(CBCESClientConfig.renderDistance(), 1024 * 16);
    }

    @Inject(method = "renderCloudShadow", at = @At("HEAD"), cancellable = true)
    private void cbcRealisticBallistics$centerCloudShadow(
            FxContext context,
            CallbackInfo callback
    ) {
        callback.cancel();
        if (!CBCESClientConfig.cloudShadow()
                || context.sunY < 0.25f
                || context.skyLight < 0.3f) {
            return;
        }
        float alpha = 0.42f * context.skyLight * this.fade
                * Math.min(1.0f, this.t / 20.0f)
                * (0.45f + 0.55f * this.growth);
        if (alpha < 0.01f) {
            return;
        }
        float radius = this.profile.dome
                ? this.Rm
                : (this.Rm + this.rt) * 0.95f;
        FxGeom.discFlat(
                context.translucent(), context.matrix,
                this.cx, this.gy + 0.45f, this.cz,
                radius,
                0.04f, 0.04f, 0.07f,
                alpha, alpha * 0.15f, 48
        );
    }

    @Inject(method = "playSound", at = @At("HEAD"), cancellable = true)
    private void cbcRealisticBallistics$playAudibleLongRangeSound(
            Minecraft minecraft,
            float volumeMultiplier,
            float pitchMultiplier,
            CallbackInfo callback
    ) {
        float fixedRange = this.profile.hBombSound ? 512.0f : 256.0f;
        if (this.playerDist <= fixedRange) {
            return;
        }
        callback.cancel();
        if (minecraft.level == null
                || !CBCESClientConfig.explosionSounds()
                || this.playerDist <= 0.0f) {
            return;
        }
        SoundEvent sound = this.profile.hBombSound
                ? CBCESSoundEvents.H_BOMB_EXPLOSION.get()
                : CBCESSoundEvents.NUKE_EXPLOSION.get();
        float distanceFade = Mth.clamp(
                1.0f - (float) (Math.log10(this.playerDist / fixedRange) * 0.38),
                0.28f, 1.0f
        );
        float volume = (this.profile.hBombSound ? 3.5f : 2.8f)
                * volumeMultiplier
                * distanceFade
                * CBCESClientConfig.soundVolume();
        float pitch = (this.profile.hBombSound ? 0.4f : 0.6f)
                * pitchMultiplier;
        SimpleSoundInstance instance = new SimpleSoundInstance(
                sound.getLocation(),
                SoundSource.BLOCKS,
                volume,
                pitch,
                SoundInstance.createUnseededRandom(),
                false,
                0,
                SoundInstance.Attenuation.NONE,
                this.ox,
                this.oy,
                this.oz,
                false
        );
        minecraft.getSoundManager().play(instance);
    }
}

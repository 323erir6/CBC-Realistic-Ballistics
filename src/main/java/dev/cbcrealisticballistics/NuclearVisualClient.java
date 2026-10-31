package dev.cbcrealisticballistics;

import com.example.cbcenhancedshells.NukeFxProfile;
import com.example.cbcenhancedshells.client.fx.ClientFx;
import com.example.cbcenhancedshells.client.fx.FxContext;
import com.example.cbcenhancedshells.client.fx.FxEffect;
import com.example.cbcenhancedshells.client.fx.FxGeom;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class NuclearVisualClient {
    private static final Map<UUID, RemoteTracer> TRACERS = new HashMap<>();

    private NuclearVisualClient() {
    }

    static void accept(NuclearVisualPayload payload) {
        RemoteTracer existing = TRACERS.get(payload.projectileId());
        if (!payload.active()) {
            if (existing != null) {
                existing.stop();
            }
            TRACERS.remove(payload.projectileId());
            return;
        }
        if (existing == null || existing.stopped) {
            RemoteTracer tracer = new RemoteTracer(payload);
            TRACERS.put(payload.projectileId(), tracer);
            ClientFx.add(tracer);
        } else {
            existing.update(payload);
        }
    }

    private static final class RemoteTracer extends FxEffect {
        private static final int TRAIL_LENGTH = 48;
        private final UUID id;
        private final boolean hydrogenBomb;
        private final float[] trailX = new float[TRAIL_LENGTH];
        private final float[] trailY = new float[TRAIL_LENGTH];
        private final float[] trailZ = new float[TRAIL_LENGTH];
        private Vec3 previous;
        private Vec3 target;
        private Vec3 velocity;
        private int head;
        private int count;
        private int staleTicks;
        private boolean stopped;

        RemoteTracer(NuclearVisualPayload payload) {
            super(payload.x(), payload.y(), payload.z(), 24000,
                    payload.projectileId().getLeastSignificantBits());
            this.id = payload.projectileId();
            this.previous = new Vec3(payload.x(), payload.y(), payload.z());
            this.target = this.previous;
            this.velocity = new Vec3(
                    payload.velocityX(), payload.velocityY(), payload.velocityZ()
            );
            this.hydrogenBomb = NukeFxProfile.byOrdinal(payload.profile()).hBombSound;
        }

        void update(NuclearVisualPayload payload) {
            this.previous = position();
            this.target = new Vec3(payload.x(), payload.y(), payload.z());
            this.velocity = new Vec3(
                    payload.velocityX(), payload.velocityY(), payload.velocityZ()
            );
            this.staleTicks = 0;
        }

        void stop() {
            this.stopped = true;
        }

        @Override
        public boolean tick() {
            ++this.age;
            if (this.stopped || ++this.staleTicks > 12) {
                TRACERS.remove(this.id, this);
                return false;
            }
            Vec3 point = position();
            this.trailX[this.head] = (float) point.x;
            this.trailY[this.head] = (float) point.y;
            this.trailZ[this.head] = (float) point.z;
            this.head = (this.head + 1) % TRAIL_LENGTH;
            if (this.count < TRAIL_LENGTH) {
                ++this.count;
            }
            return true;
        }

        private Vec3 position() {
            double blend = Math.min(1.0, this.staleTicks * 0.8);
            Vec3 interpolated = this.previous.lerp(this.target, blend);
            if (blend >= 1.0 && this.staleTicks > 1) {
                return interpolated.add(this.velocity.scale(this.staleTicks - 1));
            }
            return interpolated;
        }

        @Override
        public void render(FxContext context) {
            Vec3 point = position();
            float px = (float) point.x;
            float py = (float) point.y + 0.5f;
            float pz = (float) point.z;
            float distance = Mth.sqrt(context.distSq(px, py, pz));
            if (distance < 900.0f || distance > NuclearVisualTracker.VISUAL_RANGE) {
                return;
            }

            float time = t(context);
            float apparentScale = Math.min(140.0f, 1.0f + distance / 120.0f);
            float blink = 0.35f + 0.65f * Mth.clamp(
                    Mth.sin(time * 0.9f) * 3.0f, 0.0f, 1.0f
            );
            float green = this.hydrogenBomb ? 0.1f : 0.35f;
            VertexConsumer glow = context.glow();
            FxGeom.billboardTex(
                    glow, context, px, py, pz,
                    (0.9f + 0.5f * blink) * apparentScale,
                    0.0f, 1.0f, green, 0.05f,
                    0.95f * blink, 0.25f, 0.5f
            );
            FxGeom.billboardTex(
                    glow, context, px, py, pz,
                    4.5f * apparentScale,
                    0.0f, 1.0f, green + 0.2f, 0.15f,
                    0.22f + 0.18f * blink, 0.0f, 0.25f
            );

            if (this.count < 2) {
                return;
            }
            VertexConsumer additive = context.additive();
            float lastX = px;
            float lastY = py;
            float lastZ = pz;
            for (int i = 0; i < this.count; ++i) {
                int index = (this.head - 1 - i + TRAIL_LENGTH * 2) % TRAIL_LENGTH;
                float fraction = (float) i / this.count;
                float currentX = this.trailX[index];
                float currentY = this.trailY[index] + 0.5f;
                float currentZ = this.trailZ[index];
                float halfWidth = (0.28f + 0.15f * apparentScale)
                        * (1.0f - fraction);
                float alpha = 0.75f * (1.0f - fraction) * (1.0f - fraction);
                if (alpha > 0.01f) {
                    FxGeom.ribbon(
                            additive, context,
                            lastX, lastY, lastZ,
                            currentX, currentY, currentZ,
                            halfWidth, halfWidth * 0.85f,
                            1.0f, this.hydrogenBomb ? 0.5f : 0.75f, 0.3f,
                            alpha, alpha * 0.85f
                    );
                }
                lastX = currentX;
                lastY = currentY;
                lastZ = currentZ;
            }
        }
    }
}

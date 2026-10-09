package com.nnpg.dfizaddon.modules.main;

import com.nnpg.dfizaddon.DFizAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.meteor.MouseScrollEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.ChunkOcclusionEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.InputUtil;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

public class DFizFreecam extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Move freely.")
        .defaultValue(0.8)
        .min(0.05)
        .max(20.0)
        .sliderRange(0.05, 20.0)
        .build()
    );

    private final Setting<Boolean> staySneaking = sgGeneral.add(new BoolSetting.Builder()
        .name("stay-sneaking")
        .description("If you were already sneaking when you enabled freecam, keeps you sneaking so you stay on the ground and mine underwater faster.")
        .defaultValue(true)
        .build()
    );

    public final Vector3d currentPosition = new Vector3d();
    public final Vector3d previousPosition = new Vector3d();

    public float yaw;
    public float pitch;
    public float previousYaw;
    public float previousPitch;

    private Perspective currentPerspective;
    private double savedFovEffect;
    private boolean savedBobView;

    private boolean isMovingForward;
    private boolean isMovingBackward;
    private boolean isMovingRight;
    private boolean isMovingLeft;
    private boolean isMovingUp;
    private boolean isMovingDown;

    private boolean sneakOnEnable;

    private long lastFrameTime;

    public DFizFreecam() {
        super(DFizAddon.CATEGORY, "dfiz-freecam", "Move freely.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null || mc.options == null) {
            toggle();
            return;
        }

        savedFovEffect = mc.options.getFovEffectScale().getValue();
        savedBobView = mc.options.getBobView().getValue();
        mc.options.getFovEffectScale().setValue(0.0);
        mc.options.getBobView().setValue(false);

        yaw = mc.player.getYaw();
        pitch = mc.player.getPitch();
        currentPerspective = mc.options.getPerspective();

        Vec3d eyePos = mc.player.getEyePos();
        currentPosition.set(eyePos.x, eyePos.y, eyePos.z);
        previousPosition.set(eyePos.x, eyePos.y, eyePos.z);

        mc.player.setVelocity(0.0, 0.0, 0.0);

        if (currentPerspective == Perspective.THIRD_PERSON_FRONT) {
            yaw += 180.0f;
            pitch *= -1.0f;
        }

        mc.options.setPerspective(Perspective.FIRST_PERSON);

        previousYaw = yaw;
        previousPitch = pitch;

        isMovingForward = mc.options.forwardKey.isPressed();
        isMovingBackward = mc.options.backKey.isPressed();
        isMovingRight = mc.options.rightKey.isPressed();
        isMovingLeft = mc.options.leftKey.isPressed();
        isMovingUp = mc.options.jumpKey.isPressed();
        isMovingDown = mc.options.sneakKey.isPressed();

        sneakOnEnable = mc.player.isSneaking();

        lastFrameTime = System.currentTimeMillis();
        resetMovementKeys();

        if (mc.worldRenderer != null) mc.worldRenderer.reload();
    }

    @Override
    public void onDeactivate() {
        restoreView();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        restoreView();
        toggle();
    }

    @EventHandler
    private void onRespawn(PacketEvent.Receive event) {
        if (!(event.packet instanceof PlayerRespawnS2CPacket)) return;

        restoreView();
        toggle();
    }

    private void restoreView() {
        resetMovementKeys();

        sneakOnEnable = false;

        previousPosition.set(currentPosition);
        previousYaw = yaw;
        previousPitch = pitch;

        if (mc.worldRenderer != null) mc.execute(mc.worldRenderer::reload);

        if (mc.options == null) return;

        mc.options.getFovEffectScale().setValue(savedFovEffect);
        mc.options.getBobView().setValue(savedBobView);

        mc.options.setPerspective(currentPerspective != null ? currentPerspective : Perspective.FIRST_PERSON);
    }

    @EventHandler
    private void onChunkOcclusion(ChunkOcclusionEvent event) {
        event.cancel();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.options == null) return;
        resetMovementKeys();

        if (staySneaking.get() && sneakOnEnable) mc.options.sneakKey.setPressed(true);

        if (mc.options.getPerspective() != Perspective.FIRST_PERSON) mc.options.setPerspective(Perspective.FIRST_PERSON);
    }

    public void onGameRender() {
        if (mc.options == null) return;

        pollMovementKeys();

        previousPosition.set(currentPosition);
        previousYaw = yaw;
        previousPitch = pitch;

        long currentTime = System.currentTimeMillis();
        float deltaTime = (currentTime - lastFrameTime) / 1000.0f;
        lastFrameTime = currentTime;

        if (deltaTime > 0.1f) deltaTime = 0.1f;
        if (deltaTime < 0.001f) deltaTime = 0.016f;

        Vec3d forward = Vec3d.fromPolar(0.0f, yaw);
        Vec3d right = Vec3d.fromPolar(0.0f, yaw + 90.0f);

        double moveX = 0, moveY = 0, moveZ = 0;
        double moveSpeed = speed.get() * 2 * (isBoundDown(mc.options.sprintKey) ? 2.0 : 1.0);

        if (isMovingForward) {
            moveX += forward.x * moveSpeed;
            moveZ += forward.z * moveSpeed;
        }
        if (isMovingBackward) {
            moveX -= forward.x * moveSpeed;
            moveZ -= forward.z * moveSpeed;
        }
        if (isMovingRight) {
            moveX += right.x * moveSpeed;
            moveZ += right.z * moveSpeed;
        }
        if (isMovingLeft) {
            moveX -= right.x * moveSpeed;
            moveZ -= right.z * moveSpeed;
        }
        if (isMovingUp) moveY += moveSpeed;
        if (isMovingDown) moveY -= moveSpeed;

        currentPosition.x += moveX * deltaTime * 5.0;
        currentPosition.y += moveY * deltaTime * 5.0;
        currentPosition.z += moveZ * deltaTime * 5.0;
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onMouseScroll(MouseScrollEvent event) {
        if (event.value == 0 || mc.currentScreen != null) return;

        adjustSpeed(event.value > 0 ? 1 : -1);
        event.cancel();
    }

    public void adjustSpeed(int dir) {
        double step = Math.max(0.25, speed.get() * 0.15);
        speed.set(Math.max(0.05, Math.min(20.0, speed.get() + dir * step)));
    }

    public void updateRotation(double deltaYaw, double deltaPitch) {
        yaw += (float) deltaYaw;
        pitch += (float) deltaPitch;

        yaw = MathHelper.wrapDegrees(yaw);
        pitch = MathHelper.clamp(pitch, -90.0f, 90.0f);
    }

    public double getInterpolatedX(float partialTicks) {
        return currentPosition.x;
    }

    public double getInterpolatedY(float partialTicks) {
        return currentPosition.y;
    }

    public double getInterpolatedZ(float partialTicks) {
        return currentPosition.z;
    }

    public double getInterpolatedYaw(float partialTicks) {
        return yaw;
    }

    public double getInterpolatedPitch(float partialTicks) {
        return pitch;
    }

    private void resetMovementKeys() {
        if (mc.options == null) return;

        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
    }

    private void pollMovementKeys() {
        boolean active = mc.currentScreen == null && !isKeyPressed(GLFW.GLFW_KEY_F3);

        isMovingForward = active && isBoundDown(mc.options.forwardKey);
        isMovingBackward = active && isBoundDown(mc.options.backKey);
        isMovingRight = active && isBoundDown(mc.options.rightKey);
        isMovingLeft = active && isBoundDown(mc.options.leftKey);
        isMovingUp = active && isBoundDown(mc.options.jumpKey);
        isMovingDown = active && isBoundDown(mc.options.sneakKey);
    }

    private boolean isBoundDown(KeyBinding bind) {
        if (bind == null || mc.getWindow() == null) return false;

        try {
            InputUtil.Key key = InputUtil.fromTranslationKey(bind.getBoundKeyTranslationKey());
            if (key == null || key.equals(InputUtil.UNKNOWN_KEY)) return false;

            long window = mc.getWindow().getHandle();
            if (key.getCategory() == InputUtil.Type.MOUSE) {
                return GLFW.glfwGetMouseButton(window, key.getCode()) == GLFW.GLFW_PRESS;
            }
            return GLFW.glfwGetKey(window, key.getCode()) == GLFW.GLFW_PRESS;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isKeyPressed(int key) {
        return mc.getWindow() != null && GLFW.glfwGetKey(mc.getWindow().getHandle(), key) == GLFW.GLFW_PRESS;
    }
}

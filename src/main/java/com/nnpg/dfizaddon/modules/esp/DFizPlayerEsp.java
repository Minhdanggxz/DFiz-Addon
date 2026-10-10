package com.nnpg.dfizaddon.modules.esp;

import com.nnpg.dfizaddon.DFizAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class DFizPlayerEsp extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<SettingColor> fillColor = sgGeneral.add(new ColorSetting.Builder()
        .name("fill-color")
        .description("Color of the box drawn on other players.")
        .defaultValue(new SettingColor(35, 151, 255, 100))
        .build()
    );

    private final Setting<Boolean> tracers = sgGeneral.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line from you to every player.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> tracerColor = sgGeneral.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Color of the tracer lines.")
        .defaultValue(new SettingColor(65, 185, 255, 255))
        .visible(tracers::get)
        .build()
    );

    public DFizPlayerEsp() {
        super(DFizAddon.CATEGORY, "dfiz-player-esp", "Highlights other players through walls.");
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        Vec3d cam = mc.gameRenderer.getCamera().getPos();
        Vec3d look = Vec3d.fromPolar(mc.gameRenderer.getCamera().getPitch(), mc.gameRenderer.getCamera().getYaw());
        Vec3d tracerStart = cam.add(look.multiply(0.12));

        SettingColor fill = fillColor.get();
        SettingColor line = tracerColor.get();

        for (AbstractClientPlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isRemoved() || player.isSpectator()) continue;

            double dx = MathHelper.lerp(event.tickDelta, player.lastRenderX, player.getX()) - player.getX();
            double dy = MathHelper.lerp(event.tickDelta, player.lastRenderY, player.getY()) - player.getY();
            double dz = MathHelper.lerp(event.tickDelta, player.lastRenderZ, player.getZ()) - player.getZ();
            Box worldBox = player.getBoundingBox().offset(dx, dy, dz);

            double dist = worldBox.getCenter().distanceTo(cam);
            double grow = Math.max(0.0, Math.min(0.12, (dist - 20.0) * 0.0018));
            Box box = worldBox.expand(grow, 0.015, grow);

            event.renderer.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, fill, fill, ShapeMode.Sides, 0);

            if (tracers.get()) {
                Vec3d center = worldBox.getCenter();
                event.renderer.line(tracerStart.x, tracerStart.y, tracerStart.z, center.x, center.y, center.z, line);
            }
        }
    }
}

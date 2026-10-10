package com.nnpg.dfizaddon.modules.esp;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nnpg.dfizaddon.DFizAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SpawnerBeam extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> maxScanRadius = sgGeneral.add(new IntSetting.Builder()
        .name("max-scan-radius")
        .description("Scan radius in chunks. It can't go above your render distance.")
        .defaultValue(16)
        .min(1)
        .sliderRange(1, 32)
        .build()
    );

    private final Setting<Integer> scanInterval = sgGeneral.add(new IntSetting.Builder()
        .name("scan-interval")
        .description("Ticks between scans (20 ticks = 1 second).")
        .defaultValue(60)
        .min(1)
        .sliderRange(5, 200)
        .build()
    );

    private final Setting<Boolean> chatAlerts = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-alerts")
        .description("Send a chat message for each new spawner found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxChatPerScan = sgGeneral.add(new IntSetting.Builder()
        .name("max-chat-per-scan")
        .description("Maximum new spawner messages per scan (avoids spam).")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 20)
        .visible(chatAlerts::get)
        .build()
    );

    private final Setting<SettingColor> beamColor = sgRender.add(new ColorSetting.Builder()
        .name("beam-color")
        .description("Color of the beam.")
        .defaultValue(new SettingColor(0, 255, 60, 150))
        .build()
    );

    private final Setting<Double> beamHalfWidth = sgRender.add(new DoubleSetting.Builder()
        .name("beam-half-width")
        .description("Half of the beam thickness, in blocks.")
        .defaultValue(0.2)
        .min(0.05)
        .sliderRange(0.05, 1.0)
        .build()
    );

    private List<BlockPos> spawners = new ArrayList<>();
    private final Set<Long> alerted = new HashSet<>();
    private int tickCounter = 0;

    public SpawnerBeam() {
        super(DFizAddon.CATEGORY, "spawner-beam", "Draws a beam through walls to every spawner nearby.");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void reset() {
        spawners = new ArrayList<>();
        alerted.clear();
        tickCounter = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) {
            reset();
            return;
        }
        if (tickCounter-- > 0) return;
        tickCounter = scanInterval.get();
        scan();
    }

    private void scan() {
        ClientWorld world = mc.world;
        int radius = Math.min(mc.options.getClampedViewDistance(), maxScanRadius.get());
        ChunkPos center = mc.player.getChunkPos();
        List<BlockPos> found = new ArrayList<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(center.x + dx, center.z + dz);
                if (chunk == null) continue;
                for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
                    if (be instanceof MobSpawnerBlockEntity) {
                        found.add(be.getPos().toImmutable());
                    }
                }
            }
        }
        spawners = found;

        int sent = 0, skipped = 0;
        for (BlockPos p : found) {
            if (!alerted.add(p.asLong())) continue;
            if (!chatAlerts.get()) continue;
            if (sent < maxChatPerScan.get()) {
                info("Spawner tai X=%d Y=%d Z=%d", p.getX(), p.getY(), p.getZ());
                sent++;
            } else {
                skipped++;
            }
        }
        if (skipped > 0) {
            info("...va %d spawner khac", skipped);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        List<BlockPos> list = spawners;
        if (list.isEmpty() || mc.world == null) return;

        MatrixStack matrices = event.matrices;
        if (matrices == null) return;

        Vec3d cam = mc.gameRenderer.getCamera().getPos();
        Matrix4f m = matrices.peek().getPositionMatrix();
        float top = mc.world.getBottomY() + mc.world.getHeight();
        float half = beamHalfWidth.get().floatValue();
        SettingColor c = beamColor.get();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        for (BlockPos p : list) {
            float x0 = (float) (p.getX() + 0.5 - half - cam.x);
            float x1 = (float) (p.getX() + 0.5 + half - cam.x);
            float z0 = (float) (p.getZ() + 0.5 - half - cam.z);
            float z1 = (float) (p.getZ() + 0.5 + half - cam.z);
            float y0 = (float) (p.getY() - cam.y);
            float y1 = (float) (top - cam.y);

            quad(buf, m, c, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1);
            quad(buf, m, c, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
            quad(buf, m, c, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
            quad(buf, m, c, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
            quad(buf, m, c, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
        }

        BufferRenderer.drawWithGlobalProgram(buf.end());

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void quad(BufferBuilder b, Matrix4f m, SettingColor c,
                             float ax, float ay, float az,
                             float bx, float by, float bz,
                             float cx, float cy, float cz,
                             float dx, float dy, float dz) {
        b.vertex(m, ax, ay, az).color(c.r, c.g, c.b, c.a);
        b.vertex(m, bx, by, bz).color(c.r, c.g, c.b, c.a);
        b.vertex(m, cx, cy, cz).color(c.r, c.g, c.b, c.a);
        b.vertex(m, dx, dy, dz).color(c.r, c.g, c.b, c.a);
    }
}

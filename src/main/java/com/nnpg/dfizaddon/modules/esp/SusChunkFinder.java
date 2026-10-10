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
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class SusChunkFinder extends Module {
    private static final int SCAN_RADIUS = 8;
    private static final int GLOW_LIGHT = 4;
    private static final int NATURAL_MAX_LIGHT = 5;
    private static final int MIN_Y = -58;
    private static final int MAX_Y = 50;
    private static final int GEODE_MIN_BLOCKS = 30;
    private static final int MIN_SHELL_PER_CHUNK = 3;
    private static final int THRESHOLD = 13;
    private static final boolean COUNT_LARGE_BUDS = false;
    private static final int SCAN_INTERVAL_TICKS = 40;
    private static final long DEBUG_INTERVAL_MS = 5000;
    private static final float HALF_WIDTH = 0.2f;
    private static final long RGB_CYCLE_MS = 3000L;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> simulationDistance = sgGeneral.add(new IntSetting.Builder()
        .name("simulation-distance")
        .description("Scan radius, multiplied by 2 (4 = 8 chunks). It can't go above your render distance.")
        .defaultValue(4)
        .range(1, 16)
        .sliderRange(1, 16)
        .build()
    );

    private final Setting<Integer> sensitivity = sgGeneral.add(new IntSetting.Builder()
        .name("sensitivity")
        .description("Glowing cells needed to count a geode as sus, multiplied by 10 (4 = 40 cells). Higher = stricter.")
        .defaultValue(4)
        .range(1, 20)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> showPlainGeodes = sgGeneral.add(new BoolSetting.Builder()
        .name("show-plain-geodes")
        .description("Also mark normal geodes (white beam).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> alertChat = sgGeneral.add(new BoolSetting.Builder()
        .name("alert-chat")
        .description("Send a chat message when a sus chunk is found.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print scan debug info in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showBeam = sgRender.add(new BoolSetting.Builder()
        .name("show-beam")
        .description("Draw a vertical beam above the geode.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> strongColor = sgRender.add(new ColorSetting.Builder()
        .name("sus-beam-color")
        .description("Beam color for sus chunks.")
        .defaultValue(new SettingColor(200, 80, 255, 150))
        .visible(showBeam::get)
        .build()
    );

    private final Setting<SettingColor> plainColor = sgRender.add(new ColorSetting.Builder()
        .name("plain-beam-color")
        .description("Beam color for plain geodes.")
        .defaultValue(new SettingColor(255, 255, 255, 200))
        .visible(() -> showBeam.get() && showPlainGeodes.get())
        .build()
    );

    private final Setting<Boolean> showStar = sgRender.add(new BoolSetting.Builder()
        .name("show-star")
        .description("Draw a star in the middle of the chunk.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> starSize = sgRender.add(new DoubleSetting.Builder()
        .name("star-size")
        .description("Outer radius of the star.")
        .defaultValue(5.5)
        .min(1.0)
        .sliderRange(1.0, 8.0)
        .visible(showStar::get)
        .build()
    );

    private final Setting<Boolean> starRgb = sgRender.add(new BoolSetting.Builder()
        .name("star-rgb")
        .description("Rainbow star.")
        .defaultValue(true)
        .visible(showStar::get)
        .build()
    );

    private final Setting<SettingColor> starColor = sgRender.add(new ColorSetting.Builder()
        .name("star-color")
        .description("Star color when rainbow is off.")
        .defaultValue(new SettingColor(255, 230, 0, 230))
        .visible(() -> showStar.get() && !starRgb.get())
        .build()
    );

    private final Setting<Boolean> showChunkPlane = sgRender.add(new BoolSetting.Builder()
        .name("show-chunk-plane")
        .description("Draw a flat plane over the chunk at ground level.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> planeOffset = sgRender.add(new DoubleSetting.Builder()
        .name("plane-offset")
        .description("Height of the plane above the surface.")
        .defaultValue(1.0)
        .sliderRange(-5.0, 20.0)
        .visible(showChunkPlane::get)
        .build()
    );

    private final Setting<SettingColor> planeColor = sgRender.add(new ColorSetting.Builder()
        .name("plane-color")
        .description("Color of the chunk plane (alpha is used for the edge, fill is more transparent).")
        .defaultValue(new SettingColor(0, 255, 255, 230))
        .visible(showChunkPlane::get)
        .build()
    );

    private final Setting<Double> chunkSize = sgRender.add(new DoubleSetting.Builder()
        .name("chunk-size")
        .description("Size of the chunk plane. 1 = the whole chunk, 0.5 = half, 2 = twice as big.")
        .defaultValue(1.0)
        .min(0.1)
        .sliderRange(0.1, 4.0)
        .visible(showChunkPlane::get)
        .build()
    );

    private final Setting<Integer> chunkAlpha = sgRender.add(new IntSetting.Builder()
        .name("chunk-alpha")
        .description("Opacity of the chunk plane fill.")
        .defaultValue(70)
        .range(0, 255)
        .sliderRange(0, 255)
        .visible(showChunkPlane::get)
        .build()
    );

    private static final Predicate<BlockState> GROWN = s ->
        s.isOf(Blocks.AMETHYST_CLUSTER) || (COUNT_LARGE_BUDS && s.isOf(Blocks.LARGE_AMETHYST_BUD));
    private static final Predicate<BlockState> SHELL = s ->
        s.isOf(Blocks.AMETHYST_BLOCK) || s.isOf(Blocks.BUDDING_AMETHYST);
    private static final Predicate<BlockState> ANY = GROWN.or(SHELL);

    private static final class Hit {
        final int lit, grown, x, y, z;
        final float planeY;
        final boolean strong;

        Hit(int lit, int grown, int x, int y, int z, boolean strong, float planeY) {
            this.lit = lit;
            this.grown = grown;
            this.x = x;
            this.y = y;
            this.z = z;
            this.strong = strong;
            this.planeY = planeY;
        }
    }

    private static final class ChunkData {
        final int cx, cz, shell, grown, lit, centreX, centreY, centreZ;

        ChunkData(int cx, int cz, int shell, int grown, int lit, BlockPos centre) {
            this.cx = cx;
            this.cz = cz;
            this.shell = shell;
            this.grown = grown;
            this.lit = lit;
            this.centreX = centre.getX();
            this.centreY = centre.getY();
            this.centreZ = centre.getZ();
        }
    }

    private Map<Long, Hit> hits = new HashMap<>();
    private final Set<Long> alertedStrong = new HashSet<>();
    private int tickCounter = 0;
    private long lastDebug = 0;

    public SusChunkFinder() {
        super(DFizAddon.CATEGORY, "sus-chunk-finder",
            "Finds sus chunks (geodes with lots of grown amethyst) and marks them.");
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
        hits = new HashMap<>();
        alertedStrong.clear();
        tickCounter = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) {
            reset();
            return;
        }
        if (tickCounter-- > 0) return;
        tickCounter = SCAN_INTERVAL_TICKS;
        scan();
    }

    private void scan() {
        ClientWorld world = mc.world;
        int radius = Math.min(mc.options.getClampedViewDistance(), simulationDistance.get() * 2);
        ChunkPos center = mc.player.getChunkPos();
        int bottomY = world.getBottomY();
        int glowThreshold = sensitivity.get() * 10;
        boolean dbg = debug.get();

        Map<Long, ChunkData> data = new HashMap<>();
        int[] hist = new int[16];

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = center.x + dx;
                int cz = center.z + dz;
                WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) continue;

                ChunkSection[] sections = chunk.getSectionArray();
                int grown = 0, shell = 0;
                long sumX = 0, sumY = 0, sumZ = 0;

                for (int i = 0; i < sections.length; i++) {
                    ChunkSection section = sections[i];
                    int baseY = bottomY + i * 16;
                    if (baseY + 15 < MIN_Y || baseY > MAX_Y) continue;
                    if (section == null || section.isEmpty() || !section.hasAny(ANY)) continue;

                    for (int y = 0; y < 16; y++) {
                        int worldY = baseY + y;
                        if (worldY < MIN_Y || worldY > MAX_Y) continue;
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState state = section.getBlockState(x, y, z);
                                if (GROWN.test(state)) {
                                    grown++;
                                } else if (SHELL.test(state)) {
                                    shell++;
                                    sumX += cx * 16L + x;
                                    sumY += worldY;
                                    sumZ += cz * 16L + z;
                                }
                            }
                        }
                    }
                }

                if (shell < MIN_SHELL_PER_CHUNK) continue;

                BlockPos centre = new BlockPos((int) (sumX / shell), (int) (sumY / shell), (int) (sumZ / shell));
                int lit = litCells(world, centre, hist, dbg);
                data.put(ChunkPos.toLong(cx, cz), new ChunkData(cx, cz, shell, grown, lit, centre));
            }
        }

        Map<Long, Hit> found = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        int geodeGroups = 0, bestLit = 0;
        List<String> debugGroups = new ArrayList<>();

        for (Map.Entry<Long, ChunkData> entry : data.entrySet()) {
            if (!visited.add(entry.getKey())) continue;

            ArrayDeque<ChunkData> queue = new ArrayDeque<>();
            List<Long> members = new ArrayList<>();
            queue.add(entry.getValue());

            int shell = 0, grown = 0, litMax = 0, n = 0;
            long sx = 0, sy = 0, sz = 0;

            while (!queue.isEmpty()) {
                ChunkData c = queue.poll();
                members.add(ChunkPos.toLong(c.cx, c.cz));
                shell += c.shell;
                grown += c.grown;
                if (c.lit > litMax) litMax = c.lit;
                sx += c.centreX;
                sy += c.centreY;
                sz += c.centreZ;
                n++;

                for (int ox = -1; ox <= 1; ox++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        if (ox == 0 && oz == 0) continue;
                        long k = ChunkPos.toLong(c.cx + ox, c.cz + oz);
                        ChunkData neighbour = data.get(k);
                        if (neighbour != null && visited.add(k)) queue.add(neighbour);
                    }
                }
            }

            if (shell < GEODE_MIN_BLOCKS) continue;
            geodeGroups++;
            if (litMax > bestLit) bestLit = litMax;
            debugGroups.add("[X=" + (int) (sx / n) + " Z=" + (int) (sz / n) + " l4=" + litMax + " cum=" + grown + "]");

            boolean strong = litMax > glowThreshold || grown > THRESHOLD;
            if (!strong && !showPlainGeodes.get()) continue;

            int hx = (int) (sx / n), hy = (int) (sy / n), hz = (int) (sz / n);
            Hit h = new Hit(litMax, grown, hx, hy, hz, strong, (float) (surfaceY(world, hx, hz) + planeOffset.get()));
            found.put(entry.getKey(), h);

            if (strong) {
                boolean newStrong = true;
                for (long k : members) {
                    if (alertedStrong.contains(k)) {
                        newStrong = false;
                        break;
                    }
                }
                if (newStrong) {
                    alertedStrong.addAll(members);
                    if (alertChat.get()) {
                        info("Nhieu amethyst lon tai X=" + h.x + " Y=" + h.y + " Z=" + h.z
                            + " (light 4: " + h.lit + ", thay " + h.grown + " cum)");
                    }
                }
            }
        }
        hits = found;

        if (dbg) {
            long now = System.currentTimeMillis();
            int totalShell = 0;
            for (ChunkData c : data.values()) totalShell += c.shell;
            if (totalShell > 0 && now - lastDebug > DEBUG_INTERVAL_MS) {
                lastDebug = now;
                StringBuilder groups = new StringBuilder();
                for (int i = 0; i < debugGroups.size() && i < 4; i++) groups.append(' ').append(debugGroups.get(i));
                info("debug: vo=" + totalShell + " hang=" + geodeGroups
                    + " light4 cao nhat=" + bestLit + " (nguong " + glowThreshold + ") |" + groups
                    + " | o theo muc sang: 0=" + hist[0] + " 1=" + hist[1] + " 2=" + hist[2] + " 3=" + hist[3]
                    + " 4=" + hist[4] + " 5=" + hist[5]);
            }
        }
    }

    private static int surfaceY(ClientWorld world, int x, int z) {
        int baseX = Math.floorDiv(x, 16) * 16;
        int baseZ = Math.floorDiv(z, 16) * 16;
        int best = world.getBottomY();
        for (int ox = 0; ox < 16; ox += 3) {
            for (int oz = 0; oz < 16; oz += 3) {
                int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, baseX + ox, baseZ + oz);
                if (y > best) best = y;
            }
        }
        return best;
    }

    private static int litCells(ClientWorld world, BlockPos centre, int[] hist, boolean dbg) {
        int count = 0;
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        BlockPos.Mutable neighbour = new BlockPos.Mutable();

        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dy = -SCAN_RADIUS; dy <= SCAN_RADIUS; dy++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);

                    int light = world.getLightLevel(LightType.BLOCK, cursor);
                    if (dbg) hist[Math.max(0, Math.min(light, 15))]++;
                    if (light != GLOW_LIGHT) continue;

                    boolean artificial = false;
                    for (Direction d : Direction.values()) {
                        neighbour.set(cursor.getX() + d.getOffsetX(), cursor.getY() + d.getOffsetY(), cursor.getZ() + d.getOffsetZ());
                        if (world.getLightLevel(LightType.BLOCK, neighbour) > NATURAL_MAX_LIGHT) {
                            artificial = true;
                            break;
                        }
                    }
                    if (artificial) continue;

                    count++;
                }
            }
        }
        return count;
    }

    private static int[] rgba(Color c) {
        return new int[]{c.r, c.g, c.b, c.a};
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        Map<Long, Hit> current = hits;
        if (current.isEmpty() || mc.world == null) return;

        boolean beam = showBeam.get();
        boolean plane = showChunkPlane.get();
        boolean star = showStar.get();
        if (!beam && !plane && !star) return;

        MatrixStack matrices = event.matrices;
        if (matrices == null) return;

        Vec3d cam = mc.gameRenderer.getCamera().getPos();
        Matrix4f m = matrices.peek().getPositionMatrix();
        float top = mc.world.getBottomY() + mc.world.getHeight();

        int[] strongCol = rgba(strongColor.get());
        int[] plainCol = rgba(plainColor.get());
        SettingColor pc = planeColor.get();
        int[] planeFill = new int[]{pc.r, pc.g, pc.b, chunkAlpha.get()};
        int[] planeEdge = new int[]{pc.r, pc.g, pc.b, pc.a};
        float outer = starSize.get().floatValue();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        for (Hit h : new ArrayList<>(current.values())) {
            int[] c = h.strong ? strongCol : plainCol;
            float x0 = (float) (h.x + 0.5 - HALF_WIDTH - cam.x);
            float x1 = (float) (h.x + 0.5 + HALF_WIDTH - cam.x);
            float z0 = (float) (h.z + 0.5 - HALF_WIDTH - cam.z);
            float z1 = (float) (h.z + 0.5 + HALF_WIDTH - cam.z);
            float y0 = (float) (h.y - cam.y);
            float y1 = (float) (top - cam.y);

            if (beam) {
                quad(buf, m, c, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1);
                quad(buf, m, c, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
                quad(buf, m, c, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
                quad(buf, m, c, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
                quad(buf, m, c, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
            }

            if (plane || star) {
                int chX = Math.floorDiv(h.x, 16) * 16;
                int chZ = Math.floorDiv(h.z, 16) * 16;
                float half = 8f * chunkSize.get().floatValue();
                float pcx = (float) (chX + 8 - cam.x);
                float pcz = (float) (chZ + 8 - cam.z);
                float px0 = pcx - half;
                float px1 = pcx + half;
                float pz0 = pcz - half;
                float pz1 = pcz + half;
                float py = (float) (h.planeY - cam.y);
                float t = 0.25f;

                if (plane) {
                    quad(buf, m, planeFill, px0, py, pz0, px1, py, pz0, px1, py, pz1, px0, py, pz1);
                    quad(buf, m, planeEdge, px0, py, pz0, px1, py, pz0, px1, py, pz0 + t, px0, py, pz0 + t);
                    quad(buf, m, planeEdge, px0, py, pz1 - t, px1, py, pz1 - t, px1, py, pz1, px0, py, pz1);
                    quad(buf, m, planeEdge, px0, py, pz0, px0 + t, py, pz0, px0 + t, py, pz1, px0, py, pz1);
                    quad(buf, m, planeEdge, px1 - t, py, pz0, px1, py, pz0, px1, py, pz1, px1 - t, py, pz1);
                }

                if (star) {
                    float cx = (px0 + px1) / 2f;
                    float cz = (pz0 + pz1) / 2f;
                    float sy = py + 0.05f;
                    float[] sxs = new float[10];
                    float[] szs = new float[10];
                    for (int i = 0; i < 10; i++) {
                        double ang = -Math.PI / 2 + i * Math.PI / 5;
                        float r = (i % 2 == 0) ? outer : outer * 0.42f;
                        sxs[i] = cx + (float) Math.cos(ang) * r;
                        szs[i] = cz + (float) Math.sin(ang) * r;
                    }
                    int[] starCol = starRgb.get() ? rainbow() : rgba(starColor.get());
                    for (int i = 0; i < 10; i++) {
                        int j = (i + 1) % 10;
                        quad(buf, m, starCol, cx, sy, cz, sxs[i], sy, szs[i], sxs[j], sy, szs[j], sxs[j], sy, szs[j]);
                    }
                }
            }
        }

        BufferRenderer.drawWithGlobalProgram(buf.end());

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static int[] rainbow() {
        float hue = (System.currentTimeMillis() % RGB_CYCLE_MS) / (float) RGB_CYCLE_MS;
        float h6 = hue * 6f;
        int i = (int) h6;
        float f = h6 - i;
        int up = (int) (255 * f);
        int down = (int) (255 * (1f - f));
        switch (i % 6) {
            case 0:  return new int[]{255, up, 0, 235};
            case 1:  return new int[]{down, 255, 0, 235};
            case 2:  return new int[]{0, 255, up, 235};
            case 3:  return new int[]{0, down, 255, 235};
            case 4:  return new int[]{up, 0, 255, 235};
            default: return new int[]{255, 0, down, 235};
        }
    }

    private static void quad(BufferBuilder b, Matrix4f m, int[] c,
                             float ax, float ay, float az,
                             float bx, float by, float bz,
                             float cx, float cy, float cz,
                             float dx, float dy, float dz) {
        b.vertex(m, ax, ay, az).color(c[0], c[1], c[2], c[3]);
        b.vertex(m, bx, by, bz).color(c[0], c[1], c[2], c[3]);
        b.vertex(m, cx, cy, cz).color(c[0], c[1], c[2], c[3]);
        b.vertex(m, dx, dy, dz).color(c[0], c[1], c[2], c[3]);
    }
}

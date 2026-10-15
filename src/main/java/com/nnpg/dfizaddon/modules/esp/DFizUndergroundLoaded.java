package com.nnpg.dfizaddon.modules.esp;

import com.nnpg.dfizaddon.DFizAddon;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DFizUndergroundLoaded extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
        .name("min-y")
        .description("Lowest height checked for underground blocks.")
        .defaultValue(-64)
        .range(-64, 320)
        .sliderRange(-64, 128)
        .build()
    );

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y")
        .description("Highest height checked for underground blocks.")
        .defaultValue(-50)
        .range(-64, 320)
        .sliderRange(-64, 128)
        .build()
    );

    private final Setting<List<Block>> filler = sgGeneral.add(new BlockListSetting.Builder()
        .name("filler-blocks")
        .description("Blocks the server uses to fill hidden underground. A chunk only counts if it has other blocks in range.")
        .defaultValue(Blocks.STONE, Blocks.DEEPSLATE, Blocks.BEDROCK)
        .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print the block counts of your current chunk in range to chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> scanInterval = sgGeneral.add(new IntSetting.Builder()
        .name("scan-interval")
        .description("Ticks between scans.")
        .defaultValue(20)
        .range(5, 200)
        .sliderRange(5, 100)
        .build()
    );

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Color of chunks with loaded underground.")
        .defaultValue(new SettingColor(0, 200, 255, 90))
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the chunks are drawn.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<Double> planeY = sgGeneral.add(new DoubleSetting.Builder()
        .name("plane-y")
        .description("Height of the drawn chunk plane and tracer.")
        .defaultValue(63.0)
        .sliderRange(-64.0, 320.0)
        .build()
    );

    private final Setting<Boolean> tracers = sgGeneral.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a tracer to loaded underground areas.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> nearestOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("nearest-only")
        .description("Only draw the tracer to the nearest area.")
        .defaultValue(true)
        .visible(tracers::get)
        .build()
    );

    private LongOpenHashSet loaded = new LongOpenHashSet();
    private List<double[]> areas = new ArrayList<>();
    private int timer;

    public DFizUndergroundLoaded() {
        super(DFizAddon.CATEGORY, "dfiz-underground-loaded", "Shows which chunks have underground blocks loaded.");
    }

    @Override
    public void onActivate() {
        loaded = new LongOpenHashSet();
        areas = new ArrayList<>();
        timer = Integer.MAX_VALUE - 1;
    }

    @Override
    public void onDeactivate() {
        loaded = new LongOpenHashSet();
        areas = new ArrayList<>();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) {
            loaded = new LongOpenHashSet();
            areas = new ArrayList<>();
            return;
        }
        if (++timer < scanInterval.get()) return;
        timer = 0;

        int range = mc.options.getViewDistance().getValue() + 2;
        ChunkPos center = mc.player.getChunkPos();
        int low = minY.get();
        int high = maxY.get();
        Set<Block> fillerSet = new HashSet<>(filler.get());
        LongOpenHashSet result = new LongOpenHashSet();

        if (debug.get()) printCounts(center, low, high);

        for (int x = center.x - range; x <= center.x + range; x++) {
            for (int z = center.z - range; z <= center.z + range; z++) {
                WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(x, z);
                if (chunk != null && hasUnderground(chunk, low, high, fillerSet)) result.add(ChunkPos.toLong(x, z));
            }
        }

        loaded = result;
        areas = buildAreas(result);
    }

    private static boolean hasUnderground(WorldChunk chunk, int low, int high, Set<Block> fillerSet) {
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomSectionCoord();
        for (int i = 0; i < sections.length; i++) {
            int baseY = (bottom + i) << 4;
            if (baseY + 15 < low || baseY > high) continue;
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;
            if (baseY >= low && baseY + 15 <= high) {
                if (section.hasAny(state -> !fillerSet.contains(state.getBlock()))) return true;
                continue;
            }
            int yFrom = Math.max(low, baseY) - baseY;
            int yTo = Math.min(high, baseY + 15) - baseY;
            for (int y = yFrom; y <= yTo; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (!fillerSet.contains(section.getBlockState(x, y, z).getBlock())) return true;
                    }
                }
            }
        }
        return false;
    }

    private void printCounts(ChunkPos pos, int low, int high) {
        WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(pos.x, pos.z);
        if (chunk == null) return;
        Map<Block, Integer> counts = new HashMap<>();
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomSectionCoord();
        for (int i = 0; i < sections.length; i++) {
            int baseY = (bottom + i) << 4;
            if (baseY + 15 < low || baseY > high) continue;
            ChunkSection section = sections[i];
            if (section == null) continue;
            int yFrom = Math.max(low, baseY) - baseY;
            int yTo = Math.min(high, baseY + 15) - baseY;
            for (int y = yFrom; y <= yTo; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        counts.merge(state.getBlock(), 1, Integer::sum);
                    }
                }
            }
        }
        List<Map.Entry<Block, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((e1, e2) -> e2.getValue() - e1.getValue());
        StringBuilder out = new StringBuilder("Chunk " + pos.x + " " + pos.z + " [" + low + ".." + high + "]:");
        for (int i = 0; i < Math.min(8, list.size()); i++) {
            out.append(' ').append(list.get(i).getKey().getName().getString()).append('=').append(list.get(i).getValue());
        }
        info(out.toString());
    }

    private static List<double[]> buildAreas(LongOpenHashSet set) {
        List<double[]> out = new ArrayList<>();
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        LongIterator it = set.iterator();
        while (it.hasNext()) {
            long start = it.nextLong();
            if (!seen.add(start)) continue;
            double sumX = 0;
            double sumZ = 0;
            int count = 0;
            double firstX = 0;
            double firstZ = 0;
            queue.enqueue(start);
            while (!queue.isEmpty()) {
                long current = queue.dequeueLong();
                int cx = ChunkPos.getPackedX(current);
                int cz = ChunkPos.getPackedZ(current);
                sumX += (cx << 4) + 8.0;
                sumZ += (cz << 4) + 8.0;
                if (count == 0) {
                    firstX = (cx << 4) + 8.0;
                    firstZ = (cz << 4) + 8.0;
                }
                count++;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long next = ChunkPos.toLong(cx + dx, cz + dz);
                        if (set.contains(next) && seen.add(next)) queue.enqueue(next);
                    }
                }
            }
            double mx = sumX / count;
            double mz = sumZ / count;
            if (!set.contains(ChunkPos.toLong((int) Math.floor(mx) >> 4, (int) Math.floor(mz) >> 4))) {
                mx = firstX;
                mz = firstZ;
            }
            out.add(new double[]{mx, mz});
        }
        return out;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null || loaded.isEmpty()) return;

        SettingColor c = color.get();
        Color fill = new Color(c.r, c.g, c.b, c.a);
        Color edge = new Color((int) (c.r * 0.77), (int) (c.g * 0.77), (int) (c.b * 0.77), Math.min(255, c.a * 3 / 2));
        ShapeMode mode = shapeMode.get();
        double y = planeY.get();

        LongIterator it = loaded.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            int cx = ChunkPos.getPackedX(key);
            int cz = ChunkPos.getPackedZ(key);
            double x1 = cx << 4;
            double z1 = cz << 4;
            double x2 = x1 + 16;
            double z2 = z1 + 16;

            if (mode.sides()) {
                event.renderer.quad(x1, y, z1, x2, y, z1, x2, y, z2, x1, y, z2, fill);
            }
            if (mode.lines()) {
                if (!loaded.contains(ChunkPos.toLong(cx, cz - 1))) event.renderer.line(x1, y, z1, x2, y, z1, edge);
                if (!loaded.contains(ChunkPos.toLong(cx, cz + 1))) event.renderer.line(x1, y, z2, x2, y, z2, edge);
                if (!loaded.contains(ChunkPos.toLong(cx - 1, cz))) event.renderer.line(x1, y, z1, x1, y, z2, edge);
                if (!loaded.contains(ChunkPos.toLong(cx + 1, cz))) event.renderer.line(x2, y, z1, x2, y, z2, edge);
            }
        }

        if (!tracers.get() || areas.isEmpty()) return;
        Color tracer = new Color(c.r, c.g, c.b, 255);

        if (nearestOnly.get()) {
            double px = mc.player.getX();
            double pz = mc.player.getZ();
            double[] best = null;
            double bestDist = Double.MAX_VALUE;
            for (double[] a : areas) {
                double d = (a[0] - px) * (a[0] - px) + (a[1] - pz) * (a[1] - pz);
                if (d < bestDist) {
                    bestDist = d;
                    best = a;
                }
            }
            if (best != null) {
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, best[0], y, best[1], tracer);
            }
        } else {
            for (double[] a : areas) {
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, a[0], y, a[1], tracer);
            }
        }
    }
}

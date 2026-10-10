package com.nnpg.dfizaddon.modules.esp;

import com.nnpg.dfizaddon.DFizAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DFizHoleEsp extends Module {
    private static final int MAX_CHUNKS_PER_TICK = 200;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Range in blocks around you.")
        .defaultValue(64)
        .range(16, 128)
        .sliderRange(16, 128)
        .build()
    );

    private final Setting<Integer> minDepth = sgGeneral.add(new IntSetting.Builder()
        .name("min-depth")
        .description("Minimum depth of a hole, in blocks.")
        .defaultValue(7)
        .min(1)
        .sliderRange(3, 30)
        .build()
    );

    private final Setting<Integer> fillAlpha = sgRender.add(new IntSetting.Builder()
        .name("fill-alpha")
        .description("Opacity of the hole fill.")
        .defaultValue(60)
        .range(0, 255)
        .sliderRange(0, 255)
        .build()
    );

    private final Setting<Integer> outlineAlpha = sgRender.add(new IntSetting.Builder()
        .name("outline-alpha")
        .description("Opacity of the outline.")
        .defaultValue(180)
        .range(0, 255)
        .sliderRange(0, 255)
        .build()
    );

    private final Setting<Boolean> gradientFill = sgRender.add(new BoolSetting.Builder()
        .name("gradient-fill")
        .description("Fade the fill from the bottom of the hole to the top.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showOutline = sgRender.add(new BoolSetting.Builder()
        .name("show-outline")
        .description("Draw the outline of each hole.")
        .defaultValue(true)
        .build()
    );

    private final Map<Long, TrackedChunk> chunks = new ConcurrentHashMap<>();
    private final Queue<Long> chunkQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> queuedChunks = ConcurrentHashMap.newKeySet();
    private final Set<HoleData> holes = ConcurrentHashMap.newKeySet();
    private ExecutorService executor;
    private Object currentWorldRef;

    public DFizHoleEsp() {
        super(DFizAddon.CATEGORY, "dfiz-hole-esp", "Finds deep 1x1 and 3x1 holes and highlights them.");
    }

    @Override
    public void onActivate() {
        clear();
        executor = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "dfiz-hole-esp");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void onDeactivate() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        clear();
    }

    private void clear() {
        chunks.clear();
        chunkQueue.clear();
        queuedChunks.clear();
        holes.clear();
        currentWorldRef = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || executor == null) return;

        if (currentWorldRef != mc.world) {
            currentWorldRef = mc.world;
            chunks.clear();
            chunkQueue.clear();
            queuedChunks.clear();
            holes.clear();
        }

        for (TrackedChunk tracked : chunks.values()) tracked.marked = false;

        int viewDist = Math.max(1, range.get() / 16);
        int pcx = mc.player.getChunkPos().x;
        int pcz = mc.player.getChunkPos().z;

        for (int cx = pcx - viewDist; cx <= pcx + viewDist; cx++) {
            for (int cz = pcz - viewDist; cz <= pcz + viewDist; cz++) {
                WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) continue;

                long key = ChunkPos.toLong(cx, cz);
                TrackedChunk existing = chunks.get(key);
                if (existing != null) {
                    existing.marked = true;
                } else if (queuedChunks.add(key)) {
                    chunkQueue.add(key);
                }
            }
        }

        processChunkQueue();
        chunks.entrySet().removeIf(entry -> !entry.getValue().marked);

        Set<Long> active = chunks.keySet();
        holes.removeIf(hole -> {
            int cx = (int) Math.floor(hole.box.getCenter().x) >> 4;
            int cz = (int) Math.floor(hole.box.getCenter().z) >> 4;
            return !active.contains(ChunkPos.toLong(cx, cz));
        });
    }

    private void processChunkQueue() {
        int processed = 0;

        while (!chunkQueue.isEmpty() && processed < MAX_CHUNKS_PER_TICK) {
            Long key = chunkQueue.poll();
            if (key == null) continue;

            queuedChunks.remove(key);
            int cx = ChunkPos.getPackedX(key);
            int cz = ChunkPos.getPackedZ(key);
            WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cx, cz);
            if (chunk == null) continue;

            chunks.put(key, new TrackedChunk());
            executor.execute(() -> searchChunk(chunk));
            processed++;
        }
    }

    private void searchChunk(WorldChunk chunk) {
        try {
            if (mc.world == null) return;

            int minY = mc.world.getBottomY();
            int maxY = mc.world.getBottomY() + mc.world.getHeight();
            BlockPos.Mutable pos = new BlockPos.Mutable();

            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    for (int y = minY + 1; y < maxY - 1; y++) {
                        pos.set(chunk.getPos().getStartX() + x, y, chunk.getPos().getStartZ() + z);
                        checkHole(pos);
                        checkWideHole(pos, true);
                        checkWideHole(pos, false);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void checkHole(BlockPos pos) {
        if (!isValidHoleSection(pos) || isValidHoleSection(pos.up())) return;

        BlockPos.Mutable current = pos.mutableCopy();
        while (isValidHoleSection(current)) current.move(Direction.DOWN);

        int depth = pos.getY() - current.getY();
        if (depth < minDepth.get()) return;

        addHole(new Box(pos.getX(), current.getY() + 1, pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1));
    }

    private void checkWideHole(BlockPos pos, boolean alongX) {
        if (!isValidWideSection(pos, alongX) || isValidWideSection(pos.up(), alongX)) return;

        BlockPos.Mutable current = pos.mutableCopy();
        while (isValidWideSection(current, alongX)) current.move(Direction.DOWN);

        int depth = pos.getY() - current.getY();
        if (depth < minDepth.get()) return;

        double x2 = alongX ? pos.getX() + 3 : pos.getX() + 1;
        double z2 = alongX ? pos.getZ() + 1 : pos.getZ() + 3;
        addHole(new Box(pos.getX(), current.getY() + 1, pos.getZ(), x2, pos.getY() + 1, z2));
    }

    private void addHole(Box box) {
        for (HoleData data : holes) {
            if (data.box.equals(box) || data.box.intersects(box)) return;
        }
        holes.add(new HoleData(box));
    }

    private boolean isValidHoleSection(BlockPos pos) {
        return isPassable(pos)
            && isSolidWall(pos.north())
            && isSolidWall(pos.south())
            && isSolidWall(pos.east())
            && isSolidWall(pos.west());
    }

    private boolean isValidWideSection(BlockPos pos, boolean alongX) {
        if (alongX) {
            return isPassable(pos)
                && isPassable(pos.east())
                && isPassable(pos.east(2))
                && isSolidWall(pos.north())
                && isSolidWall(pos.south())
                && isSolidWall(pos.west())
                && isSolidWall(pos.east(3));
        }

        return isPassable(pos)
            && isPassable(pos.south())
            && isPassable(pos.south(2))
            && isSolidWall(pos.east())
            && isSolidWall(pos.west())
            && isSolidWall(pos.north())
            && isSolidWall(pos.south(3));
    }

    private boolean isPassable(BlockPos pos) {
        if (mc.world == null) return false;

        BlockState state = mc.world.getBlockState(pos);
        if (!state.isAir()) return false;

        return !isPlantBlock(mc.world.getBlockState(pos.down())) && !isPlantBlock(mc.world.getBlockState(pos.up()));
    }

    private boolean isSolidWall(BlockPos pos) {
        if (mc.world == null) return false;

        BlockState state = mc.world.getBlockState(pos);
        return !state.isAir() && !isTransparentBlock(state);
    }

    private static boolean isTransparentBlock(BlockState state) {
        return state.isIn(BlockTags.LEAVES)
            || state.isOf(Blocks.GLASS)
            || state.isOf(Blocks.GLASS_PANE)
            || isPlantBlock(state)
            || state.isOf(Blocks.BAMBOO)
            || state.isOf(Blocks.BAMBOO_SAPLING)
            || state.isOf(Blocks.SHORT_GRASS)
            || state.isOf(Blocks.TALL_GRASS)
            || state.isOf(Blocks.FERN)
            || state.isOf(Blocks.LARGE_FERN)
            || state.isOf(Blocks.SUGAR_CANE)
            || state.isOf(Blocks.DEAD_BUSH)
            || state.isOf(Blocks.SWEET_BERRY_BUSH);
    }

    private static boolean isPlantBlock(BlockState state) {
        return state.isOf(Blocks.KELP)
            || state.isOf(Blocks.KELP_PLANT)
            || state.isOf(Blocks.SEAGRASS)
            || state.isOf(Blocks.TALL_SEAGRASS)
            || state.isOf(Blocks.VINE)
            || state.isOf(Blocks.CAVE_VINES)
            || state.isOf(Blocks.CAVE_VINES_PLANT)
            || state.isOf(Blocks.WEEPING_VINES)
            || state.isOf(Blocks.WEEPING_VINES_PLANT)
            || state.isOf(Blocks.TWISTING_VINES)
            || state.isOf(Blocks.TWISTING_VINES_PLANT)
            || state.isOf(Blocks.GLOW_LICHEN)
            || state.isOf(Blocks.HANGING_ROOTS)
            || state.isOf(Blocks.SPORE_BLOSSOM);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (holes.isEmpty()) return;

        int fa = MathHelper.clamp(fillAlpha.get(), 0, 255);
        int oa = MathHelper.clamp(outlineAlpha.get(), 0, 255);
        Color outline = new Color(255, 100, 0, oa);
        boolean outlineOn = showOutline.get();
        boolean gradient = gradientFill.get();

        for (HoleData hole : holes) {
            Box b = hole.box;

            if (!gradient) {
                Color fill = new Color(255, 100, 0, fa);
                event.renderer.box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, fill, outline,
                    outlineOn ? ShapeMode.Both : ShapeMode.Sides, 0);
                continue;
            }

            int slices = Math.max(1, MathHelper.ceil(b.maxY - b.minY));
            int aTop = Math.max(6, Math.round(fa * 0.18f));

            for (int i = 0; i < slices; i++) {
                double y0 = b.minY + i;
                double y1 = Math.min(b.maxY, y0 + 1);
                float t = slices == 1 ? 0f : (float) i / (slices - 1);
                int alpha = Math.round(MathHelper.lerp(t, fa, aTop));
                Color slice = new Color(255, 100, 0, alpha);
                event.renderer.box(b.minX, y0, b.minZ, b.maxX, y1, b.maxZ, slice, slice, ShapeMode.Sides, 0);
            }

            if (outlineOn) {
                event.renderer.box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, outline, outline, ShapeMode.Lines, 0);
            }
        }
    }

    private static final class HoleData {
        private final Box box;

        private HoleData(Box box) {
            this.box = box;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            return obj instanceof HoleData other && Objects.equals(box, other.box);
        }

        @Override
        public int hashCode() {
            return Objects.hash(box);
        }
    }

    private static final class TrackedChunk {
        private volatile boolean marked = true;
    }
}

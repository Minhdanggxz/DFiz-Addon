package com.nnpg.dfizaddon.modules.esp;

import com.nnpg.dfizaddon.DFizAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
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
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class DFizClusterFinder extends Module {
    private static final long QUEUE_REBUILD_INTERVAL_MS = 2000L;
    private static final long MOVE_REBUILD_INTERVAL_MS = 500L;
    private static final int MAX_SCANS_PER_TICK = 2;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> simDistance = sgGeneral.add(new IntSetting.Builder()
        .name("sim-distance")
        .description("Scan radius in chunks.")
        .defaultValue(8)
        .range(1, 32)
        .sliderRange(1, 32)
        .build()
    );

    private final Setting<Integer> minClusterSize = sgGeneral.add(new IntSetting.Builder()
        .name("min-cluster-size")
        .description("Hits needed in a chunk to flag it.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 30)
        .build()
    );

    private final Setting<Boolean> lightOnlyMode = sgGeneral.add(new BoolSetting.Builder()
        .name("light-only")
        .description("Find clusters from block light only. Use this when the server hides the amethyst blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatAlert = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-alert")
        .description("Send a chat message when a chunk is flagged.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> blockEsp = sgRender.add(new BoolSetting.Builder()
        .name("block-esp")
        .description("Highlight the hit blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chunkMark = sgRender.add(new BoolSetting.Builder()
        .name("chunk-mark")
        .description("Draw a flat plate over flagged chunks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> chunkYLevel = sgRender.add(new IntSetting.Builder()
        .name("chunk-y-level")
        .description("Height of the chunk plate.")
        .defaultValue(55)
        .range(-64, 320)
        .sliderRange(-64, 320)
        .visible(chunkMark::get)
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line to the nearest hit of each flagged chunk.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> alpha = sgRender.add(new IntSetting.Builder()
        .name("alpha")
        .description("Opacity of the plates and blocks.")
        .defaultValue(180)
        .range(0, 255)
        .sliderRange(0, 255)
        .build()
    );

    private final Set<ChunkPos> flaggedChunks = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<ChunkPos, Set<BlockPos>> chunkHits = new ConcurrentHashMap<>();
    private final Set<ChunkPos> scannedChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> queuedChunks = ConcurrentHashMap.newKeySet();
    private final Queue<ChunkPos> scanQueue = new ConcurrentLinkedQueue<>();
    private final Set<ChunkPos> dirtyChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> notifiedChunks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean scanInProgress = new AtomicBoolean(false);
    private ExecutorService executor;
    private ChunkPos lastPlayerChunk;
    private long lastQueueRebuild;

    public DFizClusterFinder() {
        super(DFizAddon.CATEGORY, "dfiz-cluster-finder", "Finds chunks with amethyst clusters (geodes) using block light.");
    }

    @Override
    public void onActivate() {
        clearState();
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "dfiz-cluster-finder");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onDeactivate() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        scanInProgress.set(false);
        clearState();
    }

    @Override
    public String getInfoString() {
        return String.valueOf(flaggedChunks.size());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        dirtyChunks.add(new ChunkPos(event.pos));
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || executor == null) {
            clearState();
            return;
        }

        ChunkPos playerChunk = mc.player.getChunkPos();
        if (lastPlayerChunk == null) {
            lastPlayerChunk = playerChunk;
            buildScanQueue(true);
        }

        updateScanQueue(playerChunk);
        drainDirtyChunks();
        tryStartScan();
    }

    private void updateScanQueue(ChunkPos playerChunk) {
        long now = System.currentTimeMillis();
        boolean moved = !playerChunk.equals(lastPlayerChunk);
        boolean timeout = now - lastQueueRebuild > QUEUE_REBUILD_INTERVAL_MS;
        boolean moveWindow = now - lastQueueRebuild > MOVE_REBUILD_INTERVAL_MS;

        if (moved) {
            lastPlayerChunk = playerChunk;
            pruneFarChunks(playerChunk, simDistance.get());
        }

        if ((moved && moveWindow) || (timeout && scanQueue.size() < 64)) {
            lastQueueRebuild = now;
            buildScanQueue(false);
        }
    }

    private void buildScanQueue(boolean reset) {
        if (reset) {
            scanQueue.clear();
            queuedChunks.clear();
            scannedChunks.clear();
        }

        ChunkPos center = mc.player.getChunkPos();
        int radius = Math.max(1, Math.min(32, simDistance.get()));

        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                enqueueIfNeeded(center.x + dx, center.z + ring);
                if (ring != 0) enqueueIfNeeded(center.x + dx, center.z - ring);
            }

            for (int dz = -ring + 1; dz <= ring - 1; dz++) {
                enqueueIfNeeded(center.x + ring, center.z + dz);
                if (ring != 0) enqueueIfNeeded(center.x - ring, center.z + dz);
            }
        }
    }

    private void enqueueIfNeeded(int cx, int cz) {
        ChunkPos cp = new ChunkPos(cx, cz);
        if (!scannedChunks.contains(cp) && queuedChunks.add(cp)) scanQueue.offer(cp);
    }

    private void pruneFarChunks(ChunkPos center, int radius) {
        int keep = Math.max(1, Math.min(32, radius)) + 1;
        flaggedChunks.removeIf(cp -> far(cp, center, keep));
        chunkHits.keySet().removeIf(cp -> far(cp, center, keep));
        scannedChunks.removeIf(cp -> far(cp, center, keep));
        queuedChunks.removeIf(cp -> far(cp, center, keep));
        dirtyChunks.removeIf(cp -> far(cp, center, keep));
        notifiedChunks.removeIf(cp -> far(cp, center, keep));
    }

    private static boolean far(ChunkPos cp, ChunkPos center, int keep) {
        return Math.abs(cp.x - center.x) > keep || Math.abs(cp.z - center.z) > keep;
    }

    private void drainDirtyChunks() {
        for (ChunkPos cp : dirtyChunks) {
            scannedChunks.remove(cp);
            if (queuedChunks.add(cp)) scanQueue.offer(cp);
        }
        dirtyChunks.clear();
    }

    private void tryStartScan() {
        if (scanInProgress.get()) return;

        List<WorldChunk> batch = new ArrayList<>();
        while (batch.size() < MAX_SCANS_PER_TICK) {
            ChunkPos cp = scanQueue.poll();
            if (cp == null) break;

            queuedChunks.remove(cp);
            if (scannedChunks.contains(cp)) continue;

            WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cp.x, cp.z);
            if (chunk == null) continue;

            scannedChunks.add(cp);
            batch.add(chunk);
        }

        if (batch.isEmpty()) return;

        ClientWorld world = mc.world;
        int threshold = minClusterSize.get();
        boolean notify = chatAlert.get();
        boolean lightOnly = lightOnlyMode.get();

        scanInProgress.set(true);
        executor.execute(() -> {
            try {
                for (WorldChunk chunk : batch) scanChunk(world, chunk, threshold, notify, lightOnly);
            } catch (Exception ignored) {
            } finally {
                scanInProgress.set(false);
            }
        });
    }

    private void scanChunk(ClientWorld world, WorldChunk chunk, int threshold, boolean notify, boolean lightOnly) {
        ChunkPos cp = chunk.getPos();
        Set<BlockPos> hits = new HashSet<>();
        int baseX = cp.x << 4;
        int baseZ = cp.z << 4;
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int y = -64; y <= 50; y++) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    pos.set(baseX + lx, y, baseZ + lz);
                    if (world.getLightLevel(LightType.BLOCK, pos) == 5 && (lightOnly ? isLightSource(world, pos) : isAmethystNearby(world, pos))) {
                        hits.add(pos.toImmutable());
                    }
                }
            }
        }

        if (hits.size() >= threshold) {
            chunkHits.put(cp, hits);
            flaggedChunks.add(cp);
            if (notify && notifiedChunks.add(cp)) {
                info("Amethyst clusters at %d %d (%d hits)", cp.getCenterX(), cp.getCenterZ(), hits.size());
            }
        } else {
            chunkHits.remove(cp);
            flaggedChunks.remove(cp);
            notifiedChunks.remove(cp);
        }
    }

    private static boolean isLightSource(ClientWorld world, BlockPos center) {
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (Direction dir : Direction.values()) {
            pos.set(center.getX() + dir.getOffsetX(), center.getY() + dir.getOffsetY(), center.getZ() + dir.getOffsetZ());
            if (world.getLightLevel(LightType.BLOCK, pos) > 5) return false;
        }

        return true;
    }

    private static boolean isAmethystNearby(ClientWorld world, BlockPos center) {
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isOf(Blocks.AMETHYST_CLUSTER)
                        || state.isOf(Blocks.LARGE_AMETHYST_BUD)
                        || state.isOf(Blocks.MEDIUM_AMETHYST_BUD)
                        || state.isOf(Blocks.SMALL_AMETHYST_BUD)
                        || state.isOf(Blocks.BUDDING_AMETHYST)
                        || state.isOf(Blocks.AMETHYST_BLOCK)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private void clearState() {
        flaggedChunks.clear();
        chunkHits.clear();
        scannedChunks.clear();
        queuedChunks.clear();
        scanQueue.clear();
        dirtyChunks.clear();
        notifiedChunks.clear();
        lastPlayerChunk = null;
        lastQueueRebuild = 0L;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null || flaggedChunks.isEmpty()) return;

        boolean drawBlocks = blockEsp.get();
        boolean drawChunk = chunkMark.get();
        boolean drawTracers = tracers.get();
        int a = alpha.get();
        double chunkY = chunkYLevel.get();

        Color chunkColor = new Color(180, 100, 255, a);
        Color blockColor = new Color(180, 100, 255, Math.min(255, a + 20));
        Color tracerColor = new Color(180, 100, 255, 220);

        Vec3d cam = mc.gameRenderer.getCamera().getPos();
        Vec3d look = Vec3d.fromPolar(mc.gameRenderer.getCamera().getPitch(), mc.gameRenderer.getCamera().getYaw());
        Vec3d tracerStart = cam.add(look.multiply(0.12));

        for (ChunkPos cp : new ArrayList<>(flaggedChunks)) {
            Set<BlockPos> positions = chunkHits.get(cp);
            if (positions == null || positions.isEmpty()) continue;

            if (drawChunk) {
                event.renderer.box(cp.getStartX(), chunkY, cp.getStartZ(), cp.getEndX() + 1.0, chunkY + 0.05, cp.getEndZ() + 1.0,
                    chunkColor, chunkColor, ShapeMode.Sides, 0);
            }

            if (drawBlocks) {
                for (BlockPos p : positions) {
                    event.renderer.box(p.getX(), p.getY(), p.getZ(), p.getX() + 1.0, p.getY() + 1.0, p.getZ() + 1.0,
                        blockColor, blockColor, ShapeMode.Sides, 0);
                }
            }

            if (drawTracers) {
                BlockPos nearest = nearestToPlayer(positions);
                if (nearest != null) {
                    event.renderer.line(tracerStart.x, tracerStart.y, tracerStart.z,
                        nearest.getX() + 0.5, nearest.getY() + 0.5, nearest.getZ() + 0.5, tracerColor);
                }
            }
        }
    }

    private BlockPos nearestToPlayer(Set<BlockPos> positions) {
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        BlockPos playerPos = mc.player.getBlockPos();

        for (BlockPos p : positions) {
            double dist = p.getSquaredDistance(playerPos);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = p;
            }
        }

        return nearest;
    }
             }

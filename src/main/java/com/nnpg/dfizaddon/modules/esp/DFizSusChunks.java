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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class DFizSusChunks extends Module {
    private static final long CHUNK_RESCAN_MS = 3000L;
    private static final long SCAN_TICK_MS = 200L;
    private static final long EXPOSED_SCAN_MS = 1000L;
    private static final int MAX_CHUNKS_PER_SCAN = 6;
    private static final double PLATE_Y = 60.0;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> simulationDistance = sgGeneral.add(new IntSetting.Builder()
        .name("simulation-distance")
        .description("Scan radius in chunks.")
        .defaultValue(4)
        .range(1, 16)
        .sliderRange(1, 16)
        .build()
    );

    private final Setting<Integer> sensitivity = sgGeneral.add(new IntSetting.Builder()
        .name("sensitivity")
        .description("Higher = more hits needed in a chunk before it is marked.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Integer> alpha = sgRender.add(new IntSetting.Builder()
        .name("alpha")
        .description("Opacity of the plates.")
        .defaultValue(100)
        .range(0, 255)
        .sliderRange(0, 255)
        .build()
    );

    private final Setting<Boolean> showExposedAmethyst = sgRender.add(new BoolSetting.Builder()
        .name("show-exposed-amethyst")
        .description("Highlight amethyst blocks that touch air.")
        .defaultValue(true)
        .build()
    );

    private final Set<ChunkPos> susChunks = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, Long> lastScanAt = new ConcurrentHashMap<>();
    private final Set<BlockPos> exposedPositions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean scanRunning = new AtomicBoolean(false);
    private ExecutorService executor;
    private long lastScheduleMs;
    private long lastExposedScanMs;

    public DFizSusChunks() {
        super(DFizAddon.CATEGORY, "dfiz-sus-chunks", "Marks chunks with a lot of amethyst around the surface-level geodes.");
    }

    @Override
    public void onActivate() {
        clear();
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "dfiz-sus-chunks");
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
        scanRunning.set(false);
        clear();
    }

    private void clear() {
        susChunks.clear();
        lastScanAt.clear();
        exposedPositions.clear();
        lastScheduleMs = 0L;
        lastExposedScanMs = 0L;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || executor == null) return;

        int simDist = simulationDistance.get();
        ChunkPos playerChunk = new ChunkPos(mc.player.getBlockPos());

        susChunks.removeIf(pos -> Math.abs(pos.x - playerChunk.x) > simDist + 1 || Math.abs(pos.z - playerChunk.z) > simDist + 1);
        lastScanAt.keySet().removeIf(pos -> Math.abs(pos.x - playerChunk.x) > simDist + 2 || Math.abs(pos.z - playerChunk.z) > simDist + 2);
        exposedPositions.removeIf(bp -> Math.abs((bp.getX() >> 4) - playerChunk.x) > simDist + 1
            || Math.abs((bp.getZ() >> 4) - playerChunk.z) > simDist + 1);

        scheduleScan(playerChunk, simDist);
    }

    private void scheduleScan(ChunkPos playerChunk, int simDist) {
        long now = System.currentTimeMillis();
        if (scanRunning.get() || now - lastScheduleMs < SCAN_TICK_MS) return;
        lastScheduleMs = now;

        ClientWorld world = mc.world;
        List<ChunkPos> candidates = new ArrayList<>();

        for (int cx = playerChunk.x - simDist; cx <= playerChunk.x + simDist; cx++) {
            for (int cz = playerChunk.z - simDist; cz <= playerChunk.z + simDist; cz++) {
                if (!world.getChunkManager().isChunkLoaded(cx, cz)) continue;

                ChunkPos chunkPos = new ChunkPos(cx, cz);
                long last = lastScanAt.getOrDefault(chunkPos, 0L);
                if (last == 0L || now - last >= CHUNK_RESCAN_MS) candidates.add(chunkPos);
            }
        }

        if (candidates.isEmpty()) return;

        int clusterThreshold = Math.max(1, sensitivity.get()) * 2;
        boolean exposedOn = showExposedAmethyst.get();

        scanRunning.set(true);
        executor.execute(() -> {
            try {
                if (exposedOn) scanExposedAmethyst(world, playerChunk, simDist);

                int scanCount = 0;
                for (ChunkPos chunkPos : candidates) {
                    if (scanCount >= MAX_CHUNKS_PER_SCAN) break;

                    WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkPos.x, chunkPos.z);
                    if (chunk == null) continue;

                    int clusters = countClusterHits(world, chunk, -64, 50);
                    if (clusters >= clusterThreshold) {
                        susChunks.add(chunkPos);
                    } else {
                        susChunks.remove(chunkPos);
                    }

                    lastScanAt.put(chunkPos, System.currentTimeMillis());
                    scanCount++;
                }
            } catch (Exception ignored) {
            } finally {
                scanRunning.set(false);
            }
        });
    }

    private void scanExposedAmethyst(ClientWorld world, ChunkPos playerChunk, int simDist) {
        long now = System.currentTimeMillis();
        if (now - lastExposedScanMs < EXPOSED_SCAN_MS) return;
        lastExposedScanMs = now;

        for (int cx = playerChunk.x - simDist; cx <= playerChunk.x + simDist; cx++) {
            for (int cz = playerChunk.z - simDist; cz <= playerChunk.z + simDist; cz++) {
                if (!world.getChunkManager().isChunkLoaded(cx, cz)) continue;

                WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) continue;

                int finalCx = cx;
                int finalCz = cz;
                exposedPositions.removeIf(bp -> (bp.getX() >> 4) == finalCx && (bp.getZ() >> 4) == finalCz);
                collectExposedAmethyst(world, chunk, -64, 128);
            }
        }
    }

    private void collectExposedAmethyst(ClientWorld world, WorldChunk chunk, int minY, int maxY) {
        WorldChunk chunkNorth = world.getChunk(chunk.getPos().x, chunk.getPos().z - 1);
        WorldChunk chunkSouth = world.getChunk(chunk.getPos().x, chunk.getPos().z + 1);
        WorldChunk chunkWest = world.getChunk(chunk.getPos().x - 1, chunk.getPos().z);
        WorldChunk chunkEast = world.getChunk(chunk.getPos().x + 1, chunk.getPos().z);
        int bottomY = world.getBottomY();
        ChunkSection[] sections = chunk.getSectionArray();

        for (int si = 0; si < sections.length; si++) {
            ChunkSection section = sections[si];
            if (section == null || section.isEmpty()) continue;

            int sectionBaseY = bottomY + si * 16;
            if (sectionBaseY + 15 < minY || sectionBaseY > maxY) continue;
            if (!section.hasAny(state -> isAmethystCandidate(state.getBlock()))) continue;

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int ly = 0; ly < 16; ly++) {
                        int worldY = sectionBaseY + ly;
                        if (worldY < minY || worldY > maxY) continue;

                        Block block = section.getBlockState(lx, ly, lz).getBlock();
                        if (isAmethystCandidate(block)
                            && hasAirFace(sections, chunkNorth, chunkSouth, chunkWest, chunkEast, lx, ly, lz, si, worldY, bottomY)) {
                            exposedPositions.add(new BlockPos(lx + chunk.getPos().getStartX(), worldY, lz + chunk.getPos().getStartZ()));
                        }
                    }
                }
            }
        }
    }

    private static boolean hasAirFace(ChunkSection[] sections, WorldChunk cn, WorldChunk cs, WorldChunk cw, WorldChunk ce,
                                      int lx, int ly, int lz, int si, int wy, int by) {
        if (ly > 0) {
            if (sections[si].getBlockState(lx, ly - 1, lz).isAir()) return true;
        } else if (si > 0 && sections[si - 1] != null && sections[si - 1].getBlockState(lx, 15, lz).isAir()) {
            return true;
        }

        if (ly < 15) {
            if (sections[si].getBlockState(lx, ly + 1, lz).isAir()) return true;
        } else if (si < sections.length - 1 && sections[si + 1] != null && sections[si + 1].getBlockState(lx, 0, lz).isAir()) {
            return true;
        }

        if (lz > 0) {
            if (sections[si].getBlockState(lx, ly, lz - 1).isAir()) return true;
        } else if (cn != null && getStateInChunk(cn, lx, wy, 15, by).isAir()) {
            return true;
        }

        if (lz < 15) {
            if (sections[si].getBlockState(lx, ly, lz + 1).isAir()) return true;
        } else if (cs != null && getStateInChunk(cs, lx, wy, 0, by).isAir()) {
            return true;
        }

        if (lx > 0) {
            if (sections[si].getBlockState(lx - 1, ly, lz).isAir()) return true;
        } else if (cw != null && getStateInChunk(cw, 15, wy, lz, by).isAir()) {
            return true;
        }

        if (lx < 15) {
            if (sections[si].getBlockState(lx + 1, ly, lz).isAir()) return true;
        } else if (ce != null && getStateInChunk(ce, 0, wy, lz, by).isAir()) {
            return true;
        }

        return false;
    }

    private static BlockState getStateInChunk(WorldChunk neighbour, int lx, int worldY, int lz, int bottomY) {
        int sectionIndex = (worldY - bottomY) >> 4;
        int localY = (worldY - bottomY) & 15;
        ChunkSection[] secs = neighbour.getSectionArray();

        if (sectionIndex >= 0 && sectionIndex < secs.length && secs[sectionIndex] != null) {
            return secs[sectionIndex].getBlockState(lx, localY, lz);
        }
        return Blocks.AIR.getDefaultState();
    }

    private static boolean isAmethystCandidate(Block b) {
        return b == Blocks.AMETHYST_BLOCK || b == Blocks.BUDDING_AMETHYST || b == Blocks.AMETHYST_CLUSTER;
    }

    private static int countClusterHits(ClientWorld world, WorldChunk chunk, int minY, int maxY) {
        int count = 0;
        int baseX = chunk.getPos().x << 4;
        int baseZ = chunk.getPos().z << 4;
        int topY = Math.min(maxY, 50);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        BlockPos.Mutable neighbor = new BlockPos.Mutable();

        for (int y = minY; y <= topY; y++) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    pos.set(baseX + lx, y, baseZ + lz);
                    if (world.getLightLevel(LightType.BLOCK, pos) == 5 && hasNearbyAmethyst(world, pos, neighbor)) count++;
                }
            }
        }

        return count;
    }

    private static boolean hasNearbyAmethyst(ClientWorld world, BlockPos center, BlockPos.Mutable neighbor) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    neighbor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = world.getBlockState(neighbor);
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

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (susChunks.isEmpty() && exposedPositions.isEmpty()) return;

        int a = Math.max(50, Math.min(170, alpha.get()));
        Color fill = new Color(255, 30, 30, a);
        Color rim = new Color(255, 100, 100, Math.min(255, a + 80));

        Set<ChunkPos> plates = new HashSet<>();
        for (ChunkPos cp : susChunks) {
            plates.add(cp);
            plates.add(new ChunkPos(cp.x + 1, cp.z));
            plates.add(new ChunkPos(cp.x - 1, cp.z));
            plates.add(new ChunkPos(cp.x, cp.z + 1));
            plates.add(new ChunkPos(cp.x, cp.z - 1));
            plates.add(new ChunkPos(cp.x + 1, cp.z + 1));
            plates.add(new ChunkPos(cp.x - 1, cp.z - 1));
        }

        for (ChunkPos chunkPos : plates) {
            event.renderer.box(chunkPos.getStartX() - 0.1, PLATE_Y - 0.02, chunkPos.getStartZ() - 0.1,
                chunkPos.getStartX() + 16.1, PLATE_Y + 0.12, chunkPos.getStartZ() + 16.1,
                rim, rim, ShapeMode.Sides, 0);
        }

        for (ChunkPos chunkPos : plates) {
            event.renderer.box(chunkPos.getStartX() - 0.05, PLATE_Y, chunkPos.getStartZ() - 0.05,
                chunkPos.getStartX() + 16.05, PLATE_Y + 0.1, chunkPos.getStartZ() + 16.05,
                fill, fill, ShapeMode.Sides, 0);
        }

        if (showExposedAmethyst.get()) {
            Color orange = new Color(255, 165, 0, Math.max(0, Math.min(255, alpha.get())));
            for (BlockPos bp : exposedPositions) {
                event.renderer.box(bp.getX(), bp.getY(), bp.getZ(), bp.getX() + 1, bp.getY() + 1, bp.getZ() + 1,
                    orange, orange, ShapeMode.Sides, 0);
            }
        }
    }
}

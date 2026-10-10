package com.nnpg.dfizaddon.modules.esp;

import com.nnpg.dfizaddon.DFizAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;
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
    private static final int MAX_CHUNKS_PER_SCAN = 6;
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 50;
    private static final double PLATE_Y = 60.0;

    public enum LightMode {
        Light5,
        Light4,
        Both
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

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
        .description("Higher = more light cells needed in a chunk before it is marked.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<LightMode> lightMode = sgGeneral.add(new EnumSetting.Builder<LightMode>()
        .name("light-mode")
        .description("Light5: cells with light 5 (the clusters, only when you are close). Light4: cells with light 4 (the air around clusters). Both: use both.")
        .defaultValue(LightMode.Light4)
        .build()
    );

    private final Setting<Integer> alpha = sgGeneral.add(new IntSetting.Builder()
        .name("alpha")
        .description("Opacity of the plates.")
        .defaultValue(100)
        .range(0, 255)
        .sliderRange(0, 255)
        .build()
    );

    private final Set<ChunkPos> susChunks = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, Long> lastScanAt = new ConcurrentHashMap<>();
    private final AtomicBoolean scanRunning = new AtomicBoolean(false);
    private ExecutorService executor;
    private long lastScheduleMs;
    private Object lastWorld;

    public DFizSusChunks() {
        super(DFizAddon.CATEGORY, "dfiz-sus-chunks", "Marks chunks that have amethyst clusters, found from block light.");
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

    @Override
    public String getInfoString() {
        return String.valueOf(susChunks.size());
    }

    private void clear() {
        susChunks.clear();
        lastScanAt.clear();
        lastScheduleMs = 0L;
        lastWorld = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || executor == null) return;

        if (lastWorld != mc.world) {
            if (lastWorld != null) {
                susChunks.clear();
                lastScanAt.clear();
            }
            lastWorld = mc.world;
        }

        int simDist = simulationDistance.get();
        ChunkPos playerChunk = new ChunkPos(mc.player.getBlockPos());

        lastScanAt.keySet().removeIf(pos -> Math.abs(pos.x - playerChunk.x) > simDist + 2 || Math.abs(pos.z - playerChunk.z) > simDist + 2);

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
                if (susChunks.contains(chunkPos)) continue;

                long last = lastScanAt.getOrDefault(chunkPos, 0L);
                if (last == 0L || now - last >= CHUNK_RESCAN_MS) candidates.add(chunkPos);
            }
        }

        if (candidates.isEmpty()) return;

        int needed = Math.max(1, sensitivity.get()) * 6;
        LightMode mode = lightMode.get();

        scanRunning.set(true);
        executor.execute(() -> {
            try {
                int scanned = 0;
                for (ChunkPos chunkPos : candidates) {
                    if (scanned >= MAX_CHUNKS_PER_SCAN) break;

                    WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkPos.x, chunkPos.z);
                    if (chunk == null) continue;

                    if (score(world, chunk, mode) >= needed) susChunks.add(chunkPos);

                    lastScanAt.put(chunkPos, System.currentTimeMillis());
                    scanned++;
                }
            } catch (Throwable ignored) {
            } finally {
                scanRunning.set(false);
            }
        });
    }

    private static int score(ClientWorld world, WorldChunk chunk, LightMode mode) {
        boolean use5 = mode != LightMode.Light4;
        boolean use4 = mode != LightMode.Light5;
        int hits5 = 0;
        int hits4 = 0;
        int baseX = chunk.getPos().x << 4;
        int baseZ = chunk.getPos().z << 4;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        BlockPos.Mutable near = new BlockPos.Mutable();

        for (int y = MIN_Y; y <= MAX_Y; y++) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    pos.set(baseX + lx, y, baseZ + lz);
                    int light = world.getLightLevel(LightType.BLOCK, pos);

                    if (light == 5) {
                        if (use5 && isSource(world, pos, near)) hits5++;
                    } else if (light == 4) {
                        if (use4 && lx > 0 && lx < 15 && lz > 0 && lz < 15 && isGlowCell(world, pos, near)) hits4++;
                    }
                }
            }
        }

        return hits5 * 3 + hits4;
    }

    private static boolean isSource(ClientWorld world, BlockPos center, BlockPos.Mutable near) {
        for (Direction dir : Direction.values()) {
            near.set(center.getX() + dir.getOffsetX(), center.getY() + dir.getOffsetY(), center.getZ() + dir.getOffsetZ());
            if (world.getLightLevel(LightType.BLOCK, near) > 5) return false;
        }

        return true;
    }

    private static boolean isGlowCell(ClientWorld world, BlockPos center, BlockPos.Mutable near) {
        for (Direction dir : Direction.values()) {
            near.set(center.getX() + dir.getOffsetX(), center.getY() + dir.getOffsetY(), center.getZ() + dir.getOffsetZ());
            if (world.getLightLevel(LightType.BLOCK, near) > 4) return false;
        }

        return true;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (susChunks.isEmpty()) return;

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
    }
}

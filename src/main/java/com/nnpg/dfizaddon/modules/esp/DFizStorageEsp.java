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
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.block.entity.PistonBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class DFizStorageEsp extends Module {
    private static final int SIGNAL_RADIUS_CHUNKS = 6;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSignal = settings.createGroup("Signal");

    private final Setting<Boolean> chests = sgGeneral.add(new BoolSetting.Builder()
        .name("chests").description("Chests and trapped chests.").defaultValue(true).build());

    private final Setting<Boolean> enderChests = sgGeneral.add(new BoolSetting.Builder()
        .name("ender-chests").description("Ender chests.").defaultValue(true).build());

    private final Setting<Boolean> shulkerBoxes = sgGeneral.add(new BoolSetting.Builder()
        .name("shulker-boxes").description("Shulker boxes.").defaultValue(true).build());

    private final Setting<Boolean> furnaces = sgGeneral.add(new BoolSetting.Builder()
        .name("furnaces").description("Furnaces, blast furnaces and smokers.").defaultValue(false).build());

    private final Setting<Boolean> barrels = sgGeneral.add(new BoolSetting.Builder()
        .name("barrels").description("Barrels.").defaultValue(true).build());

    private final Setting<Boolean> spawners = sgGeneral.add(new BoolSetting.Builder()
        .name("spawners").description("Spawners.").defaultValue(true).build());

    private final Setting<Boolean> pistons = sgGeneral.add(new BoolSetting.Builder()
        .name("pistons").description("Pistons and sticky pistons near you.").defaultValue(false).build());

    private final Setting<Integer> alpha = sgGeneral.add(new IntSetting.Builder()
        .name("alpha")
        .description("Opacity of the boxes.")
        .defaultValue(100)
        .range(0, 255)
        .sliderRange(0, 255)
        .build()
    );

    private final Setting<Boolean> tracers = sgGeneral.add(new BoolSetting.Builder()
        .name("tracers").description("Draw a line to every highlighted block.").defaultValue(false).build());

    private final Setting<Boolean> bypassStorageLoad = sgGeneral.add(new BoolSetting.Builder()
        .name("bypass-storage-load")
        .description("Also highlight storage blocks whose block entity is not loaded yet.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> scanRadius = sgGeneral.add(new IntSetting.Builder()
        .name("scan-radius")
        .description("Radius in chunks around you.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 12)
        .build()
    );

    private final Setting<Boolean> signalMode = sgSignal.add(new BoolSetting.Builder()
        .name("signal-mode")
        .description("Mark chunks where storage blocks keep changing (base activity).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> signalSpawnerOnly = sgSignal.add(new BoolSetting.Builder()
        .name("signal-spawner-only")
        .description("Only count spawners, and only highlight spawners.")
        .defaultValue(false)
        .visible(signalMode::get)
        .build()
    );

    private final Setting<Integer> signalYCutoff = sgSignal.add(new IntSetting.Builder()
        .name("signal-y-cutoff")
        .description("Only count blocks at or below this Y.")
        .defaultValue(0)
        .range(-64, 320)
        .sliderRange(-64, 320)
        .visible(signalMode::get)
        .build()
    );

    private final Setting<Integer> signalWindowSec = sgSignal.add(new IntSetting.Builder()
        .name("signal-window-sec")
        .description("Time window in seconds.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 30)
        .visible(signalMode::get)
        .build()
    );

    private final Setting<Integer> minSignals = sgSignal.add(new IntSetting.Builder()
        .name("min-signals")
        .description("Block updates needed inside the window to mark a chunk.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 30)
        .visible(signalMode::get)
        .build()
    );

    private final Map<ChunkPos, SignalActivity> signalActivity = new ConcurrentHashMap<>();
    private final Set<BlockPos> renderedPositions = new HashSet<>();
    private final List<Box> pistonBoxes = new CopyOnWriteArrayList<>();
    private final AtomicBoolean pistonScanRunning = new AtomicBoolean(false);
    private ExecutorService executor;
    private long lastPistonScanTick = Long.MIN_VALUE;

    public DFizStorageEsp() {
        super(DFizAddon.CATEGORY, "dfiz-storage-esp", "Highlights storage blocks, spawners and pistons through walls.");
    }

    @Override
    public void onActivate() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "dfiz-piston-scanner");
            t.setDaemon(true);
            return t;
        });
        lastPistonScanTick = Long.MIN_VALUE;
    }

    @Override
    public void onDeactivate() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        pistonScanRunning.set(false);
        pistonBoxes.clear();
        signalActivity.clear();
        renderedPositions.clear();
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (!signalMode.get()) return;
        if (event.pos.getY() > signalYCutoff.get()) return;
        if (!isSignalState(event.newState, signalSpawnerOnly.get())) return;

        signalActivity.computeIfAbsent(new ChunkPos(event.pos), k -> new SignalActivity()).record();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!pistons.get()) {
            if (!pistonBoxes.isEmpty()) pistonBoxes.clear();
            return;
        }
        if (mc.world == null || mc.player == null || executor == null) return;

        long tick = mc.world.getTime();
        if (tick == lastPistonScanTick || tick % 20L != 0L) return;
        lastPistonScanTick = tick;

        if (!pistonScanRunning.compareAndSet(false, true)) return;

        ClientWorld world = mc.world;
        BlockPos playerPos = mc.player.getBlockPos();

        executor.execute(() -> {
            try {
                int centerChunkX = playerPos.getX() >> 4;
                int centerChunkZ = playerPos.getZ() >> 4;
                int minY = Math.max(playerPos.getY() - 16, world.getBottomY());
                int maxY = Math.min(playerPos.getY() + 16, world.getTopY() - 1);
                List<Box> newBoxes = new ArrayList<>();
                BlockPos.Mutable pos = new BlockPos.Mutable();

                for (int cx = -3; cx <= 3; cx++) {
                    for (int cz = -3; cz <= 3; cz++) {
                        int chunkX = centerChunkX + cx;
                        int chunkZ = centerChunkZ + cz;
                        if (!world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) continue;

                        WorldChunk chunk = world.getChunk(chunkX, chunkZ);
                        if (chunk == null) continue;

                        int startX = chunk.getPos().getStartX();
                        int startZ = chunk.getPos().getStartZ();

                        for (int x = startX; x <= startX + 15; x++) {
                            for (int z = startZ; z <= startZ + 15; z++) {
                                for (int y = minY; y <= maxY; y++) {
                                    pos.set(x, y, z);
                                    BlockState state = chunk.getBlockState(pos);
                                    if (state.isOf(Blocks.PISTON) || state.isOf(Blocks.STICKY_PISTON)) {
                                        newBoxes.add(new Box(x, y, z, x + 1, y + 1, z + 1));
                                    }
                                }
                            }
                        }
                    }
                }

                pistonBoxes.clear();
                pistonBoxes.addAll(newBoxes);
            } catch (Exception ignored) {
            } finally {
                pistonScanRunning.set(false);
            }
        });
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        ClientWorld world = mc.world;
        Vec3d cam = mc.gameRenderer.getCamera().getPos();
        Vec3d look = Vec3d.fromPolar(mc.gameRenderer.getCamera().getPitch(), mc.gameRenderer.getCamera().getYaw());
        Vec3d tracerStart = cam.add(look.multiply(0.12));

        boolean spawnerOnly = signalMode.get() && signalSpawnerOnly.get();
        BlockPos playerPos = mc.player.getBlockPos();
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;
        int radius = scanRadius.get();

        for (int cx = -radius; cx <= radius; cx++) {
            for (int cz = -radius; cz <= radius; cz++) {
                int chunkX = centerChunkX + cx;
                int chunkZ = centerChunkZ + cz;
                if (!world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) continue;

                WorldChunk chunk = world.getChunk(chunkX, chunkZ);
                if (chunk == null) continue;

                renderedPositions.clear();

                for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
                    if (spawnerOnly && !(be instanceof MobSpawnerBlockEntity)) continue;
                    if (!shouldRender(be)) continue;

                    Color color = colorFor(be);
                    Box box = new Box(be.getPos());
                    fill(event, box, color, cam);
                    renderedPositions.add(be.getPos());

                    if (tracers.get()) tracer(event, tracerStart, box.getCenter(), color);
                }

                if (!bypassStorageLoad.get()) continue;

                for (BlockPos pos : chunk.getBlockEntityPositions()) {
                    if (renderedPositions.contains(pos)) continue;

                    BlockState state = chunk.getBlockState(pos);
                    if (!shouldRenderState(state)) continue;

                    Color color = colorForState(state);
                    Box box = new Box(pos);
                    fill(event, box, color, cam);

                    if (tracers.get()) tracer(event, tracerStart, box.getCenter(), color);
                }
            }
        }

        if (pistons.get()) {
            Color pistonColor = new Color(0, 255, 64, alpha.get());
            for (Box box : pistonBoxes) {
                fill(event, box, pistonColor, cam);
            }
        }

        if (signalMode.get()) {
            ChunkPos playerChunk = mc.player.getChunkPos();
            long windowMs = Math.max(1000L, signalWindowSec.get() * 1000L);
            Color white = new Color(255, 255, 255, 255);

            for (ChunkPos chunkPos : new HashSet<>(signalActivity.keySet())) {
                if (Math.abs(chunkPos.x - playerChunk.x) > SIGNAL_RADIUS_CHUNKS) continue;
                if (Math.abs(chunkPos.z - playerChunk.z) > SIGNAL_RADIUS_CHUNKS) continue;

                SignalActivity activity = signalActivity.get(chunkPos);
                if (activity == null || activity.count(windowMs) < minSignals.get()) continue;

                Box plane = new Box(chunkPos.getStartX(), -0.2, chunkPos.getStartZ(),
                    chunkPos.getStartX() + 16.0, 0.3, chunkPos.getStartZ() + 16.0);
                fill(event, plane, white, cam);
            }
        }
    }

    private void fill(Render3DEvent event, Box worldBox, Color color, Vec3d cam) {
        double d2 = worldBox.getCenter().squaredDistanceTo(cam);
        double grow = 0.01;
        if (d2 > 324.0) grow = Math.max(0.0, Math.min(0.09, (Math.sqrt(d2) - 18.0) * 0.0015));

        Box box = worldBox.expand(grow, 0.01, grow);
        event.renderer.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, color, color, ShapeMode.Sides, 0);
    }

    private void tracer(Render3DEvent event, Vec3d from, Vec3d to, Color color) {
        event.renderer.line(from.x, from.y, from.z, to.x, to.y, to.z, color);
    }

    private boolean shouldRender(BlockEntity be) {
        if (be instanceof ChestBlockEntity) return chests.get();
        if (be instanceof EnderChestBlockEntity) return enderChests.get();
        if (be instanceof ShulkerBoxBlockEntity) return shulkerBoxes.get();
        if (be instanceof AbstractFurnaceBlockEntity) return furnaces.get();
        if (be instanceof BarrelBlockEntity) return barrels.get();
        if (be instanceof MobSpawnerBlockEntity) return spawners.get();
        if (be instanceof PistonBlockEntity) return pistons.get();
        return false;
    }

    private Color colorFor(BlockEntity be) {
        int a = alpha.get();
        if (be instanceof ChestBlockEntity) return new Color(168, 106, 0, a);
        if (be instanceof EnderChestBlockEntity) return new Color(94, 22, 184, a);
        if (be instanceof ShulkerBoxBlockEntity) return new Color(255, 105, 180, a);
        if (be instanceof AbstractFurnaceBlockEntity) return new Color(128, 128, 128, a);
        if (be instanceof BarrelBlockEntity) return new Color(139, 69, 19, a);
        if (be instanceof MobSpawnerBlockEntity) return new Color(128, 128, 128, 255);
        if (be instanceof PistonBlockEntity) return new Color(0, 255, 64, a);
        return new Color(255, 196, 51, a);
    }

    private boolean shouldRenderState(BlockState state) {
        if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST)) return chests.get();
        if (state.isOf(Blocks.ENDER_CHEST)) return enderChests.get();
        if (state.isOf(Blocks.BARREL)) return barrels.get();
        if (state.isOf(Blocks.SPAWNER)) return spawners.get();
        if (isShulker(state)) return shulkerBoxes.get();
        if (state.isOf(Blocks.FURNACE) || state.isOf(Blocks.BLAST_FURNACE) || state.isOf(Blocks.SMOKER)) return furnaces.get();
        if (state.isOf(Blocks.PISTON) || state.isOf(Blocks.STICKY_PISTON)) return pistons.get();
        return false;
    }

    private Color colorForState(BlockState state) {
        int a = alpha.get();
        if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST)) return new Color(168, 106, 0, a);
        if (state.isOf(Blocks.ENDER_CHEST)) return new Color(94, 22, 184, a);
        if (isShulker(state)) return new Color(255, 105, 180, a);
        if (state.isOf(Blocks.FURNACE) || state.isOf(Blocks.BLAST_FURNACE) || state.isOf(Blocks.SMOKER)) return new Color(128, 128, 128, a);
        if (state.isOf(Blocks.BARREL)) return new Color(139, 69, 19, a);
        if (state.isOf(Blocks.SPAWNER)) return new Color(128, 128, 128, 255);
        if (state.isOf(Blocks.PISTON) || state.isOf(Blocks.STICKY_PISTON)) return new Color(0, 255, 64, a);
        return new Color(255, 196, 51, a);
    }

    private static boolean isShulker(BlockState state) {
        return state.getBlock() instanceof ShulkerBoxBlock;
    }

    private static boolean isSignalState(BlockState state, boolean spawnerOnly) {
        if (spawnerOnly) return state.isOf(Blocks.SPAWNER);

        return state.isOf(Blocks.CHEST)
            || state.isOf(Blocks.TRAPPED_CHEST)
            || state.isOf(Blocks.ENDER_CHEST)
            || state.isOf(Blocks.BARREL)
            || state.isOf(Blocks.SPAWNER)
            || isShulker(state);
    }

    private static final class SignalActivity {
        private final ArrayDeque<Long> hits = new ArrayDeque<>();

        void record() {
            hits.addLast(System.currentTimeMillis());
            trim(30000L);
        }

        int count(long windowMs) {
            trim(windowMs);
            return hits.size();
        }

        private void trim(long windowMs) {
            long cutoff = System.currentTimeMillis() - windowMs;
            while (!hits.isEmpty() && hits.peekFirst() < cutoff) hits.removeFirst();
        }
    }
}

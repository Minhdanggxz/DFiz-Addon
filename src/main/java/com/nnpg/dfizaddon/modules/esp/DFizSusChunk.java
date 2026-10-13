package com.nnpg.dfizaddon.modules.esp;

import com.nnpg.dfizaddon.DFizAddon;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class DFizSusChunk extends Module {
    private static final long SCAN_BUDGET_NANOS = 3_000_000L;
    private static final int MIN_SCANNED_NEIGHBOURS = 3;
    private static final int VISIBLE_REFRESH_TICKS = 20;
    private static final int PACKET_MIN_Y = -16;
    private static final int PACKET_MAX_Y = 30;
    private static final int GEODE_TOP_Y = 47;
    private static final int MIN_EXPOSED_BUDDING = 10;
    private static final int MIN_EXPOSED_LINING = 160;
    private static final int GEODE_SETTLE_TICKS = 40;
    private static final int GEODE_CELL_SHIFT = 2;
    private static final int TORCH_LIGHT = 14;
    private static final int PENDING_TTL = 100;
    private static final Direction[] SIDES = Direction.values();

    private record ChunkScore(int selfHeat, boolean hasUngrown) {}

    private record GeodeSlice(long[] lining, long[] budding) {}

    private record GeodeCluster(long[] lining, int budding, int minX, int minZ, int maxX, int maxZ, long chunk) {}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final IntSetting spreadRadius = sgGeneral.add(new IntSetting.Builder()
        .name("simulation-distance")
        .description("Chunk radius a signal spreads to.")
        .defaultValue(5)
        .range(2, 16)
        .sliderRange(2, 16)
        .onChanged(v -> rescan())
        .build()
    );

    private final IntSetting sensitivity = sgGeneral.add(new IntSetting.Builder()
        .name("sensitivity")
        .description("Minimum heat for a chunk to be shown.")
        .defaultValue(6)
        .range(1, 20)
        .sliderRange(1, 20)
        .onChanged(v -> visibleDirty = true)
        .build()
    );

    private final ColorSetting chunkColor = sgGeneral.add(new ColorSetting.Builder()
        .name("chunk-color")
        .description("Color of suspicious chunks.")
        .defaultValue(new SettingColor(255, 4, 4, 96))
        .build()
    );

    private final BoolSetting tracers = sgGeneral.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw tracers to the chunks.")
        .defaultValue(true)
        .build()
    );

    private final EnumSetting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How chunk boxes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final BoolSetting detectMined = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-mined-clusters")
        .description("Highlight chunks whose amethyst geode was mined out and lit up.")
        .defaultValue(false)
        .onChanged(v -> restartGeodes())
        .build()
    );

    private final ColorSetting minedColor = sgGeneral.add(new ColorSetting.Builder()
        .name("mined-color")
        .description("Color of mined geode chunks.")
        .defaultValue(new SettingColor(166, 77, 255, 96))
        .visible(detectMined::get)
        .build()
    );

    private final Long2IntOpenHashMap heat = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap vetoes = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap chunkScore = new Long2IntOpenHashMap();
    private final Long2BooleanOpenHashMap chunkUngrown = new Long2BooleanOpenHashMap();
    private final Long2BooleanOpenHashMap chunkHasPacket = new Long2BooleanOpenHashMap();
    private final LongOpenHashSet scannedChunks = new LongOpenHashSet();
    private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
    private final LongOpenHashSet queued = new LongOpenHashSet();
    private final ConcurrentLinkedQueue<Long> incoming = new ConcurrentLinkedQueue<>();
    private final Long2IntOpenHashMap pending = new Long2IntOpenHashMap();

    private final Long2ObjectOpenHashMap<GeodeSlice> geodeSlices = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap geodeWaiting = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap geodeScanTicks = new Long2IntOpenHashMap();

    private Long2IntOpenHashMap visible = new Long2IntOpenHashMap();
    private boolean visibleDirty;
    private int visibleAge;
    private ClientWorld lastLevel;
    private int appliedRadius;
    private List<GeodeCluster> geodeClusters = List.of();
    private boolean geodeClustersDirty;
    private LongOpenHashSet minedChunks = new LongOpenHashSet();
    private int minedAge;
    private int ticks;

    public DFizSusChunk() {
        super(DFizAddon.CATEGORY, "dfiz-sus-chunk", "Finds chunks with signs of player activity from fully grown amethyst.");
    }

    private void rescan() {
        clearAll();
        queueLoadedChunks(false);
    }

    private void restartGeodes() {
        clearGeodes();
        if (detectMined.get()) queueLoadedChunks(false);
    }

    @Override
    public void onActivate() {
        clearAll();
        lastLevel = mc.world;
        queueLoadedChunks(false);
    }

    @Override
    public void onDeactivate() {
        clearAll();
        incoming.clear();
        pending.clear();
        lastLevel = null;
    }

    private void clearAll() {
        heat.clear();
        vetoes.clear();
        chunkScore.clear();
        chunkUngrown.clear();
        chunkHasPacket.clear();
        scannedChunks.clear();
        queue.clear();
        queued.clear();
        visible = new Long2IntOpenHashMap();
        visibleDirty = true;
        appliedRadius = spreadRadius.get();
        clearGeodes();
    }

    private void clearGeodes() {
        geodeSlices.clear();
        geodeWaiting.clear();
        geodeScanTicks.clear();
        geodeClusters = List.of();
        geodeClustersDirty = false;
        minedChunks = new LongOpenHashSet();
        minedAge = 0;
    }

    private static boolean isLoaded(ClientWorld world, int cx, int cz) {
        return world.getChunkManager().getWorldChunk(cx, cz) != null;
    }

    private void queueLoadedChunks(boolean onlyNew) {
        if (mc.world == null || mc.player == null) return;
        int range = mc.options.getViewDistance().getValue() + 1;
        ChunkPos center = mc.player.getChunkPos();
        for (int x = center.x - range; x <= center.x + range; x++) {
            for (int z = center.z - range; z <= center.z + range; z++) {
                if (!isLoaded(mc.world, x, z)) continue;
                long key = ChunkPos.toLong(x, z);
                if (onlyNew && (scannedChunks.contains(key) || queued.contains(key))) continue;
                enqueue(key);
            }
        }
    }

    private void enqueue(long key) {
        if (queued.add(key)) queue.enqueue(key);
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (event.packet instanceof ChunkDataS2CPacket packet) {
            incoming.add(ChunkPos.toLong(packet.getChunkX(), packet.getChunkZ()));
        }
    }

    private void drainIncoming(ClientWorld world) {
        Long next;
        while ((next = incoming.poll()) != null) pending.put(next.longValue(), PENDING_TTL);
        if (pending.isEmpty()) return;
        long[] keys = pending.keySet().toLongArray();
        for (long key : keys) {
            if (isLoaded(world, ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key))) {
                pending.remove(key);
                enqueue(key);
                continue;
            }
            int left = pending.get(key) - 1;
            if (left <= 0) pending.remove(key);
            else pending.put(key, left);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        ClientWorld world = mc.world;
        if (world != lastLevel) {
            clearAll();
            incoming.clear();
            pending.clear();
            lastLevel = world;
            queueLoadedChunks(false);
        }
        if (world == null) return;

        ticks++;

        if (spreadRadius.get() != appliedRadius) {
            clearAll();
            queueLoadedChunks(false);
        }

        drainIncoming(world);
        if (ticks % 20 == 0) queueLoadedChunks(true);

        long deadline = System.nanoTime() + SCAN_BUDGET_NANOS;
        while (!queue.isEmpty() && System.nanoTime() < deadline) {
            long key = queue.dequeueLong();
            queued.remove(key);
            int cx = ChunkPos.getPackedX(key);
            int cz = ChunkPos.getPackedZ(key);
            WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
            if (chunk == null) continue;
            updateChunk(key, chunk, world);
            scannedChunks.add(key);
            visibleDirty = true;
        }

        if (visibleDirty || ++visibleAge >= VISIBLE_REFRESH_TICKS) {
            visible = computeVisible(world);
            visibleDirty = false;
            visibleAge = 0;
        }

        if (detectMined.get() && ++minedAge >= 20) {
            minedChunks = computeMined(world);
            minedAge = 0;
        }
    }

    private void updateChunk(long key, WorldChunk chunk, ClientWorld world) {
        int radius = spreadRadius.get();
        int cx = ChunkPos.getPackedX(key);
        int cz = ChunkPos.getPackedZ(key);
        int prevHeat = chunkScore.getOrDefault(key, 0);
        boolean prevUngrown = chunkUngrown.getOrDefault(key, false);

        if (prevHeat > 0) forArea(cx, cz, radius, k -> subtract(heat, k, prevHeat));
        if (prevUngrown) forArea(cx, cz, radius, k -> subtract(vetoes, k, 1));

        chunkHasPacket.put(key, hasBlocksInRange(chunk, PACKET_MIN_Y, PACKET_MAX_Y));
        ChunkScore score = scoreChunk(chunk, world);
        chunkScore.put(key, score.selfHeat());
        chunkUngrown.put(key, score.hasUngrown());

        if (score.hasUngrown()) forArea(cx, cz, radius, k -> vetoes.addTo(k, 1));
        if (score.selfHeat() > 0) forArea(cx, cz, radius, k -> heat.addTo(k, score.selfHeat()));

        if (detectMined.get()) collectGeode(key, chunk, world);
    }

    private static void forArea(int cx, int cz, int radius, LongConsumer consumer) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                consumer.accept(ChunkPos.toLong(cx + dx, cz + dz));
            }
        }
    }

    private static void subtract(Long2IntOpenHashMap map, long key, int amount) {
        int value = map.get(key) - amount;
        if (value > 0) map.put(key, value);
        else map.remove(key);
    }

    private static boolean hasBlocksInRange(WorldChunk chunk, int minY, int maxY) {
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomSectionCoord();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;
            int baseY = (bottom + i) << 4;
            if (baseY + 15 < minY || baseY > maxY) continue;
            for (int y = 0; y < 16; y++) {
                int worldY = baseY + y;
                if (worldY < minY || worldY > maxY) continue;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (!section.getBlockState(x, y, z).isAir()) return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isDonutFolia() {
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        if (handler == null) return false;
        String brand = handler.getBrand();
        return brand != null && brand.contains("DonutFolia");
    }

    private ChunkScore scoreChunk(WorldChunk chunk, ClientWorld world) {
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomSectionCoord();
        ChunkPos chunkPos = chunk.getPos();
        boolean folia = isDonutFolia();
        int selfHeat = 0;
        int ungrown = 0;
        boolean anyBud = false;
        boolean anyCluster = false;
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;

            if (folia) {
                if (!anyBud && section.hasAny(DFizSusChunk::isBud)) anyBud = true;
                if (!anyCluster && section.hasAny(state -> state.isOf(Blocks.AMETHYST_CLUSTER))) anyCluster = true;
                continue;
            }

            if (!section.hasAny(DFizSusChunk::isAmethystGrowth)) continue;

            int baseY = (bottom + i) << 4;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isOf(Blocks.AMETHYST_CLUSTER)) {
                            selfHeat++;
                        } else if (isBud(state) && state.contains(Properties.FACING)) {
                            Direction facing = state.get(Properties.FACING);
                            pos.set(chunkPos.getStartX() + x, baseY + y, chunkPos.getStartZ() + z).move(facing.getOpposite());
                            if (world.getBlockState(pos).isOf(Blocks.BUDDING_AMETHYST)) ungrown++;
                            else selfHeat++;
                        }
                    }
                }
            }
        }

        if (folia) {
            if (anyBud) ungrown++;
            else if (anyCluster) selfHeat++;
        }

        return new ChunkScore(selfHeat, ungrown > 0);
    }

    private Long2IntOpenHashMap computeVisible(ClientWorld world) {
        Long2IntOpenHashMap candidates = new Long2IntOpenHashMap();
        for (Long2IntMap.Entry entry : heat.long2IntEntrySet()) {
            long key = entry.getLongKey();
            if (vetoes.containsKey(key)) continue;
            int cx = ChunkPos.getPackedX(key);
            int cz = ChunkPos.getPackedZ(key);
            if (!isLoaded(world, cx, cz)) continue;
            if (scannedNeighbours(cx, cz) < MIN_SCANNED_NEIGHBOURS) continue;
            candidates.put(key, entry.getIntValue());
        }

        int sens = sensitivity.get();
        int radius = appliedRadius;
        Long2IntOpenHashMap result = new Long2IntOpenHashMap();
        for (Long2IntMap.Entry entry : candidates.long2IntEntrySet()) {
            long key = entry.getLongKey();
            int value = entry.getIntValue();
            int threshold = chunkHasPacket.getOrDefault(key, true) ? sens : 1;
            if (value >= threshold && isPeak(candidates, key, value, radius)) result.put(key, value);
        }
        return result;
    }

    private static boolean isPeak(Long2IntOpenHashMap candidates, long key, int value, int radius) {
        int cx = ChunkPos.getPackedX(key);
        int cz = ChunkPos.getPackedZ(key);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (candidates.get(ChunkPos.toLong(cx + dx, cz + dz)) > value) return false;
            }
        }
        return true;
    }

    private int scannedNeighbours(int cx, int cz) {
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (scannedChunks.contains(ChunkPos.toLong(cx + dx, cz + dz))) count++;
            }
        }
        return count;
    }

    private static int waitBit(int dx, int dz) {
        return 1 << ((dx + 1) * 3 + dz + 1);
    }

    private void collectGeode(long key, WorldChunk chunk, ClientWorld world) {
        geodeScanTicks.put(key, ticks);
        if (geodeSlices.remove(key) != null) geodeClustersDirty = true;
        geodeWaiting.remove(key);

        int cx = ChunkPos.getPackedX(key);
        int cz = ChunkPos.getPackedZ(key);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                long neighbour = ChunkPos.toLong(cx + dx, cz + dz);
                if ((geodeWaiting.get(neighbour) & waitBit(-dx, -dz)) != 0) enqueue(neighbour);
            }
        }

        if (world.getRegistryKey() != World.OVERWORLD) return;

        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomSectionCoord();
        int originX = cx << 4;
        int originZ = cz << 4;
        LongArrayList lining = new LongArrayList();
        LongArrayList budding = new LongArrayList();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int waiting = 0;

        for (int i = 0; i < sections.length; i++) {
            int baseY = (bottom + i) << 4;
            if (baseY > GEODE_TOP_Y) break;
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;
            if (!section.hasAny(DFizSusChunk::isLining)) continue;

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!isLining(state)) continue;

                        int wx = originX + x;
                        int wy = baseY + y;
                        int wz = originZ + z;
                        boolean exposed = false;
                        int waitMask = 0;

                        for (Direction side : SIDES) {
                            int nx = wx + side.getOffsetX();
                            int ny = wy + side.getOffsetY();
                            int nz = wz + side.getOffsetZ();
                            if (world.isOutOfHeightLimit(ny)) continue;

                            int ncx = nx >> 4;
                            int ncz = nz >> 4;
                            boolean same = ncx == cx && ncz == cz;
                            if (!same && !isLoaded(world, ncx, ncz)) {
                                waitMask |= waitBit(ncx - cx, ncz - cz);
                                continue;
                            }

                            pos.set(nx, ny, nz);
                            BlockState neighbourState = same ? chunk.getBlockState(pos) : world.getBlockState(pos);
                            if (isOpen(neighbourState)) {
                                exposed = true;
                                break;
                            }
                        }

                        if (!exposed) {
                            waiting |= waitMask;
                            continue;
                        }

                        long packed = BlockPos.asLong(wx, wy, wz);
                        lining.add(packed);
                        if (state.isOf(Blocks.BUDDING_AMETHYST)) budding.add(packed);
                    }
                }
            }
        }

        if (waiting != 0) geodeWaiting.put(key, waiting);
        if (lining.isEmpty()) return;

        geodeSlices.put(key, new GeodeSlice(lining.toLongArray(), budding.toLongArray()));
        geodeClustersDirty = true;
    }

    private List<GeodeCluster> clusterGeodes() {
        Long2ObjectOpenHashMap<LongArrayList> cells = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet buddingSet = new LongOpenHashSet();

        for (GeodeSlice slice : geodeSlices.values()) {
            for (long packed : slice.lining()) {
                long cell = BlockPos.asLong(
                    BlockPos.unpackLongX(packed) >> GEODE_CELL_SHIFT,
                    BlockPos.unpackLongY(packed) >> GEODE_CELL_SHIFT,
                    BlockPos.unpackLongZ(packed) >> GEODE_CELL_SHIFT
                );
                LongArrayList list = cells.get(cell);
                if (list == null) {
                    list = new LongArrayList();
                    cells.put(cell, list);
                }
                list.add(packed);
            }
            for (long packed : slice.budding()) buddingSet.add(packed);
        }

        List<GeodeCluster> result = new ArrayList<>();
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue open = new LongArrayFIFOQueue();

        LongIterator cellIterator = cells.keySet().iterator();
        while (cellIterator.hasNext()) {
            long start = cellIterator.nextLong();
            if (!seen.add(start)) continue;

            LongArrayList members = new LongArrayList();
            open.enqueue(start);
            while (!open.isEmpty()) {
                long current = open.dequeueLong();
                members.addAll((LongList) cells.get(current));
                int x = BlockPos.unpackLongX(current);
                int y = BlockPos.unpackLongY(current);
                int z = BlockPos.unpackLongZ(current);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long next = BlockPos.asLong(x + dx, y + dy, z + dz);
                            if (cells.containsKey(next) && seen.add(next)) open.enqueue(next);
                        }
                    }
                }
            }
            result.add(toCluster(members.toLongArray(), buddingSet));
        }
        return result;
    }

    private static GeodeCluster toCluster(long[] lining, LongOpenHashSet buddingSet) {
        int budding = 0;
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        long sumX = 0;
        long sumZ = 0;
        for (long packed : lining) {
            if (buddingSet.contains(packed)) budding++;
            int x = BlockPos.unpackLongX(packed);
            int z = BlockPos.unpackLongZ(packed);
            minX = Math.min(minX, x);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxZ = Math.max(maxZ, z);
            sumX += x;
            sumZ += z;
        }
        int centerX = (int) Math.floorDiv(sumX, lining.length);
        int centerZ = (int) Math.floorDiv(sumZ, lining.length);
        return new GeodeCluster(lining, budding, minX, minZ, maxX, maxZ, ChunkPos.toLong(centerX >> 4, centerZ >> 4));
    }

    private boolean settled(ClientWorld world, GeodeCluster cluster) {
        for (int cx = (cluster.minX() - 1) >> 4; cx <= (cluster.maxX() + 1) >> 4; cx++) {
            for (int cz = (cluster.minZ() - 1) >> 4; cz <= (cluster.maxZ() + 1) >> 4; cz++) {
                long key = ChunkPos.toLong(cx, cz);
                if (!isLoaded(world, cx, cz) || !geodeScanTicks.containsKey(key)) return false;
                if (ticks - geodeScanTicks.get(key) < GEODE_SETTLE_TICKS) return false;
            }
        }
        return true;
    }

    private static boolean unlit(ClientWorld world, long[] lining) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        Long2IntOpenHashMap cache = new Long2IntOpenHashMap();
        for (long packed : lining) {
            int x = BlockPos.unpackLongX(packed);
            int y = BlockPos.unpackLongY(packed);
            int z = BlockPos.unpackLongZ(packed);
            for (Direction side : SIDES) {
                pos.set(x + side.getOffsetX(), y + side.getOffsetY(), z + side.getOffsetZ());
                if (isAmethystGrowth(world.getBlockState(pos))) return false;
                int light = world.getLightLevel(LightType.BLOCK, pos);
                if (light > 0 && sourceLight(world, pos, light, cache) != TORCH_LIGHT) return false;
            }
        }
        return true;
    }

    private static int sourceLight(ClientWorld world, BlockPos start, int level, Long2IntOpenHashMap cache) {
        LongArrayList visited = new LongArrayList();
        BlockPos.Mutable pos = start.mutableCopy();
        BlockPos.Mutable probe = new BlockPos.Mutable();
        int current = level;
        int result;
        while (true) {
            long key = pos.asLong();
            if (cache.containsKey(key)) {
                result = cache.get(key);
                break;
            }
            visited.add(key);
            Direction best = null;
            int bestLevel = current;
            for (Direction side : SIDES) {
                int light = world.getLightLevel(LightType.BLOCK, probe.set(pos, side));
                if (light > bestLevel) {
                    bestLevel = light;
                    best = side;
                }
            }
            if (best == null) {
                result = current;
                break;
            }
            pos.move(best);
            current = bestLevel;
        }
        for (int i = 0; i < visited.size(); i++) cache.put(visited.getLong(i), result);
        return result;
    }

    private LongOpenHashSet computeMined(ClientWorld world) {
        if (geodeSlices.keySet().removeIf((LongPredicate) k -> !isLoaded(world, ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k)))) {
            geodeClustersDirty = true;
        }
        geodeWaiting.keySet().removeIf((LongPredicate) k -> !isLoaded(world, ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k)));
        geodeScanTicks.keySet().removeIf((LongPredicate) k -> !isLoaded(world, ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k)));

        if (geodeClustersDirty) {
            geodeClusters = clusterGeodes();
            geodeClustersDirty = false;
        }

        LongOpenHashSet result = new LongOpenHashSet();
        for (GeodeCluster cluster : geodeClusters) {
            boolean big = cluster.budding() > 0
                ? cluster.budding() >= MIN_EXPOSED_BUDDING
                : cluster.lining().length >= MIN_EXPOSED_LINING;
            if (big && settled(world, cluster) && unlit(world, cluster.lining())) result.add(cluster.chunk());
        }
        return result;
    }

    private static boolean isLining(BlockState state) {
        return state.isOf(Blocks.AMETHYST_BLOCK) || state.isOf(Blocks.BUDDING_AMETHYST);
    }

    private static boolean isOpen(BlockState state) {
        return state.isAir() || !state.getFluidState().isEmpty() || isAmethystGrowth(state);
    }

    private static boolean isBud(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.SMALL_AMETHYST_BUD || block == Blocks.MEDIUM_AMETHYST_BUD || block == Blocks.LARGE_AMETHYST_BUD;
    }

    private static boolean isAmethystGrowth(BlockState state) {
        return state.isOf(Blocks.AMETHYST_CLUSTER) || isBud(state);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null) return;
        drawChunks(event, visible.keySet().toLongArray(), chunkColor.get());
        if (detectMined.get()) drawChunks(event, minedChunks.toLongArray(), minedColor.get());
    }

    private void drawChunks(Render3DEvent event, long[] chunks, SettingColor color) {
        if (chunks.length == 0) return;
        Color line = new Color(color.r, color.g, color.b, 255);
        double minY = mc.world.getBottomY();
        double maxY = mc.world.getTopY();
        for (long key : chunks) {
            double x = ChunkPos.getPackedX(key) << 4;
            double z = ChunkPos.getPackedZ(key) << 4;
            event.renderer.box(x, minY, z, x + 16, maxY, z + 16, color, color, shapeMode.get(), 0);
            if (tracers.get()) {
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, x + 8, 63, z + 8, line);
            }
        }
    }
}

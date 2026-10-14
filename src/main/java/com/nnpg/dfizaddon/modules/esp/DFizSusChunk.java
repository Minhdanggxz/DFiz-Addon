package com.nnpg.dfizaddon.modules.esp;

import com.nnpg.dfizaddon.DFizAddon;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
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
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkNibbleArray;
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

    private static final double MARKER_Y = 63.0;
    private static final int GLOW_CELLS_PER_POINT = 12;

    public enum Method {
        Classic("Classic"),
        Glow("ANBS+ Scan");

        private final String title;

        Method(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private record ChunkScore(int selfHeat, boolean hasUngrown) {}

    private record GeodeSlice(long[] lining, long[] budding) {}

    private record GeodeCluster(long[] lining, int budding, int minX, int minZ, int maxX, int maxZ, long chunk) {}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final EnumSetting<Method> method = sgGeneral.add(new EnumSetting.Builder<Method>()
        .name("method")
        .description("Classic reads the blocks, ANBS+ Scan reads the light data.")
        .defaultValue(Method.Classic)
        .onChanged(v -> rescan())
        .build()
    );

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
    private final GlowScan glow = new GlowScan();
    private final AreaCache visibleAreas = new AreaCache();
    private final Overlay chunkOverlay = new Overlay();
    private final Overlay minedOverlay = new Overlay();
    private final AreaCache minedAreas = new AreaCache();

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
        } else if (event.packet instanceof LightUpdateS2CPacket packet && method.get() == Method.Glow) {
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
        if (method.get() == Method.Glow) {
            return new ChunkScore(glow.scan(world, chunk) / GLOW_CELLS_PER_POINT, false);
        }
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
        return state.isAir() || (state.isOf(Blocks.WATER) && state.getFluidState().getLevel() == 8) || isAmethystGrowth(state);
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
        chunkOverlay.draw(event, visible.keySet(), chunkColor.get(), shapeMode.get());
        if (tracers.get()) drawTracers(event, visibleAreas.update(visible, visible.keySet()), chunkColor.get());
        if (detectMined.get()) {
            minedOverlay.draw(event, minedChunks, minedColor.get(), shapeMode.get());
            if (tracers.get()) drawTracers(event, minedAreas.update(minedChunks, minedChunks), minedColor.get());
        }
    }

    private static void drawTracers(Render3DEvent event, List<double[]> areas, SettingColor color) {
        Color tracer = new Color(color.r, color.g, color.b, 255);
        for (double[] a : areas) {
            event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, a[0], MARKER_Y, a[1], tracer);
        }
    }

    private static final class Overlay {
        private static final float GROW_SECONDS = 0.3f;
        private static final float SHRINK_SECONDS = 0.25f;
        private static final float SETTLE_SECONDS = 0.25f;
        private static final float MAX_WAIT_SECONDS = 1.0f;
        private static final float RESET_GAP_SECONDS = 0.5f;
        private static final float EDGE_SHADE = 0.77f;
        private static final double CORNER_RADIUS = 2.0;
        private static final int ARC_SEGMENTS = 4;

        private final Long2FloatOpenHashMap shown = new Long2FloatOpenHashMap();
        private final LongOpenHashSet pending = new LongOpenHashSet();
        private final LongOpenHashSet done = new LongOpenHashSet();
        private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        private final LongArrayList group = new LongArrayList();
        private long lastNanos;
        private long pendingSince;
        private long lastAdded;

        void draw(Render3DEvent event, LongSet set, SettingColor color, ShapeMode mode) {
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 0f : (now - lastNanos) / 1.0e9f;
            lastNanos = now;
            if (dt > RESET_GAP_SECONDS) {
                shown.clear();
                pending.clear();
            }
            collect(set, now);
            step(set, dt);
            if (shown.isEmpty()) return;

            Color fill = new Color(color.r, color.g, color.b, color.a);
            Color edge = new Color(
                (int) (color.r * EDGE_SHADE), (int) (color.g * EDGE_SHADE), (int) (color.b * EDGE_SHADE),
                Math.min(255, color.a * 3 / 2)
            );

            done.clear();
            long[] keys = shown.keySet().toLongArray();
            for (long key : keys) {
                if (done.add(key)) drawGroup(event, set, key, fill, edge, mode);
            }
        }

        private void collect(LongSet set, long now) {
            LongIterator it = set.iterator();
            while (it.hasNext()) {
                long key = it.nextLong();
                if (shown.containsKey(key) || pending.contains(key)) continue;
                if (pending.isEmpty()) pendingSince = now;
                pending.add(key);
                lastAdded = now;
            }

            LongIterator pit = pending.iterator();
            while (pit.hasNext()) {
                if (!set.contains(pit.nextLong())) pit.remove();
            }
            if (pending.isEmpty()) return;

            if ((now - lastAdded) / 1.0e9f < SETTLE_SECONDS && (now - pendingSince) / 1.0e9f < MAX_WAIT_SECONDS) return;

            LongIterator mit = pending.iterator();
            while (mit.hasNext()) shown.put(mit.nextLong(), 0f);
            pending.clear();
        }

        private void step(LongSet set, float dt) {
            long[] keys = shown.keySet().toLongArray();
            for (long key : keys) {
                float value = shown.get(key);
                if (set.contains(key)) {
                    shown.put(key, Math.min(1f, value + dt / GROW_SECONDS));
                } else {
                    value -= dt / SHRINK_SECONDS;
                    if (value <= 0f) shown.remove(key);
                    else shown.put(key, value);
                }
            }
        }

        private boolean joined(LongSet set, long key, float progress, boolean present) {
            return shown.containsKey(key) && shown.get(key) == progress && set.contains(key) == present;
        }

        private void visit(LongSet set, long key, float progress, boolean present) {
            if (joined(set, key, progress, present) && done.add(key)) queue.enqueue(key);
        }

        private static float easeOut(float t) {
            float u = 1f - t;
            return 1f - u * u * u;
        }

        private static Color fade(Color c, float f) {
            return new Color(c.r, c.g, c.b, Math.round(c.a * f));
        }

        private void drawGroup(Render3DEvent event, LongSet set, long start, Color fill, Color edge, ShapeMode mode) {
            float progress = shown.get(start);
            boolean present = set.contains(start);
            group.clear();
            queue.enqueue(start);
            double sumX = 0;
            double sumZ = 0;
            while (!queue.isEmpty()) {
                long current = queue.dequeueLong();
                group.add(current);
                int cx = ChunkPos.getPackedX(current);
                int cz = ChunkPos.getPackedZ(current);
                sumX += (cx << 4) + 8.0;
                sumZ += (cz << 4) + 8.0;
                visit(set, ChunkPos.toLong(cx, cz - 1), progress, present);
                visit(set, ChunkPos.toLong(cx, cz + 1), progress, present);
                visit(set, ChunkPos.toLong(cx - 1, cz), progress, present);
                visit(set, ChunkPos.toLong(cx + 1, cz), progress, present);
            }

            float e = easeOut(progress);
            if (e <= 0.001f) return;
            double mx = sumX / group.size();
            double mz = sumZ / group.size();
            Color tileFill = e >= 1f ? fill : fade(fill, e);
            Color tileEdge = e >= 1f ? edge : fade(edge, e);

            for (int i = 0; i < group.size(); i++) {
                long key = group.getLong(i);
                int cx = ChunkPos.getPackedX(key);
                int cz = ChunkPos.getPackedZ(key);
                double x0 = mx + ((cx << 4) - mx) * e;
                double z0 = mz + ((cz << 4) - mz) * e;
                double x1 = x0 + 16.0 * e;
                double z1 = z0 + 16.0 * e;
                boolean openN = !joined(set, ChunkPos.toLong(cx, cz - 1), progress, present);
                boolean openS = !joined(set, ChunkPos.toLong(cx, cz + 1), progress, present);
                boolean openW = !joined(set, ChunkPos.toLong(cx - 1, cz), progress, present);
                boolean openE = !joined(set, ChunkPos.toLong(cx + 1, cz), progress, present);
                tile(event, x0, z0, x1, z1, CORNER_RADIUS * e, openN, openS, openW, openE, tileFill, tileEdge, mode);
            }
        }

        private static void tile(Render3DEvent event, double x0, double z0, double x1, double z1, double r,
                                 boolean openN, boolean openS, boolean openW, boolean openE,
                                 Color fill, Color edge, ShapeMode mode) {
            double rNW = openN && openW ? r : 0;
            double rNE = openN && openE ? r : 0;
            double rSE = openS && openE ? r : 0;
            double rSW = openS && openW ? r : 0;
            boolean lines = mode.lines();

            List<double[]> points = new ArrayList<>(20);
            corner(points, event, x0 + rNW, z0 + rNW, rNW, 180, edge, lines);
            corner(points, event, x1 - rNE, z0 + rNE, rNE, 270, edge, lines);
            corner(points, event, x1 - rSE, z1 - rSE, rSE, 0, edge, lines);
            corner(points, event, x0 + rSW, z1 - rSW, rSW, 90, edge, lines);

            if (mode.sides() && points.size() >= 3) {
                double[] first = points.get(0);
                for (int i = 1; i < points.size() - 1; i++) {
                    double[] a = points.get(i);
                    double[] b = points.get(i + 1);
                    event.renderer.quad(
                        first[0], MARKER_Y, first[1],
                        a[0], MARKER_Y, a[1],
                        b[0], MARKER_Y, b[1],
                        b[0], MARKER_Y, b[1],
                        fill
                    );
                }
            }

            if (!lines) return;
            if (openN) edgeLine(event, x0 + rNW, z0, x1 - rNE, z0, edge);
            if (openS) edgeLine(event, x0 + rSW, z1, x1 - rSE, z1, edge);
            if (openW) edgeLine(event, x0, z0 + rNW, x0, z1 - rSW, edge);
            if (openE) edgeLine(event, x1, z0 + rNE, x1, z1 - rSE, edge);
        }

        private static void corner(List<double[]> points, Render3DEvent event, double cx, double cz, double radius,
                                   double startAngle, Color edge, boolean lines) {
            if (radius <= 0) {
                points.add(new double[]{cx, cz});
                return;
            }
            double[] previous = null;
            for (int i = 0; i <= ARC_SEGMENTS; i++) {
                double angle = Math.toRadians(startAngle + 90.0 * i / ARC_SEGMENTS);
                double[] point = new double[]{cx + Math.cos(angle) * radius, cz + Math.sin(angle) * radius};
                points.add(point);
                if (lines && previous != null) edgeLine(event, previous[0], previous[1], point[0], point[1], edge);
                previous = point;
            }
        }

        private static void edgeLine(Render3DEvent event, double x1, double z1, double x2, double z2, Color color) {
            event.renderer.line(x1, MARKER_Y, z1, x2, MARKER_Y, z2, color);
        }
    }

    private static final class AreaCache {
        private Object source;
        private int size = -1;
        private final List<double[]> areas = new ArrayList<>();

        List<double[]> update(Object src, LongSet set) {
            if (src == source && set.size() == size) return areas;
            source = src;
            size = set.size();
            areas.clear();
            LongOpenHashSet seen = new LongOpenHashSet();
            LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
            LongArrayList members = new LongArrayList();
            LongIterator it = set.iterator();
            while (it.hasNext()) {
                long start = it.nextLong();
                if (!seen.add(start)) continue;
                members.clear();
                queue.enqueue(start);
                double sumX = 0;
                double sumZ = 0;
                while (!queue.isEmpty()) {
                    long current = queue.dequeueLong();
                    members.add(current);
                    int cx = ChunkPos.getPackedX(current);
                    int cz = ChunkPos.getPackedZ(current);
                    sumX += (cx << 4) + 8.0;
                    sumZ += (cz << 4) + 8.0;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long next = ChunkPos.toLong(cx + dx, cz + dz);
                            if (set.contains(next) && seen.add(next)) queue.enqueue(next);
                        }
                    }
                }
                double mx = sumX / members.size();
                double mz = sumZ / members.size();
                if (!set.contains(ChunkPos.toLong((int) Math.floor(mx) >> 4, (int) Math.floor(mz) >> 4))) {
                    double best = Double.MAX_VALUE;
                    double bx = mx;
                    double bz = mz;
                    for (int i = 0; i < members.size(); i++) {
                        long m = members.getLong(i);
                        double px = (ChunkPos.getPackedX(m) << 4) + 8.0;
                        double pz = (ChunkPos.getPackedZ(m) << 4) + 8.0;
                        double d = (px - mx) * (px - mx) + (pz - mz) * (pz - mz);
                        if (d < best) {
                            best = d;
                            bx = px;
                            bz = pz;
                        }
                    }
                    mx = bx;
                    mz = bz;
                }
                areas.add(new double[]{mx, mz});
            }
            return areas;
        }
    }

    private static final class GlowScan {
        private static final int MIN_Y = -58;
        private static final int MAX_Y = 30;
        private static final int EDGE_LIGHT = 4;
        private static final int LOW_SECTION = -4;
        private static final int HIGH_SECTION = 1;
        private static final int FIRST_CACHED = -5;
        private static final int CACHED = 8;

        private final ChunkNibbleArray[] layers = new ChunkNibbleArray[72];
        private final BlockPos.Mutable cursor = new BlockPos.Mutable();
        private int chunkX;
        private int chunkZ;

        int scan(ClientWorld world, WorldChunk chunk) {
            chunkX = chunk.getPos().x;
            chunkZ = chunk.getPos().z;
            if (!cacheLight(world)) return 0;
            ChunkSection[] sections = chunk.getSectionArray();
            int originX = chunkX << 4;
            int originZ = chunkZ << 4;
            int count = 0;
            for (int sy = LOW_SECTION; sy <= HIGH_SECTION; sy++) {
                int index = chunk.sectionCoordToIndex(sy);
                if (index < 0 || index >= sections.length || sections[index] == null) continue;
                if (!nearLight(sy)) continue;
                ChunkSection section = sections[index];
                int yMin = Math.max(MIN_Y, sy << 4);
                int yMax = Math.min(MAX_Y, (sy << 4) + 15);
                for (int y = yMin; y <= yMax; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (!open(section.getBlockState(x, y & 15, z))) continue;
                            int wx = originX + x;
                            int wz = originZ + z;
                            if (lightAt(wx, y, wz) != 0) continue;
                            if (!edge(world, wx, y, wz)) continue;
                            count++;
                        }
                    }
                }
            }
            return count;
        }

        private boolean cacheLight(ClientWorld world) {
            var provider = world.getLightingProvider().get(LightType.BLOCK);
            if (provider == null) return false;
            boolean any = false;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int i = 0; i < CACHED; i++) {
                        ChunkNibbleArray array = provider.getLightSection(ChunkSectionPos.from(chunkX + dx, FIRST_CACHED + i, chunkZ + dz));
                        if (array != null && array.isUninitialized()) array = null;
                        layers[slot(dx, dz, i)] = array;
                        any |= array != null;
                    }
                }
            }
            return any;
        }

        private boolean nearLight(int sy) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int k = sy - 1; k <= sy + 1; k++) {
                        if (layers[slot(dx, dz, k - FIRST_CACHED)] != null) return true;
                    }
                }
            }
            return false;
        }

        private boolean edge(ClientWorld world, int x, int y, int z) {
            boolean openAtEdge = false;
            int max = 0;
            for (Direction side : SIDES) {
                int nx = x + side.getOffsetX();
                int ny = y + side.getOffsetY();
                int nz = z + side.getOffsetZ();
                int light = lightAt(nx, ny, nz);
                if (light > EDGE_LIGHT) return false;
                max = Math.max(max, light);
                if (light == EDGE_LIGHT && !openAtEdge && open(world.getBlockState(cursor.set(nx, ny, nz)))) openAtEdge = true;
            }
            return max == EDGE_LIGHT && openAtEdge;
        }

        private int lightAt(int x, int y, int z) {
            int layer = (y >> 4) - FIRST_CACHED;
            if (layer < 0 || layer >= CACHED) return 0;
            int dx = (x >> 4) - chunkX;
            int dz = (z >> 4) - chunkZ;
            if (dx < -1 || dx > 1 || dz < -1 || dz > 1) return 0;
            ChunkNibbleArray array = layers[slot(dx, dz, layer)];
            return array == null ? 0 : array.get(x & 15, y & 15, z & 15);
        }

        private static int slot(int dx, int dz, int layer) {
            return ((dx + 1) * 3 + dz + 1) * CACHED + layer;
        }

        private static boolean open(BlockState state) {
            return state.isAir() || state.isOf(Blocks.AMETHYST_CLUSTER);
        }
    }
}

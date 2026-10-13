package com.nnpg.dfizaddon.modules.esp;

import com.nnpg.dfizaddon.DFizAddon;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.ChunkLightingView;

import java.util.ArrayDeque;
import java.util.function.LongConsumer;

public class AmethystBypass extends Module {
    public enum Method {
        Bypass,
        Light,
        AnbsScan
    }

    private static final long BUDGET_NANOS = 2_000_000L;
    private static final int RESCAN_TICKS = 20;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Method> method = sgGeneral.add(new EnumSetting.Builder<Method>()
        .name("method")
        .description("Bypass reads leaked chunk palettes. Light looks for bud light next to geode blocks. AnbsScan looks for dark cells at the edge of hidden light.")
        .defaultValue(Method.Bypass)
        .onChanged(m -> restart())
        .build());

    private final Setting<Integer> minCells = sgGeneral.add(new IntSetting.Builder()
        .name("min-cells")
        .description("Minimum glow cells in a chunk before it is shown.")
        .defaultValue(12)
        .range(1, 100)
        .sliderRange(1, 100)
        .visible(() -> method.get() == Method.AnbsScan)
        .build());

    private final Setting<Boolean> tracer = sgRender.add(new BoolSetting.Builder()
        .name("tracer")
        .description("Draw a line to every hit.")
        .defaultValue(false)
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the boxes are drawn.")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Box fill color.")
        .defaultValue(new SettingColor(200, 130, 255, 40))
        .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Box and tracer color.")
        .defaultValue(new SettingColor(200, 130, 255, 255))
        .build());

    private final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
    private final LongOpenHashSet found = new LongOpenHashSet();
    private final LongOpenHashSet bypassScanned = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<BlockPos> bypassMarkers = new Long2ObjectOpenHashMap<>();
    private final GeodeGlowScan glow = new GeodeGlowScan();
    private final LongArrayList glowCells = new LongArrayList();
    private volatile long[] hits = new long[0];
    private int tick;

    public AmethystBypass() {
        super(DFizAddon.CATEGORY, "amethyst-bypass", "Shows amethyst geodes hidden by anti-xray.");
    }

    @Override
    public void onActivate() {
        restart();
    }

    @Override
    public void onDeactivate() {
        restart();
    }

    private void restart() {
        queue.clear();
        found.clear();
        bypassScanned.clear();
        bypassMarkers.clear();
        hits = new long[0];
        tick = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        ClientWorld world = mc.world;
        if (world == null || mc.player == null) return;

        boolean bypass = method.get() == Method.Bypass;

        if (queue.isEmpty()) {
            if (++tick < RESCAN_TICKS) return;
            tick = 0;

            ChunkPos center = mc.player.getChunkPos();
            int radius = mc.options.getViewDistance().getValue();

            if (bypass) {
                LongIterator it = bypassScanned.iterator();
                while (it.hasNext()) {
                    long k = it.nextLong();
                    if (!inRange(k, center, radius) || !isLoaded(world, ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k))) it.remove();
                }

                LongIterator mit = bypassMarkers.keySet().iterator();
                while (mit.hasNext()) {
                    long k = mit.nextLong();
                    if (!bypassScanned.contains(k)) mit.remove();
                }

                for (int x = center.x - radius; x <= center.x + radius; x++) {
                    for (int z = center.z - radius; z <= center.z + radius; z++) {
                        long key = ChunkPos.toLong(x, z);
                        if (isLoaded(world, x, z) && !bypassScanned.contains(key)) queue.add(new ChunkPos(x, z));
                    }
                }
            } else {
                found.clear();
                for (int x = center.x - radius; x <= center.x + radius; x++) {
                    for (int z = center.z - radius; z <= center.z + radius; z++) {
                        if (isLoaded(world, x, z)) queue.add(new ChunkPos(x, z));
                    }
                }
            }
        }

        long deadline = System.nanoTime() + BUDGET_NANOS;
        while (!queue.isEmpty() && System.nanoTime() < deadline) {
            ChunkPos pos = queue.poll();
            if (!isLoaded(world, pos.x, pos.z)) continue;

            WorldChunk chunk = world.getChunk(pos.x, pos.z);
            if (bypass) {
                collectPaletteLeak(chunk, bypassMarkers);
                bypassScanned.add(pos.toLong());
            } else if (method.get() == Method.AnbsScan) {
                collectGlow(world, chunk);
            } else {
                collectHiddenBuds(world, chunk, found);
            }
        }

        if (queue.isEmpty()) {
            hits = bypass ? markersToLongArray(bypassMarkers) : found.toLongArray();
        }
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (method.get() != Method.Bypass) return;

        if (event.packet instanceof ChunkDataS2CPacket p) {
            int cx = p.getChunkX();
            int cz = p.getChunkZ();
            mc.execute(() -> dirtyBypassChunk(cx, cz));
        } else if (event.packet instanceof BlockUpdateS2CPacket p) {
            int cx = p.getPos().getX() >> 4;
            int cz = p.getPos().getZ() >> 4;
            mc.execute(() -> dirtyBypassChunk(cx, cz));
        } else if (event.packet instanceof ChunkDeltaUpdateS2CPacket p) {
            p.visitUpdates((pos, state) -> {
                int cx = pos.getX() >> 4;
                int cz = pos.getZ() >> 4;
                mc.execute(() -> dirtyBypassChunk(cx, cz));
            });
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        ClientWorld world = mc.world;
        long[] current = hits;
        if (world == null || current.length == 0) return;

        boolean drawTracer = tracer.get();

        for (long packed : current) {
            BlockPos pos = BlockPos.fromLong(packed);
            if (!isLoaded(world, pos.getX() >> 4, pos.getZ() >> 4)) continue;

            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);

            if (drawTracer) {
                event.renderer.line(
                    RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    lineColor.get()
                );
            }
        }
    }

    private void dirtyBypassChunk(int chunkX, int chunkZ) {
        long key = ChunkPos.toLong(chunkX, chunkZ);
        if (bypassScanned.remove(key)) bypassMarkers.remove(key);
    }

    private static boolean isLoaded(ClientWorld world, int chunkX, int chunkZ) {
        return world.getChunkManager().isChunkLoaded(chunkX, chunkZ);
    }

    private static boolean inRange(long key, ChunkPos center, int radius) {
        int x = ChunkPos.getPackedX(key);
        int z = ChunkPos.getPackedZ(key);
        return Math.abs(x - center.x) <= radius && Math.abs(z - center.z) <= radius;
    }

    private static long[] markersToLongArray(Long2ObjectOpenHashMap<BlockPos> markers) {
        long[] out = new long[markers.size()];
        int i = 0;
        for (BlockPos marker : markers.values()) out[i++] = marker.asLong();
        return out;
    }

    private static boolean isBud(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.SMALL_AMETHYST_BUD || block == Blocks.MEDIUM_AMETHYST_BUD || block == Blocks.LARGE_AMETHYST_BUD;
    }

    private static boolean isGeode(BlockState state) {
        return state.isOf(Blocks.AMETHYST_BLOCK) || state.isOf(Blocks.BUDDING_AMETHYST);
    }

    private static boolean isBudLight(int level) {
        return level == 1 || level == 2 || level == 4 || level == 5;
    }

    private static void collectPaletteLeak(WorldChunk chunk, Long2ObjectOpenHashMap<BlockPos> out) {
        ChunkSection[] sections = chunk.getSectionArray();
        int minSectionY = chunk.getBottomSectionCoord();
        ChunkPos center = chunk.getPos();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null) continue;

            PalettedContainer<BlockState> states = section.getBlockStateContainer();
            if (!states.hasAny(AmethystBypass::isBud)) continue;

            int baseY = (minSectionY + i) * 16;
            BlockPos marker = pickMarker(chunk, section, baseY, center);
            out.put(center.toLong(), marker != null ? marker : new BlockPos(center.getStartX() + 8, baseY + 8, center.getStartZ() + 8));
            return;
        }
    }

    private static BlockPos pickMarker(WorldChunk chunk, ChunkSection section, int baseY, ChunkPos center) {
        BlockPos.Mutable neighbour = new BlockPos.Mutable();
        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;

        for (int y = 0; y < 16; y++) {
            int worldY = baseY + y;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (!section.getBlockState(x, y, z).isAir()) continue;

                    int worldX = center.getStartX() + x;
                    int worldZ = center.getStartZ() + z;
                    boolean touchesGeode = false;

                    for (Direction direction : Direction.values()) {
                        int nx = worldX + direction.getOffsetX();
                        int nz = worldZ + direction.getOffsetZ();
                        if ((nx >> 4) != center.x || (nz >> 4) != center.z) continue;

                        neighbour.set(nx, worldY + direction.getOffsetY(), nz);
                        Block block = chunk.getBlockState(neighbour).getBlock();
                        if (block == Blocks.AMETHYST_BLOCK || block == Blocks.BUDDING_AMETHYST) {
                            touchesGeode = true;
                            break;
                        }
                    }

                    if (!touchesGeode) continue;

                    int score = scatter(worldX, worldY, worldZ);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new BlockPos(worldX, worldY, worldZ);
                    }
                }
            }
        }

        return best;
    }

    private static int scatter(int x, int y, int z) {
        long h = (long) x * -7046029254386353131L ^ (long) y * -4417276706812531889L ^ (long) z * 1609587929392839161L;
        h ^= h >>> 29;
        h *= -4658895280553007687L;
        h ^= h >>> 32;
        return Math.floorMod(h, 100);
    }

    private static void collectHiddenBuds(ClientWorld world, WorldChunk chunk, LongOpenHashSet out) {
        ChunkSection[] sections = chunk.getSectionArray();
        int minSectionY = chunk.getBottomSectionCoord();
        ChunkPos center = chunk.getPos();
        BlockPos.Mutable cursor = new BlockPos.Mutable();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null) continue;
            if (!section.getBlockStateContainer().hasAny(AmethystBypass::isGeode)) continue;

            int baseY = (minSectionY + i) * 16;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (section.getBlockState(x, y, z).getLuminance() > 0) continue;

                        cursor.set(center.getStartX() + x, baseY + y, center.getStartZ() + z);
                        int light = world.getLightLevel(LightType.BLOCK, cursor);
                        if (isBudLight(light) && touchesGeode(world, cursor) && isLocalMaximum(world, cursor, light)) {
                            out.add(cursor.asLong());
                        }
                    }
                }
            }
        }
    }

    private void collectGlow(ClientWorld world, WorldChunk chunk) {
        glowCells.clear();
        int count = glow.scan(world, chunk, glowCells::add);
        if (count < minCells.get()) return;

        int limit = Math.min(glowCells.size(), 200);
        for (int i = 0; i < limit; i++) found.add(glowCells.getLong(i));
    }

    private static boolean touchesGeode(ClientWorld world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (isGeode(world.getBlockState(pos.offset(direction)))) return true;
        }
        return false;
    }

    private static boolean isLocalMaximum(ClientWorld world, BlockPos pos, int light) {
        for (Direction direction : Direction.values()) {
            if (world.getLightLevel(LightType.BLOCK, pos.offset(direction)) >= light) return false;
        }
        return true;
    }

    private static final class GeodeGlowScan {
        private static final int MIN_Y = -58;
        private static final int MAX_Y = 30;
        private static final int EDGE_LIGHT = 4;
        private static final int LOW_SECTION = -4;
        private static final int HIGH_SECTION = 1;
        private static final int FIRST_CACHED = -5;
        private static final int CACHED = 8;
        private static final Direction[] SIDES = Direction.values();

        private final ChunkNibbleArray[] layers = new ChunkNibbleArray[72];
        private final BlockPos.Mutable cursor = new BlockPos.Mutable();
        private int chunkX;
        private int chunkZ;

        int scan(ClientWorld world, WorldChunk chunk, LongConsumer out) {
            chunkX = chunk.getPos().x;
            chunkZ = chunk.getPos().z;
            if (!cacheLight(world)) return 0;

            ChunkSection[] sections = chunk.getSectionArray();
            int baseX = chunkX << 4;
            int baseZ = chunkZ << 4;
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

                            int wx = baseX + x;
                            int wz = baseZ + z;
                            if (lightAt(wx, y, wz) != 0) continue;
                            if (!edge(world, wx, y, wz)) continue;

                            count++;
                            if (out != null) out.accept(BlockPos.asLong(wx, y, wz));
                        }
                    }
                }
            }

            return count;
        }

        private boolean cacheLight(ClientWorld world) {
            ChunkLightingView view = world.getLightingProvider().get(LightType.BLOCK);
            boolean any = false;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int i = 0; i < CACHED; i++) {
                        ChunkNibbleArray nibble = view.getLightSection(ChunkSectionPos.from(chunkX + dx, FIRST_CACHED + i, chunkZ + dz));
                        if (nibble != null && nibble.isUninitialized()) nibble = null;
                        layers[slot(dx, dz, i)] = nibble;
                        any |= nibble != null;
                    }
                }
            }

            return any;
        }

        private boolean nearLight(int sectionY) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int k = sectionY - 1; k <= sectionY + 1; k++) {
                        if (layers[slot(dx, dz, k - FIRST_CACHED)] != null) return true;
                    }
                }
            }
            return false;
        }

        private boolean edge(ClientWorld world, int x, int y, int z) {
            boolean openNeighbour = false;
            int max = 0;

            for (Direction direction : SIDES) {
                int nx = x + direction.getOffsetX();
                int ny = y + direction.getOffsetY();
                int nz = z + direction.getOffsetZ();
                int level = lightAt(nx, ny, nz);
                if (level > EDGE_LIGHT) return false;

                max = Math.max(max, level);
                if (level == EDGE_LIGHT && !openNeighbour && open(world.getBlockState(cursor.set(nx, ny, nz)))) {
                    openNeighbour = true;
                }
            }

            return max == EDGE_LIGHT && openNeighbour;
        }

        private int lightAt(int x, int y, int z) {
            int index = (y >> 4) - FIRST_CACHED;
            if (index < 0 || index >= CACHED) return 0;

            ChunkNibbleArray nibble = layers[slot((x >> 4) - chunkX, (z >> 4) - chunkZ, index)];
            return nibble == null ? 0 : nibble.get(x & 15, y & 15, z & 15);
        }

        private static int slot(int dx, int dz, int index) {
            return ((dx + 1) * 3 + dz + 1) * 8 + index;
        }

        private static boolean open(BlockState state) {
            return state.isAir() || state.isOf(Blocks.AMETHYST_CLUSTER);
        }
    }
}

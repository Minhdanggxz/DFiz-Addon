package com.nnpg.dfizaddon.modules.esp;

import com.nnpg.dfizaddon.DFizAddon;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

public class DFizSusChunk extends Module {
    private static final int DIR_UP = 2;
    private static final int DIR_DOWN = 4;
    private static final int DIR_NORTH = 8;
    private static final int DIR_SOUTH = 16;
    private static final int DIR_WEST = 32;
    private static final int DIR_EAST = 64;
    private static final int DIR_SIDES = DIR_NORTH | DIR_SOUTH | DIR_WEST | DIR_EAST;

    private static final Color OUTLINE = new Color(128, 128, 128, 255);
    private static final Color EDGE = new Color(255, 255, 255, 255);
    private static final Color LABEL = new Color(255, 255, 255, 255);
    private static final double OUTLINE_STEP = 0.12;
    private static final double PLATE_Y = 63.0;

    private static final Predicate<BlockState> BUD = state -> isBud(state.getBlock());
    private static final Predicate<BlockState> CLUSTER = state -> state.isOf(Blocks.AMETHYST_CLUSTER);
    private static final Predicate<BlockState> AMETHYST_ANY = BUD.or(CLUSTER);
    private static final Predicate<BlockState> BEE_NEST = state -> state.isOf(Blocks.BEE_NEST);
    private static final Predicate<BlockState> DEEPSLATE = state -> state.isOf(Blocks.DEEPSLATE);

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> spreadRadius = sg.add(new IntSetting.Builder()
        .name("spread-radius")
        .description("How many chunks around a find also get heat (and are blocked by growing amethyst).")
        .defaultValue(4)
        .range(2, 16)
        .sliderRange(2, 16)
        .build()
    );

    private final Setting<Integer> sensitivity = sg.add(new IntSetting.Builder()
        .name("sensitivity")
        .description("Heat a chunk needs before it is marked.")
        .defaultValue(3)
        .range(1, 20)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> numbers = sg.add(new BoolSetting.Builder()
        .name("numbers")
        .description("Show the heat number on each marked chunk.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoAdjust = sg.add(new BoolSetting.Builder()
        .name("auto-adjust")
        .description("Of a group of marked chunks, only keep the hottest ones.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> amethyst = sg.add(new BoolSetting.Builder()
        .name("amethyst")
        .description("Count grown amethyst clusters. Growing buds next to budding amethyst block the area.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> beeNest = sg.add(new BoolSetting.Builder()
        .name("bee-nest")
        .description("Count bee nests that are full of honey.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotatedDeepslate = sg.add(new BoolSetting.Builder()
        .name("rotated-deepslate")
        .description("Highlight sideways deepslate that is fully enclosed (Y 0 to 60).")
        .defaultValue(true)
        .build()
    );

    private final Map<ChunkPos, Integer> heat = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> vetoes = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> chunkScore = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Boolean> chunkUngrown = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Set<BlockPos>> chunkRotated = new ConcurrentHashMap<>();
    private final Set<BlockPos> rotatedPositions = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> scannedChunks = ConcurrentHashMap.newKeySet();
    private ExecutorService scanner;

    private record ChunkScore(int selfHeat, boolean hasUngrown, Set<BlockPos> rotated) {
    }

    public DFizSusChunk() {
        super(DFizAddon.CATEGORY, "dfiz-sus-chunk", "Finds sus chunks from a heat map of amethyst, bee nests and rotated deepslate.");
    }

    @Override
    public void onActivate() {
        clearAll();

        scanner = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "dfiz-sus-chunk");
            thread.setDaemon(true);
            return thread;
        });

        if (mc.world == null || mc.player == null) return;

        int viewDistance = mc.options.getViewDistance().getValue();
        ChunkPos center = mc.player.getChunkPos();

        for (int cx = center.x - viewDistance; cx <= center.x + viewDistance; cx++) {
            for (int cz = center.z - viewDistance; cz <= center.z + viewDistance; cz++) {
                if (mc.world.getChunkManager().isChunkLoaded(cx, cz)) {
                    submitChunkScan(mc.world.getChunk(cx, cz));
                }
            }
        }
    }

    @Override
    public void onDeactivate() {
        if (scanner != null) scanner.shutdownNow();
        clearAll();
    }

    private void clearAll() {
        heat.clear();
        vetoes.clear();
        chunkScore.clear();
        chunkUngrown.clear();
        chunkRotated.clear();
        rotatedPositions.clear();
        scannedChunks.clear();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        clearAll();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        submitChunkScan(event.chunk());
    }

    private void submitChunkScan(WorldChunk chunk) {
        if (scanner == null || scanner.isShutdown() || chunk == null) return;

        ChunkPos cp = chunk.getPos();
        scanner.submit(() -> {
            try {
                if (mc.world == null) return;
                updateChunk(cp, chunk);
                scannedChunks.add(cp);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }

    private void updateChunk(ChunkPos cp, WorldChunk chunk) {
        int r = spreadRadius.get();
        int oldScore = chunkScore.getOrDefault(cp, 0);
        boolean oldUngrown = Boolean.TRUE.equals(chunkUngrown.get(cp));
        Set<BlockPos> oldRotated = chunkRotated.remove(cp);

        if (oldScore > 0) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    heat.compute(new ChunkPos(cp.x + dx, cp.z + dz), (key, value) -> {
                        int remaining = (value == null ? 0 : value) - oldScore;
                        return remaining <= 0 ? null : remaining;
                    });
                }
            }
        }

        if (oldUngrown) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) removeVeto(new ChunkPos(cp.x + dx, cp.z + dz));
            }
        }

        if (oldRotated != null && !oldRotated.isEmpty()) rotatedPositions.removeAll(oldRotated);

        ChunkScore score = scoreChunk(cp, chunk);
        chunkScore.put(cp, score.selfHeat());
        chunkUngrown.put(cp, score.hasUngrown());

        if (score.hasUngrown()) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) addVeto(new ChunkPos(cp.x + dx, cp.z + dz));
            }
        }

        if (score.selfHeat() > 0) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    heat.merge(new ChunkPos(cp.x + dx, cp.z + dz), score.selfHeat(), Integer::sum);
                }
            }
        }

        if (!score.rotated().isEmpty()) {
            chunkRotated.put(cp, score.rotated());
            rotatedPositions.addAll(score.rotated());
        }
    }

    private void addVeto(ChunkPos cp) {
        vetoes.merge(cp, 1, Integer::sum);
    }

    private void removeVeto(ChunkPos cp) {
        vetoes.compute(cp, (key, value) -> {
            if (value == null) return null;
            int remaining = value - 1;
            return remaining <= 0 ? null : remaining;
        });
    }

    private boolean isVetoed(ChunkPos cp) {
        return vetoes.containsKey(cp);
    }

    private ChunkScore scoreChunk(ChunkPos cp, WorldChunk chunk) {
        ClientWorld world = mc.world;
        Set<BlockPos> rotated = ConcurrentHashMap.newKeySet();
        if (world == null) return new ChunkScore(0, false, rotated);

        ChunkSection[] sections = chunk.getSectionArray();
        int bottomSection = chunk.getBottomSectionCoord();
        int baseX = cp.getStartX();
        int baseZ = cp.getStartZ();

        boolean amethystOn = amethyst.get();
        boolean beeOn = beeNest.get();
        boolean rotatedOn = rotatedDeepslate.get();
        boolean folia = amethystOn && isDonutFolia();

        int grown = 0;
        int ungrown = 0;
        int honeyFull = 0;
        int honeyOther = 0;
        boolean foliaBuds = false;
        boolean foliaClusters = false;

        for (int si = 0; si < sections.length; si++) {
            ChunkSection section = sections[si];
            if (section == null || section.isEmpty()) continue;

            int baseY = (bottomSection + si) << 4;

            if (folia) {
                if (!foliaBuds && section.hasAny(BUD)) foliaBuds = true;
                if (!foliaClusters && section.hasAny(CLUSTER)) foliaClusters = true;
            }

            boolean scanAmethyst = amethystOn && !folia && section.hasAny(AMETHYST_ANY);
            boolean scanBees = beeOn && section.hasAny(BEE_NEST);
            boolean scanDeepslate = rotatedOn && section.hasAny(DEEPSLATE);
            if (!scanAmethyst && !scanBees && !scanDeepslate) continue;

            for (int lx = 0; lx < 16; lx++) {
                for (int ly = 0; ly < 16; ly++) {
                    for (int lz = 0; lz < 16; lz++) {
                        BlockState state = section.getBlockState(lx, ly, lz);
                        int wx = baseX + lx;
                        int wy = baseY + ly;
                        int wz = baseZ + lz;

                        if (scanAmethyst) {
                            if (state.isOf(Blocks.AMETHYST_CLUSTER)) {
                                grown++;
                            } else if (isBud(state.getBlock()) && state.contains(Properties.FACING)) {
                                Direction facing = state.get(Properties.FACING);
                                BlockPos support = new BlockPos(wx, wy, wz).offset(facing.getOpposite());
                                if (world.getBlockState(support).isOf(Blocks.BUDDING_AMETHYST)) {
                                    ungrown++;
                                } else {
                                    grown++;
                                }
                            }
                        }

                        if (scanBees && state.isOf(Blocks.BEE_NEST) && state.contains(Properties.HONEY_LEVEL)) {
                            if (state.get(Properties.HONEY_LEVEL) == 5) {
                                honeyFull++;
                            } else {
                                honeyOther++;
                            }
                        }

                        if (scanDeepslate && state.isOf(Blocks.DEEPSLATE) && state.contains(Properties.AXIS)
                            && state.get(Properties.AXIS) != Direction.Axis.Y && wy >= 0 && wy <= 60) {
                            BlockPos pos = new BlockPos(wx, wy, wz);
                            boolean enclosed = true;

                            for (Direction dir : Direction.values()) {
                                if (world.getBlockState(pos.offset(dir)).isAir()) {
                                    enclosed = false;
                                    break;
                                }
                            }

                            if (enclosed) rotated.add(pos);
                        }
                    }
                }
            }
        }

        if (folia) {
            if (foliaBuds) {
                ungrown++;
            } else if (foliaClusters) {
                grown++;
            }
        }

        int selfHeat = (amethystOn ? grown : 0) + (beeOn ? honeyFull : 0);
        int ungrownTotal = (amethystOn ? ungrown : 0) + (beeOn ? honeyOther : 0);

        return new ChunkScore(selfHeat, ungrownTotal > 0, rotated);
    }

    private static boolean isBud(Block block) {
        return block == Blocks.SMALL_AMETHYST_BUD || block == Blocks.MEDIUM_AMETHYST_BUD || block == Blocks.LARGE_AMETHYST_BUD;
    }

    private boolean isDonutFolia() {
        if (mc.getNetworkHandler() == null) return false;

        String brand = mc.getNetworkHandler().getBrand();
        return brand != null && brand.contains("DonutFolia");
    }

    private Set<ChunkPos> visible() {
        if (mc.world == null) return Set.of();

        int needed = sensitivity.get();
        Map<ChunkPos, Integer> candidates = new HashMap<>();

        for (Map.Entry<ChunkPos, Integer> entry : heat.entrySet()) {
            ChunkPos cp = entry.getKey();
            if (entry.getValue() < needed) continue;
            if (isVetoed(cp)) continue;
            if (!mc.world.getChunkManager().isChunkLoaded(cp.x, cp.z)) continue;

            int scannedNeighbours = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx != 0 || dz != 0) && scannedChunks.contains(new ChunkPos(cp.x + dx, cp.z + dz))) scannedNeighbours++;
                }
            }

            if (scannedNeighbours < 3) continue;
            candidates.put(cp, entry.getValue());
        }

        if (!autoAdjust.get()) return candidates.keySet();

        Set<ChunkPos> seen = new HashSet<>();
        Set<ChunkPos> peaks = new HashSet<>();

        for (ChunkPos start : candidates.keySet()) {
            if (!seen.add(start)) continue;

            List<ChunkPos> group = new ArrayList<>();
            Deque<ChunkPos> queue = new ArrayDeque<>();
            queue.add(start);

            while (!queue.isEmpty()) {
                ChunkPos current = queue.poll();
                group.add(current);

                ChunkPos[] around = {
                    new ChunkPos(current.x + 1, current.z),
                    new ChunkPos(current.x - 1, current.z),
                    new ChunkPos(current.x, current.z + 1),
                    new ChunkPos(current.x, current.z - 1)
                };

                for (ChunkPos next : around) {
                    if (candidates.containsKey(next) && seen.add(next)) queue.add(next);
                }
            }

            int max = candidates.get(group.get(0));
            for (ChunkPos member : group) max = Math.max(max, candidates.get(member));

            for (ChunkPos member : group) {
                if (candidates.get(member) == max) peaks.add(member);
            }
        }

        return peaks;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        ClientWorld world = mc.world;
        if (world == null) return;

        Color fill = new Color(255, 255, 255, 80);
        Set<ChunkPos> shown = visible();

        for (ChunkPos cp : shown) {
            int mask = 0;
            if (shown.contains(new ChunkPos(cp.x, cp.z - 1))) mask |= DIR_NORTH;
            if (shown.contains(new ChunkPos(cp.x, cp.z + 1))) mask |= DIR_SOUTH;
            if (shown.contains(new ChunkPos(cp.x - 1, cp.z))) mask |= DIR_WEST;
            if (shown.contains(new ChunkPos(cp.x + 1, cp.z))) mask |= DIR_EAST;

            double x1 = cp.getStartX();
            double z1 = cp.getStartZ();
            double x2 = x1 + 16.0;
            double z2 = z1 + 16.0;

            event.renderer.boxSides(x1, PLATE_Y, z1, x2, PLATE_Y + 0.1, z2, fill, mask | DIR_SIDES);
            event.renderer.boxSides(x1, PLATE_Y, z1, x2, PLATE_Y + 0.1, z2, EDGE, mask | DIR_UP | DIR_DOWN);
            event.renderer.boxLines(x1, PLATE_Y, z1, x2, PLATE_Y + 0.1, z2, OUTLINE, mask);
            event.renderer.boxLines(x1 + OUTLINE_STEP, PLATE_Y, z1 + OUTLINE_STEP, x2 - OUTLINE_STEP, PLATE_Y + 0.1, z2 - OUTLINE_STEP, OUTLINE, mask);
        }

        if (rotatedPositions.isEmpty()) return;

        Color cyan = new Color(0, 255, 255, 80);
        rotatedPositions.removeIf(pos -> !world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4));

        for (BlockPos pos : rotatedPositions) {
            event.renderer.box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0,
                cyan, cyan, ShapeMode.Sides, 0);
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!numbers.get() || mc.world == null || mc.player == null) return;

        TextRenderer text = TextRenderer.get();

        for (ChunkPos cp : visible()) {
            Integer value = heat.get(cp);
            if (value == null) continue;

            Vector3d pos = new Vector3d(cp.getStartX() + 8, 64.5, cp.getStartZ() + 8);
            if (!NametagUtils.to2D(pos, 2.0, true)) continue;

            String label = String.valueOf(value);

            NametagUtils.begin(pos);
            text.beginBig();
            text.render(label, -text.getWidth(label, true) / 2.0, -text.getHeight(true) / 2.0, LABEL, true);
            text.end();
            NametagUtils.end();
        }
    }
}

package com.nnpg.dfizaddon.modules.esp;

import com.nnpg.dfizaddon.DFizAddon;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class BlockEntityDebug extends Module {
    public enum Detection {
        All,
        BelowZero,
        BelowMinus20,
        BelowMinus30
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Detection> detection = sgGeneral.add(new EnumSetting.Builder<Detection>()
        .name("detection")
        .description("Only show block entities below this height.")
        .defaultValue(Detection.All)
        .onChanged(d -> resync())
        .build());

    private final Setting<Boolean> blockFilter = sgGeneral.add(new BoolSetting.Builder()
        .name("block-filter")
        .description("Only show the blocks in the list.")
        .defaultValue(false)
        .onChanged(b -> resync())
        .build());

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks to show when the filter is on.")
        .defaultValue(defaultEnabled())
        .visible(() -> blockFilter.get())
        .onChanged(l -> {
            if (isActive() && blockFilter.get()) resync();
        })
        .build());

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Maximum distance to draw.")
        .defaultValue(256)
        .range(16, 1024)
        .sliderRange(16, 512)
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the boxes are drawn.")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<Integer> fillAlpha = sgRender.add(new IntSetting.Builder()
        .name("fill-alpha")
        .description("Box fill opacity.")
        .defaultValue(100)
        .range(0, 255)
        .sliderRange(0, 255)
        .build());

    private final Setting<Boolean> blockColors = sgRender.add(new BoolSetting.Builder()
        .name("block-colors")
        .description("Use a different color for each block type. Off uses the single color below.")
        .defaultValue(true)
        .build());

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color")
        .description("Color for blocks without their own color.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build());

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line to every block entity.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> customTracerColor = sgRender.add(new BoolSetting.Builder()
        .name("custom-tracer-color")
        .description("Use one color for all tracers.")
        .defaultValue(false)
        .visible(() -> tracers.get())
        .build());

    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Color of all tracers.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(() -> tracers.get() && customTracerColor.get())
        .build());

    private final Long2ObjectOpenHashMap<Block> layer = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet spawners = new LongOpenHashSet();
    private final Color sideScratch = new Color();
    private final Color lineScratch = new Color();
    private final Color tracerScratch = new Color();
    private int seenGeneration = -1;

    public BlockEntityDebug() {
        super(DFizAddon.CATEGORY, "block-entity-debug", "Renders every leaked block entity as ESP (always-on packet capture).");
        MeteorClient.EVENT_BUS.subscribe(new Tracker());
    }

    @Override
    public void onActivate() {
        Tracker.recordAdditions = true;
        resync();
    }

    @Override
    public void onDeactivate() {
        Tracker.recordAdditions = false;
        Tracker.ADDED.clear();
        layer.clear();
        spawners.clear();
    }

    private int colorOf(Block block) {
        if (!blockColors.get()) {
            SettingColor c = color.get();
            return (c.r << 16) | (c.g << 8) | c.b;
        }
        Integer rgb = COLORS.get(block);
        return rgb != null ? rgb : 0xFFFFFF;
    }

    private static void setRgb(Color target, int rgb, int alpha) {
        target.set((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, alpha);
    }

    private static final Map<Block, Integer> COLORS = buildColors();

    private static final Set<String> PICKED = Set.of(
        "bell", "campfire", "soul_campfire", "jigsaw", "structure_block", "command_block",
        "chain_command_block", "repeating_command_block", "end_portal", "end_gateway",
        "sculk_sensor", "calibrated_sculk_sensor", "sculk_catalyst", "sculk_shrieker",
        "suspicious_sand", "suspicious_gravel", "trial_spawner", "vault",
        "chiseled_bookshelf", "daylight_detector"
    );

    private static Map<Block, Integer> buildColors() {
        Map<Block, Integer> map = new HashMap<>();
        for (Block block : Registries.BLOCK) {
            if (block.getDefaultState().isAir()) continue;
            int rgb = rgbFor(Registries.BLOCK.getId(block).getPath());
            if (rgb != -1) map.put(block, rgb);
        }
        return map;
    }

    private static List<Block> defaultEnabled() {
        List<Block> list = new ArrayList<>();
        for (Block block : Registries.BLOCK) {
            if (block.getDefaultState().isAir()) continue;
            String path = Registries.BLOCK.getId(block).getPath();
            if (rgbFor(path) != -1 || picked(path)) list.add(block);
        }
        return list;
    }

    private static boolean picked(String path) {
        return path.contains("sign") || path.endsWith("_bed") || path.endsWith("_banner")
            || path.endsWith("_skull") || path.endsWith("_head") || PICKED.contains(path);
    }

    private static int rgbFor(String path) {
        if (path.endsWith("copper_chest")) return 10246912;
        if (path.endsWith("shulker_box")) return 8781982;
        return switch (path) {
            case "chest" -> 10246912;
            case "trapped_chest" -> 13775410;
            case "ender_chest" -> 7667967;
            case "barrel" -> 6572850;
            case "hopper" -> 5263440;
            case "dispenser", "dropper" -> 7887420;
            case "brewing_stand" -> 7874760;
            case "furnace", "blast_furnace", "smoker" -> 6579300;
            case "spawner" -> 9076390;
            case "enchanting_table" -> 13107200;
            case "crafter" -> 3978400;
            case "moving_piston", "piston", "sticky_piston", "piston_head" -> 65280;
            case "lectern" -> 13806160;
            case "jukebox" -> 2017380;
            case "decorated_pot" -> 11822120;
            case "beehive", "bee_nest" -> 15779840;
            case "beacon" -> 61660;
            case "conduit" -> 46320;
            case "comparator" -> 13120050;
            default -> -1;
        };
    }

    private void resync() {
        if (!isActive()) return;

        layer.clear();
        spawners.clear();
        seenGeneration = Tracker.GENERATION.get();
        Tracker.ADDED.clear();

        for (Map.Entry<BlockPos, Block> entry : Tracker.FOUND.entrySet()) {
            accept(entry.getKey(), entry.getValue());
        }
    }

    private void accept(BlockPos pos, Block block) {
        if (!passesDetection(pos)) return;
        if (blockFilter.get() && block != Blocks.AIR && !blocks.get().contains(block)) return;

        layer.put(pos.asLong(), block);
        if (block == Blocks.SPAWNER) spawners.add(pos.asLong());
    }

    private boolean passesDetection(BlockPos pos) {
        return switch (detection.get()) {
            case BelowZero -> pos.getY() < 0;
            case BelowMinus20 -> pos.getY() < -20;
            case BelowMinus30 -> pos.getY() < -30;
            default -> true;
        };
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (Tracker.GENERATION.get() != seenGeneration) {
            resync();
            return;
        }

        BlockPos added;
        while ((added = Tracker.ADDED.poll()) != null) {
            Block block = Tracker.FOUND.get(added);
            if (block != null) accept(added, block);
        }
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof BlockUpdateS2CPacket packet)) return;
        if (!Tracker.FOUND.containsKey(packet.getPos())) return;

        BlockState state = packet.getState();
        if (state.isAir() || state.isOf(Blocks.STONE) || state.isOf(Blocks.DEEPSLATE)) event.cancel();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.gameRenderer == null) return;

        Vec3d cam = mc.gameRenderer.getCamera().getPos();
        double rangeValue = range.get();
        double rangeSq = rangeValue * rangeValue;
        boolean drawTracers = tracers.get();
        int alpha = fillAlpha.get();

        for (Long2ObjectMap.Entry<Block> entry : layer.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            int x = BlockPos.unpackLongX(key);
            int y = BlockPos.unpackLongY(key);
            int z = BlockPos.unpackLongZ(key);

            double dx = x + 0.5 - cam.x;
            double dy = y + 0.5 - cam.y;
            double dz = z + 0.5 - cam.z;
            if (dx * dx + dy * dy + dz * dz > rangeSq) continue;

            int rgb = colorOf(entry.getValue());
            setRgb(sideScratch, rgb, alpha);
            setRgb(lineScratch, rgb, 255);

            event.renderer.box(x, y, z, x + 1, y + 1, z + 1, sideScratch, lineScratch, shapeMode.get(), 0);

            if (drawTracers) {
                tracerScratch.set(customTracerColor.get() ? tracerColor.get() : lineScratch);
                event.renderer.line(
                    RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    x + 0.5, y + 0.5, z + 0.5,
                    tracerScratch
                );
            }
        }

        if (spawners.isEmpty()) return;

        int beamRgb = colorOf(Blocks.SPAWNER);
        setRgb(lineScratch, beamRgb, 255);
        setRgb(sideScratch, beamRgb, 0);

        LongOpenHashSet snapshot = spawners;
        for (long key : snapshot) {
            int x = BlockPos.unpackLongX(key);
            int y = BlockPos.unpackLongY(key);
            int z = BlockPos.unpackLongZ(key);

            double dx = x + 0.5 - cam.x;
            double dy = y - cam.y;
            double dz = z + 0.5 - cam.z;
            if (dx * dx + dy * dy + dz * dz > rangeSq) continue;

            event.renderer.box(x + 0.3, y, z + 0.3, x + 0.7, y + 257, z + 0.7, sideScratch, lineScratch, ShapeMode.Lines, 0);
        }
    }

    public static final class Tracker {
        private static final Map<BlockPos, Block> FOUND = new ConcurrentHashMap<>();
        private static final ConcurrentLinkedQueue<BlockPos> ADDED = new ConcurrentLinkedQueue<>();
        private static final AtomicInteger GENERATION = new AtomicInteger();
        private static volatile boolean recordAdditions;

        @EventHandler(priority = EventPriority.HIGHEST)
        private void onPacket(PacketEvent.Receive event) {
            ingest(event.packet);
        }

        private static void ingest(Packet<?> packet) {
            if (packet instanceof ChunkDataS2CPacket p) {
                try {
                    ChunkData data = p.getChunkData();
                    data.getBlockEntities(p.getChunkX(), p.getChunkZ()).accept((pos, type, nbt) -> {
                        if (type != null) put(pos, type);
                    });
                } catch (Throwable ignored) {
                }
            } else if (packet instanceof BlockEntityUpdateS2CPacket p) {
                put(p.getPos(), p.getBlockEntityType());
            } else if (packet instanceof GameJoinS2CPacket || packet instanceof PlayerRespawnS2CPacket) {
                clear();
            }
        }

        private static void clear() {
            FOUND.clear();
            ADDED.clear();
            GENERATION.incrementAndGet();
        }

        private static void put(BlockPos pos, BlockEntityType<?> type) {
            BlockPos key = pos.toImmutable();
            if (FOUND.putIfAbsent(key, blockFromType(type)) == null && recordAdditions) ADDED.add(key);
        }

        private static Block blockFromType(BlockEntityType<?> type) {
            Identifier id = Registries.BLOCK_ENTITY_TYPE.getId(type);
            if (id == null) return Blocks.AIR;

            Block special = switch (id.getPath()) {
                case "mob_spawner" -> Blocks.SPAWNER;
                case "piston" -> Blocks.MOVING_PISTON;
                case "sign" -> Blocks.OAK_SIGN;
                case "hanging_sign" -> Blocks.OAK_HANGING_SIGN;
                case "skull" -> Blocks.SKELETON_SKULL;
                case "banner" -> Blocks.WHITE_BANNER;
                case "bed" -> Blocks.RED_BED;
                case "brushable_block" -> Blocks.SUSPICIOUS_SAND;
                default -> null;
            };
            if (special != null) return special;

            return Registries.BLOCK.getOrEmpty(id).orElse(Blocks.AIR);
        }
    }
}

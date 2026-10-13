package com.nnpg.dfizaddon.modules.esp;

import com.nnpg.dfizaddon.DFizAddon;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

public class SpawnerNotifier extends Module {
    private static final SoundEvent SOUND = SoundEvent.of(Identifier.of("dfizaddon", "spawner_notify"));
    private static final int SCAN_TICKS = 20;
    private static final double RANGE = 256.0;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> sound = sgGeneral.add(new BoolSetting.Builder()
        .name("sound")
        .description("Play a sound when a new spawner is found.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> chat = sgGeneral.add(new BoolSetting.Builder()
        .name("chat")
        .description("Send a chat message when a new spawner is found.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlight spawners.")
        .defaultValue(true)
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the boxes are drawn.")
        .defaultValue(ShapeMode.Both)
        .visible(() -> render.get())
        .build());

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color")
        .description("Spawner color.")
        .defaultValue(new SettingColor(255, 85, 85, 220))
        .visible(() -> render.get())
        .build());

    private final LongOpenHashSet layer = new LongOpenHashSet();
    private final LongOpenHashSet notified = new LongOpenHashSet();
    private final LongOpenHashSet scratch = new LongOpenHashSet();
    private final Color sideScratch = new Color();
    private int tick;

    public SpawnerNotifier() {
        super(DFizAddon.CATEGORY, "spawner-notifier", "Scans chunks for mob spawners and highlights them.");
    }

    @Override
    public void onActivate() {
        layer.clear();
        notified.clear();
        tick = SCAN_TICKS;
    }

    @Override
    public void onDeactivate() {
        layer.clear();
        notified.clear();
    }

    public static void scan(ClientWorld world, ChunkPos center, int radius, LongOpenHashSet out) {
        out.clear();
        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(x, z);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof MobSpawnerBlockEntity) out.add(be.getPos().asLong());
                }
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        ClientWorld world = mc.world;
        if (world == null || mc.player == null) return;
        if (++tick < SCAN_TICKS) return;
        tick = 0;

        scan(world, mc.player.getChunkPos(), mc.options.getViewDistance().getValue(), scratch);

        layer.clear();
        layer.addAll(scratch);

        for (long key : scratch) {
            if (notified.add(key)) notifyFound(BlockPos.fromLong(key));
        }
    }

    private void notifyFound(BlockPos pos) {
        if (chat.get()) {
            String name = entityName(pos);
            long distance = Math.round(mc.player.getPos().distanceTo(Vec3d.ofCenter(pos)));
            if (name == null) {
                info("Unknown spawner found at %d %d %d (%dm)", pos.getX(), pos.getY(), pos.getZ(), distance);
            } else {
                info("%s spawner found at %d %d %d (%dm)", name, pos.getX(), pos.getY(), pos.getZ(), distance);
            }
        }

        if (sound.get() && mc.player != null && mc.world != null) {
            mc.getSoundManager().play(PositionedSoundInstance.master(SOUND, 1.0f, 1.0f));
        }
    }

    private String entityName(BlockPos pos) {
        if (mc.world == null) return null;
        if (!(mc.world.getBlockEntity(pos) instanceof MobSpawnerBlockEntity spawner)) return null;

        Entity entity = spawner.getLogic().getRenderedEntity(mc.world, pos);
        return entity == null ? null : entity.getType().getName().getString();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!render.get() || layer.isEmpty() || mc.gameRenderer == null) return;

        Vec3d cam = mc.gameRenderer.getCamera().getPos();
        double rangeSq = RANGE * RANGE;
        SettingColor base = color.get();
        sideScratch.set(base.r, base.g, base.b, 26);

        for (long key : layer) {
            int x = BlockPos.unpackLongX(key);
            int y = BlockPos.unpackLongY(key);
            int z = BlockPos.unpackLongZ(key);

            double dx = x + 0.5 - cam.x;
            double dy = y + 0.5 - cam.y;
            double dz = z + 0.5 - cam.z;
            if (dx * dx + dy * dy + dz * dz > rangeSq) continue;

            event.renderer.box(x, y, z, x + 1, y + 1, z + 1, sideScratch, base, shapeMode.get(), 0);
        }
    }
}

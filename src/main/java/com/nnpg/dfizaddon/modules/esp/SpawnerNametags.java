package com.nnpg.dfizaddon.modules.esp;

import com.nnpg.dfizaddon.DFizAddon;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SpawnerNametags extends Module {
    private static final int MAX_LABELS = 48;
    private static final int RING_SEGMENTS = 64;
    private static final double LABEL_HEIGHT = 1.2;
    private static final double FADE_SHARE = 0.125;
    private static final int SCAN_TICKS = 20;
    private static final int INFO_TTL = 40;
    private static final double[] RING_COS = new double[RING_SEGMENTS + 1];
    private static final double[] RING_SIN = new double[RING_SEGMENTS + 1];

    static {
        for (int i = 0; i <= RING_SEGMENTS; i++) {
            double a = Math.PI * 2.0 * i / RING_SEGMENTS;
            RING_COS[i] = Math.cos(a);
            RING_SIN[i] = Math.sin(a);
        }
    }

    private record Info(String mob, int range, int tick) {
    }

    private record Shown(BlockPos pos, double distance, String mob, int range, boolean inside) {
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> rangeSetting = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Maximum distance to show labels.")
        .defaultValue(64)
        .range(8, 256)
        .sliderRange(8, 256)
        .build());

    private final Setting<Boolean> activationRadius = sgGeneral.add(new BoolSetting.Builder()
        .name("activation-radius")
        .description("Show the spawner activation range as text and a ring.")
        .defaultValue(true)
        .build());

    private final Setting<SettingColor> accent = sgGeneral.add(new ColorSetting.Builder()
        .name("accent-color")
        .description("Ring color and highlight color.")
        .defaultValue(new SettingColor(120, 200, 255, 255))
        .build());

    private final LongOpenHashSet spawners = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<Info> infos = new Long2ObjectOpenHashMap<>();
    private final List<Shown> shown = new ArrayList<>(MAX_LABELS);
    private final Color ringColor = new Color();
    private final Color textColor = new Color();
    private final Color accentText = new Color();
    private int scanTick;
    private int age;

    public SpawnerNametags() {
        super(DFizAddon.CATEGORY, "spawner-nametags", "Shows each spawner's mob and activation range over the block.");
    }

    @Override
    public void onActivate() {
        spawners.clear();
        infos.clear();
        shown.clear();
        scanTick = SCAN_TICKS;
        age = 0;
    }

    @Override
    public void onDeactivate() {
        spawners.clear();
        infos.clear();
        shown.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        ClientWorld world = mc.world;
        if (world == null || mc.player == null) {
            shown.clear();
            return;
        }

        age++;

        if (++scanTick >= SCAN_TICKS) {
            scanTick = 0;
            SpawnerNotifier.scan(world, mc.player.getChunkPos(), mc.options.getViewDistance().getValue(), spawners);
            LongIterator it = infos.keySet().iterator();
            while (it.hasNext()) {
                if (!spawners.contains(it.nextLong())) it.remove();
            }
        }

        shown.clear();
        if (spawners.isEmpty()) return;

        Vec3d player = mc.player.getPos();
        double range = rangeSetting.get();
        double rangeSq = range * range;
        List<Shown> found = new ArrayList<>();

        for (long key : spawners) {
            BlockPos pos = BlockPos.fromLong(key);
            double distSq = player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (distSq > rangeSq) continue;

            Info info = infos.get(key);
            if (info == null || age - info.tick() > INFO_TTL) {
                info = readInfo(world, pos);
                if (info == null) continue;
                infos.put(key, info);
            }

            boolean inside = info.range() < 0 || distSq < (double) info.range() * info.range();
            found.add(new Shown(pos, Math.sqrt(distSq), info.mob(), info.range(), inside));
        }

        found.sort(Comparator.comparingDouble(Shown::distance));
        shown.addAll(found.size() > MAX_LABELS ? found.subList(0, MAX_LABELS) : found);
    }

    private Info readInfo(ClientWorld world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof MobSpawnerBlockEntity)) return null;

        NbtCompound nbt = be.createNbt(world.getRegistryManager());
        int range = nbt.contains("RequiredPlayerRange") ? nbt.getShort("RequiredPlayerRange") : 16;

        String mob = null;
        String id = nbt.getCompound("SpawnData").getCompound("entity").getString("id");
        if (!id.isEmpty()) mob = EntityType.get(id).map(t -> t.getName().getString()).orElse(null);

        return new Info(mob, range, age);
    }

    private float fade(double distance) {
        double range = rangeSetting.get();
        return (float) Math.max(0.0, Math.min(1.0, (range - distance) / (range * FADE_SHARE)));
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!activationRadius.get() || shown.isEmpty()) return;

        SettingColor base = accent.get();

        for (Shown s : shown) {
            if (s.range() <= 0) continue;

            float alpha = (s.inside() ? 0.8f : 0.4f) * fade(s.distance());
            int a = Math.round(alpha * 255.0f);
            if (a < 4) continue;

            ringColor.set(base.r, base.g, base.b, a);

            double cx = s.pos().getX() + 0.5;
            double cy = s.pos().getY() + 0.5;
            double cz = s.pos().getZ() + 0.5;
            double r = s.range();

            for (int i = 0; i < RING_SEGMENTS; i++) {
                event.renderer.line(
                    cx + RING_COS[i] * r, cy, cz + RING_SIN[i] * r,
                    cx + RING_COS[i + 1] * r, cy, cz + RING_SIN[i + 1] * r,
                    ringColor
                );
            }
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (shown.isEmpty() || mc.options.hudHidden) return;

        SettingColor base = accent.get();

        for (int i = shown.size() - 1; i >= 0; i--) {
            Shown s = shown.get(i);
            int a = Math.round(fade(s.distance()) * 255.0f);
            if (a < 4) continue;

            Vector3d vec = new Vector3d(s.pos().getX() + 0.5, s.pos().getY() + LABEL_HEIGHT, s.pos().getZ() + 0.5);
            if (!NametagUtils.to2D(vec, 1.0)) continue;

            textColor.set(255, 255, 255, a);
            accentText.set(base.r, base.g, base.b, a);

            String title = s.mob() == null ? "Spawner" : s.mob() + " Spawner";
            String distance = Math.round(s.distance()) + "m";
            boolean showRange = activationRadius.get() && s.range() > 0;
            String separator = "  ·  ";
            String rangeText = s.range() + "m range";

            NametagUtils.begin(vec);
            TextRenderer text = TextRenderer.get();
            text.beginBig();

            double lineHeight = text.getHeight();
            double titleWidth = text.getWidth(title);
            text.render(title, -titleWidth / 2.0, -lineHeight * 2.0 - 2.0, textColor, true);

            double width = text.getWidth(distance);
            if (showRange) width += text.getWidth(separator) + text.getWidth(rangeText);

            double x = -width / 2.0;
            double y = -lineHeight - 1.0;
            x = text.render(distance, x, y, textColor, true);
            if (showRange) {
                x = text.render(separator, x, y, textColor, true);
                text.render(rangeText, x, y, s.inside() ? accentText : textColor, true);
            }

            text.end();
            NametagUtils.end();
        }
    }
}

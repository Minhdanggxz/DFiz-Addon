package com.nnpg.dfizaddon.antivanish;

import com.mojang.brigadier.suggestion.Suggestion;
import com.nnpg.dfizaddon.DFizAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Formatting;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.util.Identifier;
import net.minecraft.sound.SoundEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.world.GameMode;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AntiVanish extends Module {
    private static final String PROBE_COMMAND = "minecraft:msg";
    private static final long SIGNAL_WINDOW_MS = 15000L;
    private static final long DETECTION_TTL_MS = 20000L;
    private static final long CRITICAL_COOLDOWN_MS = 12000L;
    private static final long ANNOUNCE_COOLDOWN_MS = 1500L;
    private static final long ANNOUNCE_WINDOW_MS = 4000L;
    private static final int MAX_ANNOUNCE_PER_WINDOW = 6;
    private static final int MAX_OBSERVATIONS_PER_TICK = 512;
    private static final int CRITICAL_SCORE = 35;
    private static final long SELF_PLACE_TTL_MS = 6000L;
    private static final long CONTAINER_SELF_GRACE_MS = 2500L;
    private static final long RECENT_MESSAGE_TTL_MS = 8000L;
    private static final long TRUSTED_LISTED_MS = 1500L;
    private static final double SENSOR_RANGE = 64.0;

    private final StaffRoster roster = new StaffRoster();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDisplay = settings.createGroup("Display");

    private final Setting<Boolean> completionProbe = sgGeneral.add(new BoolSetting.Builder()
            .name("completion-probe")
            .description("Asks the server for the /msg name list every few seconds to find staff that are hidden from tab but still targetable.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> showOverlay = sgDisplay.add(new BoolSetting.Builder()
            .name("show-overlay")
            .description("Shows a warning at the top of the screen when a staff member is watching you.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> chatNotify = sgDisplay.add(new BoolSetting.Builder()
            .name("chat-notify")
            .description("Prints detections in chat.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> showHud = sgDisplay.add(new BoolSetting.Builder()
            .name("show-hud")
            .description("Draws the active detections in the top left.")
            .defaultValue(true)
            .build()
    );

    private final ConcurrentLinkedQueue<Observation> observations = new ConcurrentLinkedQueue<>();
    private final Map<UUID, KnownPlayer> knownPlayers = new HashMap<>();
    private final Map<String, Detection> detections = new LinkedHashMap<>();
    private final Map<String, Long> signalCooldowns = new HashMap<>();
    private final Map<String, Long> announceCooldowns = new HashMap<>();
    private final Deque<Long> announceTimes = new ArrayDeque<>();
    private final Map<Long, Long> selfPlacedBlocks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> confirmedDepartures = new HashMap<>();
    private final Map<Long, Long> automatedMechanisms = new HashMap<>();
    private final Map<String, Deque<Long>> weakParticleBursts = new HashMap<>();
    private final Deque<Signal> signals = new ArrayDeque<>();
    private final Deque<ExplosionEvent> recentExplosions = new ArrayDeque<>();
    private final Deque<PendingVanish> pendingVanishes = new ArrayDeque<>();
    private final Map<String, HiddenSpell> hiddenSpells = new LinkedHashMap<>();
    private final Deque<RecentMessage> recentMessages = new ArrayDeque<>();
    private final Map<UUID, Long> listedSinceMs = new HashMap<>();
    private final Set<Integer> completionRequestIds = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingChimes = new HashSet<>();
    private final ShardTracker shard = new ShardTracker();

    private volatile List<String> pendingCompletionNames;
    private volatile long lastLocalActionMs;
    private boolean serverSendsLeaveMessages;
    private int nextCompletionId = 30000;
    private int tickCounter;
    private long lastContainerActivityMs;
    private long lastCriticalMs;
    private long criticalUntilMs;
    private String criticalWatcher = "";
    private UUID ghostId;
    private String overlayName = "";
    private long overlayUntilMs;
    private String ghostName;

    public AntiVanish() {
        super(DFizAddon.CATEGORY, "anti-vanish", "Tells you when a vanished staff member is watching you.");
    }

    @Override
    public String getInfoString() {
        long now = System.currentTimeMillis();
        int live = 0;
        for (Detection detection : detections.values()) {
            if (detection.expiresAt > now && detectionWorthShowing(detection)) live++;
        }
        return live == 0 ? null : Integer.toString(live);
    }

    @Override
    public void onActivate() {
        resetRuntime();
        refreshRoster();
        if (mc.player != null && mc.world != null) trackListedPlayers();
    }

    private void refreshRoster() {
        roster.setNames(AdminHud.sharedNames());
        roster.setStarsEnabled(!AdminHud.namesOnly());
    }

    @Override
    public void onDeactivate() {
        resetRuntime();
        roster.clearLearned();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
            resetRuntime();
            return;
        }

        if (shard.moved(mc)) resetRuntime();

        playPendingChime();

        tickCounter++;

        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) lastContainerActivityMs = System.currentTimeMillis();

        drainObservations();
        processPendingVanishes();
        processCompletionProbe();

        if (completionProbe.get() && tickCounter % 100 == 0) sendCompletionProbe();

        if (tickCounter % 10 == 0) {
            trackListedPlayers();
            reviewSpells();
        }

        if (tickCounter % 5 == 0) scanInvisiblePlayers();

        pruneState();
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!showHud.get() || mc.player == null) return;

        List<HudEntry> entries = hudSnapshot();
        if (entries.isEmpty()) return;

        double gs = mc.getWindow().getScaleFactor();
        TextRenderer text = TextRenderer.get();

        text.begin(0.9 * gs);

        double x = 8 * gs;
        double y = 8 * gs;
        double lineHeight = text.getHeight(true) + 3 * gs;

        text.render("ANTI VANISH", x, y, new Color(160, 164, 172, 255), true);
        y += lineHeight;

        for (HudEntry entry : entries) {
            boolean alert = "CRITICAL".equalsIgnoreCase(entry.name());
            Color tagColor = alert ? new Color(255, 85, 85, 255) : new Color(255, 170, 0, 255);
            String tag = hudTag(entry);

            text.render(tag, x, y, tagColor, true);
            text.render(hudValue(entry), x + text.getWidth(tag + "  ", true), y, new Color(255, 255, 255, 255), true);
            y += lineHeight;
        }

        text.end();
    }

    @EventHandler
    private void onRenderWatch(Render2DEvent event) {
        if (!showOverlay.get() || mc.player == null) return;

        long now = System.currentTimeMillis();
        if (now >= overlayUntilMs) return;

        int alpha = (int) Math.min(255L, (overlayUntilMs - now) * 255L / 1000L);
        double gs = mc.getWindow().getScaleFactor();
        TextRenderer text = TextRenderer.get();

        String title = "STAFF WATCHING YOU";
        String who = shortName(overlayName);

        text.begin(1.5 * gs);
        double titleWidth = text.getWidth(title, true);
        text.render(title, event.screenWidth / 2.0 - titleWidth / 2.0, 36 * gs, new Color(255, 70, 70, alpha), true);
        double titleHeight = text.getHeight(true);
        text.end();

        if (who.isBlank()) return;

        text.begin(1.1 * gs);
        double whoWidth = text.getWidth(who, true);
        text.render(who, event.screenWidth / 2.0 - whoWidth / 2.0, 36 * gs + titleHeight + 4 * gs, new Color(255, 255, 255, alpha), true);
        text.end();
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        handleOutbound(event.packet);
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (handleInbound(event.packet)) event.cancel();
    }

    private void handleOutbound(Packet<?> packet) {
        if (packet == null) return;

        if (packet instanceof PlayerInteractBlockC2SPacket useOn) {
            lastLocalActionMs = System.currentTimeMillis();

            BlockHitResult hitResult = useOn.getBlockHitResult();
            if (hitResult != null && hitResult.getBlockPos() != null) {
                long until = System.currentTimeMillis() + SELF_PLACE_TTL_MS;
                BlockPos hit = hitResult.getBlockPos();
                BlockPos adjacent = hit.offset(hitResult.getSide());

                selfPlacedBlocks.put(hit.asLong(), until);
                selfPlacedBlocks.put(adjacent.asLong(), until);

                String itemPath = "";
                if (mc.player != null && useOn.getHand() != null) {
                    Identifier itemId = Registries.ITEM.getId(mc.player.getStackInHand(useOn.getHand()).getItem());
                    itemPath = AntiVanishHeuristics.path(itemId == null ? "" : itemId.toString());
                }

                markSelfMultiBlockFootprint(hit, itemPath, selfPlacedBlocks, until);
                markSelfMultiBlockFootprint(adjacent, itemPath, selfPlacedBlocks, until);
            }
            return;
        }

        if (packet instanceof PlayerActionC2SPacket
                || packet instanceof PlayerInteractItemC2SPacket
                || packet instanceof PlayerInteractEntityC2SPacket
                || packet instanceof HandSwingC2SPacket) {
            lastLocalActionMs = System.currentTimeMillis();
        }
    }

    static void markSelfMultiBlockFootprint(BlockPos pos, String path, Map<Long, Long> targets, long until) {
        if (pos == null || targets == null) return;

        targets.put(pos.asLong(), until);

        String id = path == null ? "" : path;
        boolean vertical = (id.endsWith("_door") && !id.endsWith("trapdoor"))
                || id.contains("sunflower")
                || id.contains("lilac")
                || id.contains("rose_bush")
                || id.contains("peony")
                || id.contains("tall_grass")
                || id.contains("large_fern")
                || id.contains("pitcher_plant");

        if (vertical) {
            targets.put(pos.up().asLong(), until);
            targets.put(pos.down().asLong(), until);
        }

        if (id.endsWith("_bed")) {
            targets.put(pos.add(1, 0, 0).asLong(), until);
            targets.put(pos.add(-1, 0, 0).asLong(), until);
            targets.put(pos.add(0, 0, 1).asLong(), until);
            targets.put(pos.add(0, 0, -1).asLong(), until);
        }
    }

    private boolean handleInbound(Packet<?> packet) {
        if (packet instanceof BundleS2CPacket bundle) {
            for (Packet<?> sub : bundle.getPackets()) handleInbound(sub);
            return false;
        }

        if (packet instanceof PlayerRemoveS2CPacket remove) {
            if (remove.profileIds().size() < 4) {
                for (UUID id : remove.profileIds()) observations.offer(Observation.tabRemove(id));
            }
        } else if (packet instanceof PlayerListS2CPacket info && info.getActions().contains(PlayerListS2CPacket.Action.UPDATE_LISTED)) {
            int unlisted = 0;
            for (PlayerListS2CPacket.Entry entry : info.getEntries()) {
                if (entry == null || entry.listed()) continue;
                unlisted++;
            }

            if (unlisted > 0 && unlisted < 4) {
                for (PlayerListS2CPacket.Entry entry : info.getEntries()) {
                    if (entry == null || entry.listed()) continue;
                    observations.offer(Observation.tabHide(entry.profileId()));
                }
            }
        } else if (packet instanceof PlayerListS2CPacket info && info.getActions().contains(PlayerListS2CPacket.Action.UPDATE_GAME_MODE)) {
            queueGamemodeUpdates(info);
        } else if (packet instanceof GameMessageS2CPacket chat) {
            String text = chat.content() == null ? "" : chat.content().getString();
            if (!text.isBlank()) observations.offer(Observation.systemChat(text));

            String departedName = departedPlayerName(chat.content());
            if (!departedName.isBlank()) observations.offer(Observation.playerLeft(departedName));
        } else if (packet instanceof CommandSuggestionsS2CPacket suggestions && completionRequestIds.remove(suggestions.id())) {
            List<String> names = new ArrayList<>();
            for (Suggestion suggestion : suggestions.getSuggestions().getList()) {
                String text = suggestion.getText();
                if (text == null || text.isBlank()) continue;
                names.add(text.trim());
            }
            pendingCompletionNames = names;
            return true;
        } else if (packet instanceof EntityTrackerUpdateS2CPacket metadata) {
            observations.offer(Observation.entityMetadata(metadata.id()));
        } else if (packet instanceof ExplosionS2CPacket explosion) {
            observations.offer(Observation.explosion(new Vec3d(explosion.getX(), explosion.getY(), explosion.getZ()), explosion.getRadius()));
        } else if (packet instanceof PlaySoundFromEntityS2CPacket sound) {
            observations.offer(Observation.entitySound(sound.getEntityId(), soundId(sound.getSound().value().getId())));
        } else if (packet instanceof ParticleS2CPacket particles) {
            Identifier id = Registries.PARTICLE_TYPE.getId(particles.getParameters().getType());
            observations.offer(Observation.position(ObservationType.PARTICLE, particles.getX(), particles.getY(), particles.getZ(), id == null ? "" : id.toString()));
        } else if (packet instanceof PlaySoundS2CPacket sound) {
            observations.offer(Observation.position(ObservationType.POSITIONAL_SOUND, sound.getX(), sound.getY(), sound.getZ(), soundId(sound.getSound().value().getId())));
        }

        return false;
    }

    private List<HudEntry> hudSnapshot() {
        long now = System.currentTimeMillis();
        List<HudEntry> out = new ArrayList<>();

        if (now < criticalUntilMs) out.add(new HudEntry("CRITICAL", criticalWatcher));

        Set<String> seenRows = new HashSet<>();
        List<Detection> sorted = new ArrayList<>();
        for (Detection detection : detections.values()) {
            if (detection.expiresAt > now && detectionWorthShowing(detection)) sorted.add(detection);
        }

        sorted.sort(Comparator.<Detection>comparingInt(d -> d.score).reversed().thenComparing(d -> d.name, String.CASE_INSENSITIVE_ORDER));

        int added = 0;
        for (Detection detection : sorted) {
            if (added >= 4) break;

            HudEntry entry = new HudEntry(detection.name, detection.reason);
            if (seenRows.add(hudTag(entry) + "\u0000" + hudValue(entry))) {
                out.add(entry);
                added++;
            }
        }

        return out;
    }

    private static boolean detectionWorthShowing(Detection detection) {
        if (detection == null) return false;

        String name = detection.name == null ? "" : detection.name.trim();
        return !name.isBlank() && !"Unknown".equalsIgnoreCase(name) && !"You".equalsIgnoreCase(name) && !"CRITICAL".equalsIgnoreCase(name);
    }

    private static String hudTag(HudEntry entry) {
        if (entry == null) return "WATCH";
        if ("CRITICAL".equalsIgnoreCase(entry.name())) return "ALERT";

        String lower = entry.reason() == null ? "" : entry.reason().toLowerCase(Locale.ROOT);

        if (lower.startsWith("vanish event")) return "VANISH";
        if (lower.startsWith("invisible entity")) return "INVIS";
        if (lower.startsWith("suspicious sound")) return "SOUND";
        if (lower.startsWith("ghost particle")) return "PARTICLE";

        return "WATCH";
    }

    private static String hudValue(HudEntry entry) {
        if (entry == null) return "Staff";
        if ("CRITICAL".equalsIgnoreCase(entry.name())) return shortName(entry.reason());

        String name = entry.name() == null ? "" : entry.name().trim();
        if (name.isBlank() || "Unknown".equalsIgnoreCase(name) || "You".equalsIgnoreCase(name)) return "Staff";

        return shortName(name);
    }

    private static String shortName(String name) {
        String shown = name == null ? "" : name.trim();
        return shown.length() <= 16 ? shown : shown.substring(0, 16);
    }

    private void drainObservations() {
        Observation observation;
    

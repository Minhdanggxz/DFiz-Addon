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
            targets.put(pos.offset(1, 0, 0).asLong(), until);
            targets.put(pos.offset(-1, 0, 0).asLong(), until);
            targets.put(pos.offset(0, 0, 1).asLong(), until);
            targets.put(pos.offset(0, 0, -1).asLong(), until);
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
        } else if (packet instanceof CommandSuggestionsS2CPacket suggestions && completionRequestIds.remove(suggestions.getCompletionId())) {
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
        for (int i = 0; i < MAX_OBSERVATIONS_PER_TICK && (observation = observations.poll()) != null; i++) {
            processObservation(observation);
        }

        while (observations.size() > 4096) observations.poll();
    }

    private void processObservation(Observation observation) {
        switch (observation.type) {
            case TAB_REMOVE -> handleTabRemoval(observation.profileId);
            case TAB_HIDE -> handleTabHidden(observation.profileId);
            case PLAYER_LEFT -> {
                serverSendsLeaveMessages = true;
                confirmDeparture(observation.detail);
            }
            case SYSTEM_CHAT -> cacheRecentMessage(observation.detail);
            case ENTITY_METADATA -> inspectInvisibleEntity(observation.entityId);
            case POSITIONAL_SOUND -> inspectPositionalSound(observation);
            case ENTITY_SOUND -> inspectEntitySound(observation);
            case PARTICLE -> inspectParticle(observation);
            case EXPLOSION -> rememberExplosion(observation);
            case GAMEMODE -> announceGamemode(observation.profileId, observation.detail);
        }
    }

    private void trackListedPlayers() {
        if (mc.getNetworkHandler() == null) return;

        refreshRoster();

        long now = System.currentTimeMillis();

        for (PlayerListEntry info : mc.getNetworkHandler().getListedPlayerListEntries()) {
            if (info == null || info.getProfile() == null || info.getProfile().getId() == null) continue;

            UUID uuid = info.getProfile().getId();
            if (mc.player != null && mc.player.getUuid().equals(uuid)) continue;

            listedSinceMs.putIfAbsent(uuid, now);

            String name = info.getProfile().getName();
            if (name != null) roster.learnFromTab(name, info.getDisplayName());

            if (name != null && !knownPlayers.containsKey(uuid)) knownPlayers.put(uuid, new KnownPlayer(uuid, name));

            if (name == null || hiddenSpells.isEmpty()) continue;

            endSpell(name, false, 1000L);
        }

        if (listedSinceMs.size() > 1024 || knownPlayers.size() > 1024) {
            Set<UUID> connected = new HashSet<>();
            for (PlayerListEntry info : mc.getNetworkHandler().getPlayerList()) {
                if (info == null || info.getProfile() == null) continue;
                connected.add(info.getProfile().getId());
            }

            listedSinceMs.keySet().retainAll(connected);
            knownPlayers.keySet().retainAll(connected);
        }
    }

    private boolean trustedListed(UUID uuid) {
        Long since = uuid == null ? null : listedSinceMs.get(uuid);
        return since != null && System.currentTimeMillis() - since >= TRUSTED_LISTED_MS;
    }

    private boolean credibleSubject(UUID uuid, String name) {
        return uuid != null && AntiVanishText.isUsername(name) && roster.isStaffName(name) && trustedListed(uuid);
    }

    private void handleTabRemoval(UUID uuid) {
        if (uuid == null || mc.player.getUuid().equals(uuid)) return;

        String name = knownName(uuid);
        if (!credibleSubject(uuid, name) || !AntiVanishText.isPlausiblePlayerName(name)) return;

        pendingVanishes.removeIf(pending -> pending.uuid.equals(uuid));
        pendingVanishes.addLast(new PendingVanish(uuid, name, tickCounter + 20));
    }

    private void handleTabHidden(UUID uuid) {
        if (uuid == null || mc.player.getUuid().equals(uuid)) return;

        KnownPlayer known = knownPlayers.get(uuid);
        String name = known != null && known.name != null ? known.name : knownName(uuid);

        if (!credibleSubject(uuid, name) || !AntiVanishText.isPlausiblePlayerName(name)) return;
        if (recentMessageNames(name)) return;

        reportHidden(uuid, name, false, "Vanish Event: hidden from TAB", "hidden from TAB", 100);
    }

    private String knownName(UUID uuid) {
        KnownPlayer known = knownPlayers.get(uuid);
        if (known != null && known.name != null && !known.name.isBlank()) return known.name;

        if (mc.getNetworkHandler() != null) {
            PlayerListEntry info = mc.getNetworkHandler().getPlayerListEntry(uuid);
            if (info != null && info.getProfile() != null) return info.getProfile().getName();
        }

        return null;
    }

    private UUID knownUuid(String name) {
        for (KnownPlayer known : knownPlayers.values()) {
            if (known.name.equalsIgnoreCase(name)) return known.uuid;
        }
        return null;
    }

    private void confirmDeparture(String displayedName) {
        long now = System.currentTimeMillis();

        for (KnownPlayer known : knownPlayers.values()) {
            if (!AntiVanishText.containsPlayerName(displayedName, known.name)) continue;

            confirmedDepartures.put(known.uuid, now + 5000L);
            pendingVanishes.removeIf(pending -> pending.uuid.equals(known.uuid));
            hiddenSpells.remove(spellKey(known.name));

            Detection detection = detections.get(known.uuid.toString());
            if (detection != null && tabDepartureReason(detection.reason)) detections.remove(known.uuid.toString());

            signals.removeIf(signal -> signal.type == SignalType.VANISH && signal.subject.equalsIgnoreCase(known.name) && tabDepartureReason(signal.reason));
        }
    }

    private void processPendingVanishes() {
        while (!pendingVanishes.isEmpty() && pendingVanishes.peekFirst().dueTick <= tickCounter) {
            PendingVanish pending = pendingVanishes.removeFirst();

            if (mc.getNetworkHandler().getPlayerListEntry(pending.uuid) != null) continue;

            long now = System.currentTimeMillis();
            if (confirmedDepartures.getOrDefault(pending.uuid, 0L) > now) continue;

            if (recentMessageNames(pending.name)) {
                serverSendsLeaveMessages = true;
                continue;
            }

            PlayerEntity remaining = mc.world.getPlayerByUuid(pending.uuid);
            if (remaining != null && !remaining.isRemoved()) {
                reportHidden(pending.uuid, pending.name, false, "Vanish Event: entity remained", "off TAB, still nearby", 100);
                continue;
            }

            String reason = "Vanish Event: silent TAB disappearance";
            int score = silentTabRemovalScore(serverSendsLeaveMessages);

            if (serverSendsLeaveMessages) {
                reportHidden(pending.uuid, pending.name, false, reason, "left TAB silently", score);
                continue;
            }

            RegionProof proof = regionProof(pending.uuid);
            if (proof == null) continue;

            signals.addLast(new Signal(SignalType.VANISH, pending.name, proof, reason, score, now));
            evaluateCritical();
        }
    }

    static int silentTabRemovalScore(boolean serverSendsLeaveMessages) {
        return serverSendsLeaveMessages ? 100 : 30;
    }

    static boolean tabDepartureReason(String reason) {
        if (reason == null) return false;

        String lower = reason.toLowerCase(Locale.ROOT);
        return lower.startsWith("vanish event:") && (lower.contains("tab") || lower.contains("entity remained") || lower.contains("no leave packet"));
    }

    private void cacheRecentMessage(String text) {
        if (text == null || text.isBlank()) return;

        long now = System.currentTimeMillis();
        recentMessages.addLast(new RecentMessage(text, now));

        while (!recentMessages.isEmpty() && !(now - recentMessages.peekFirst().atMs <= RECENT_MESSAGE_TTL_MS && recentMessages.size() <= 64)) {
            recentMessages.removeFirst();
        }
    }

    private boolean recentMessageNames(String name) {
        if (name == null || name.isBlank()) return false;

        long now = System.currentTimeMillis();
        for (RecentMessage message : recentMessages) {
            if (now - message.atMs > RECENT_MESSAGE_TTL_MS) continue;
            if (AntiVanishText.looksLikeLeaveMessage(message.text, name)) return true;
        }

        return false;
    }

    private void sendCompletionProbe() {
        if (mc.getNetworkHandler() == null) return;

        int id = nextCompletionId++;
        if (nextCompletionId > 40000) nextCompletionId = 30000;

        completionRequestIds.add(id);
        while (completionRequestIds.size() > 8) completionRequestIds.remove(completionRequestIds.iterator().next());

        try {
            mc.getNetworkHandler().sendPacket(new RequestCommandCompletionsC2SPacket(id, PROBE_COMMAND + " "));
        } catch (Throwable ignored) {
        }
    }

    private void processCompletionProbe() {
        List<String> current = pendingCompletionNames;
        if (current == null) return;

        pendingCompletionNames = null;

        if (mc.getNetworkHandler() == null || mc.player == null) return;

        Set<String> tabNames = new HashSet<>();
        for (PlayerListEntry info : mc.getNetworkHandler().getPlayerList()) {
            if (info == null || info.getProfile() == null || info.getProfile().getName() == null) continue;
            tabNames.add(info.getProfile().getName().toLowerCase(Locale.ROOT));
        }

        String self = mc.player.getName().getString();
        List<String> hidden = new ArrayList<>();

        for (String name : current) {
            if (!roster.isStaffName(name)
                    || !AntiVanishText.isPlausiblePlayerName(name)
                    || name.equalsIgnoreCase(self)
                    || tabNames.contains(name.toLowerCase(Locale.ROOT))
                    || recentMessageNames(name)) continue;

            hidden.add(name);
        }

        if (hidden.isEmpty() || hidden.size() > 3) return;

        for (String name : hidden) {
            reportHidden(knownUuid(name), name, false, "Vanish Event: hidden but targetable", "off TAB, still targetable", 90);
        }
    }

    private static String departedPlayerName(Text component) {
        if (component == null || !(component.getContent() instanceof TranslatableTextContent translated)) return "";
        if (!"multiplayer.player.left".equals(translated.getKey())) return "";

        Object[] args = translated.getArgs();
        if (args.length == 0 || args[0] == null) return "";

        return args[0] instanceof Text name ? name.getString() : String.valueOf(args[0]);
    }

    private void reportHidden(UUID uuid, String name, boolean spectator, String reason, String detail, int score) {
        upsertDetection(detectionKey(uuid, name), name, reason, score, System.currentTimeMillis() + DETECTION_TTL_MS);

        HiddenSpell spell = hiddenSpells.get(spellKey(name));
        if (spell != null && spell.spectator == spectator) return;

        hiddenSpells.put(spellKey(name), new HiddenSpell(uuid, name, spectator, detail, regionProof(uuid), tickCounter));
    }

    private void reviewSpells() {
        Iterator<HiddenSpell> it = hiddenSpells.values().iterator();

        while (it.hasNext()) {
            HiddenSpell spell = it.next();

            if (spell.spectator && mc.getNetworkHandler().getPlayerListEntry(spell.uuid) == null) {
                it.remove();
                continue;
            }

            if (spell.watched || spell.sinceTick >= tickCounter) continue;

            RegionProof proof = regionProof(spell.uuid);
            if (proof == null && !spell.told) proof = spell.proof;

            if (proof != null) {
                spell.told = true;
                spell.watched = true;
                watchingYou(spell.name, proof);
                continue;
            }

            if (spell.told) continue;

            spell.told = true;
            news(spell.name, spell.how(), spell.detail);
        }
    }

    private boolean endSpell(String name, boolean spectator, long settleMs) {
        HiddenSpell spell = hiddenSpells.get(spellKey(name));
        if (spell == null || spell.spectator != spectator || System.currentTimeMillis() - spell.sinceMs < settleMs) return false;

        hiddenSpells.remove(spellKey(name));
        detections.remove(detectionKey(spell.uuid, spell.name));

        if (spell.told) news(spell.name, Text.literal("is ").append(Text.literal("visible again").formatted(Formatting.GREEN)), "");

        return true;
    }

    private RegionProof regionProof(UUID uuid) {
        if (uuid == null) return null;

        PlayerEntity body = mc.world.getPlayerByUuid(uuid);
        if (body != null && body != mc.player) return new RegionProof(uuid, Math.round(body.distanceTo(mc.player)));

        return AdminHud.isInYourRegion(uuid) ? new RegionProof(uuid, -1) : null;
    }

    private static String spellKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String detectionKey(UUID uuid, String name) {
        return uuid != null ? uuid.toString() : name;
    }

    private void scanInvisiblePlayers() {
        double rangeSq = sensorRangeSq();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == null || player == mc.player || !player.isInvisible() || player.squaredDistanceTo(mc.player) > rangeSq || !realPlayer(player)) continue;

            triggerSensor(SignalType.INVISIBLE, player, "Invisible Entity: metadata flag", 35, 5000L);
        }
    }

    private void inspectInvisibleEntity(int entityId) {
        Entity entity = mc.world.getEntityById(entityId);
        if (!(entity instanceof PlayerEntity player) || player == mc.player || !player.isInvisible()) return;
        if (player.squaredDistanceTo(mc.player) > sensorRangeSq()) return;
        if (!realPlayer(player)) return;

        triggerSensor(SignalType.INVISIBLE, player, "Invisible Entity: metadata flag", 35, 5000L);
    }

    private boolean realPlayer(PlayerEntity player) {
        return credibleSubject(player.getUuid(), profileName(player));
    }

    private static String profileName(PlayerEntity player) {
        return player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName();
    }

    private void inspectPositionalSound(Observation observation) {
        if (!AntiVanishHeuristics.suspiciousSound(observation.detail)) return;

        Vec3d source = observation.position();
        long now = System.currentTimeMillis();

        if (!nearPlayer(source)
                || hasVisibleCause(source)
                || isExplosionRelated(source)
                || isPoweredMechanism(source, observation.detail)
                || now - lastLocalActionMs < 1000L
                || recentlySelfPlaced(source)
                || villagerToggledDoor(observation.detail, source)
                || (selfContainerActive() && isContainerSignal(observation.detail))) return;

        triggerSensor(SignalType.SOUND, locatedSubject(source), "Suspicious Sound: " + shortId(observation.detail), 14, 3000L);
    }

    private void inspectEntitySound(Observation observation) {
        if (!AntiVanishHeuristics.suspiciousSound(observation.detail)) return;

        Entity entity = mc.world.getEntityById(observation.entityId);

        if (entity instanceof PlayerEntity player
                && player != mc.player
                && player.isInvisible()
                && player.squaredDistanceTo(mc.player) <= sensorRangeSq()
                && realPlayer(player)) {
            triggerSensor(SignalType.SOUND, player, "Suspicious Sound: invisible source", 16, 3000L);
        }
    }

    private void inspectParticle(Observation observation) {
        if (!AntiVanishHeuristics.suspiciousParticle(observation.detail)) return;

        Vec3d source = observation.position();
        long now = System.currentTimeMillis();

        if (!nearPlayer(source)
                || hasVisibleCause(source)
                || isExplosionRelated(source)
                || now - lastLocalActionMs < 900L
                || hasAmbientParticleSource(source, observation.detail)) return;

        String particle = shortId(observation.detail);

        if ((particle.equals("block") || particle.contains("smoke")) && nearSelf(source, 6.25)) return;
        if ((particle.equals("block") || particle.contains("smoke")) && !particleBurstReady(particle, now)) return;

        triggerSensor(SignalType.PARTICLE, locatedSubject(source), "Ghost Particle: " + shortId(observation.detail), 16, 3000L);
    }

    private boolean villagerToggledDoor(String blockId, Vec3d source) {
        String path = AntiVanishHeuristics.path(blockId);
        boolean doorLike = (path.contains("door") && !path.contains("trapdoor")) || path.contains("fence_gate");

        if (!doorLike || source == null || mc.world == null) return false;

        for (Entity entity : mc.world.getEntities()) {
            if (entity.getType() == EntityType.VILLAGER && entity.getPos().squaredDistanceTo(source) <= 9.0) return true;
        }

        return false;
    }

    private boolean recentlySelfPlaced(Vec3d source) {
        if (source == null) return false;

        Long until = selfPlacedBlocks.get(BlockPos.ofFloored(source).asLong());
        return until != null && until > System.currentTimeMillis();
    }

    private void rememberExplosion(Observation observation) {
        long now = System.currentTimeMillis();

        recentExplosions.addLast(new ExplosionEvent(observation.position(), Math.max(2.0, parseDouble(observation.detail, 4.0) + 4.0), now));
        while (recentExplosions.size() > 8) recentExplosions.removeFirst();
    }

    private boolean particleBurstReady(String particle, long now) {
        Deque<Long> burst = weakParticleBursts.computeIfAbsent(particle, ignored -> new ArrayDeque<>());
        burst.addLast(now);

        while (!burst.isEmpty() && now - burst.peekFirst() > 2000L) burst.removeFirst();

        if (burst.size() < 2) return false;

        burst.clear();
        return true;
    }

    private void triggerSensor(SignalType type, String where, String reason, int weight, long cooldownMs) {
        addSignal(type, where, null, reason, weight, cooldownMs);
    }

    private void triggerSensor(SignalType type, PlayerEntity player, String reason, int weight, long cooldownMs) {
        addSignal(type, profileName(player), regionProof(player.getUuid()), reason, weight, cooldownMs);
    }

    private void addSignal(SignalType type, String subject, RegionProof proof, String reason, int weight, long cooldownMs) {
        long now = System.currentTimeMillis();
        String cooldownKey = type.name() + "|" + subject.toLowerCase(Locale.ROOT);
        long last = signalCooldowns.getOrDefault(cooldownKey, 0L);

        if (now - last < cooldownMs) return;

        signalCooldowns.put(cooldownKey, now);
        signals.addLast(new Signal(type, subject, proof, reason, weight, now));
        upsertDetection("signal:" + cooldownKey, subject, reason, weight, now + DETECTION_TTL_MS);
        announceTrigger(cooldownKey, subject, proof != null, reason);
        evaluateCritical();
    }

    private void evaluateCritical() {
        long now = System.currentTimeMillis();
        pruneSignals(now);

        EnumMap<SignalType, Integer> strongest = new EnumMap<>(SignalType.class);
        Signal watcher = null;

        for (Signal signal : signals) {
            strongest.merge(signal.type, signal.weight, Math::max);
            if (signal.proof != null) watcher = signal;
        }

        int score = strongest.values().stream().mapToInt(Integer::intValue).sum();

        if (watcher == null || strongest.size() < 2 || score < CRITICAL_SCORE) return;
        if (now - lastCriticalMs < CRITICAL_COOLDOWN_MS) return;

        watchingYou(watcher.subject, watcher.proof);
    }

    public static boolean isWatching(String name) {
        AntiVanish module = Modules.get().get(AntiVanish.class);
        if (module == null || !module.isActive() || name == null) return false;

        long now = System.currentTimeMillis();

        if (module.criticalUntilMs > now && name.equalsIgnoreCase(module.criticalWatcher)) return true;

        for (Detection detection : module.detections.values()) {
            if (detection.expiresAt > now && name.equalsIgnoreCase(detection.name) && detectionWorthShowing(detection)) return true;
        }

        return false;
    }

    private void watchingYou(String name, RegionProof proof) {
        long now = System.currentTimeMillis();
        lastCriticalMs = now;
        criticalUntilMs = now + 7000L;
        criticalWatcher = name;

        pendingChimes.add(proof.id());
        ghostId = proof.id();
        ghostName = name;
    }

    private void playPendingChime() {
        if (pendingChimes.isEmpty()) return;

        boolean sirenCovers = pendingChimes.stream().allMatch(AdminHud::alarmedRecently);
        pendingChimes.clear();

        if (sirenCovers) return;

        StaffAlarm.stinger(1.0f);

        overlayName = ghostName == null ? "" : ghostName;
        overlayUntilMs = System.currentTimeMillis() + 7000L;
    }

    private void news(String name, Text what, String detail) {
        if (!chatNotify.get()) return;

        MutableText line = Text.literal(name).formatted(Formatting.AQUA).append(Text.literal(" ")).append(what);
        if (!detail.isEmpty()) line.append(Text.literal(" (" + detail + ")"));

        info(line);
    }

    private void queueGamemodeUpdates(PlayerListS2CPacket info) {
        if (info.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) return;

        for (PlayerListS2CPacket.Entry entry : info.getEntries()) {
            if (entry == null || entry.gameMode() == null || entry.profileId() == null) continue;

            observations.offer(Observation.gamemode(entry.profileId(), entry.gameMode().getName()));
        }
    }

    private void announceGamemode(UUID uuid, String gameMode) {
        if (uuid == null || (mc.player != null && mc.player.getUuid().equals(uuid))) return;

        KnownPlayer known = knownPlayers.get(uuid);
        if (known == null || !roster.isStaffName(known.name)) return;

        if (GameMode.SPECTATOR.getName().equals(gameMode)) {
            if (credibleSubject(uuid, known.name)) {
                reportHidden(uuid, known.name, true, "Vanish Event: switched to spectator", "", 100);
                return;
            }
        } else if (endSpell(known.name, true, 0L)) {
            return;
        }

        if (!chatNotify.get()) return;

        info(switchedTo(Text.literal(known.name).formatted(Formatting.AQUA).append(Text.literal(" ")), gameMode));
    }

    private static MutableText switchedTo(MutableText body, String mode) {
        if (mode == null) return body.append(Text.literal("switched game mode"));

        body.append(Text.literal("switched to "));

        if (GameMode.SPECTATOR.getName().equals(mode)) return body.append(Text.literal(mode).formatted(Formatting.RED));
        if (GameMode.CREATIVE.getName().equals(mode)) return body.append(Text.literal(mode).formatted(Formatting.YELLOW));

        return body.append(Text.literal(mode).formatted(Formatting.AQUA));
    }

    private void announceTrigger(String eventKey, String subject, boolean named, String reason) {
        long now = System.currentTimeMillis();

        Long last = announceCooldowns.get(eventKey);
        if (last != null && now - last < ANNOUNCE_COOLDOWN_MS) return;

        while (!announceTimes.isEmpty() && now - announceTimes.peekFirst() > ANNOUNCE_WINDOW_MS) announceTimes.removeFirst();

        if (announceTimes.size() >= MAX_ANNOUNCE_PER_WINDOW) return;

        announceCooldowns.put(eventKey, now);
        announceTimes.addLast(now);

        if (!chatNotify.get()) return;

        int colon = reason.indexOf(": ");
        MutableText line = colon < 0
                ? Text.literal(reason).formatted(Formatting.YELLOW)
                : Text.literal(reason.substring(0, colon)).formatted(Formatting.YELLOW).append(Text.literal(reason.substring(colon)));

        boolean located = subject != null && !subject.isBlank() && !"Unknown".equalsIgnoreCase(subject) && !"CRITICAL".equalsIgnoreCase(subject);

        if (located) {
            line.append(Text.literal(" ("));
            line.append(Text.literal(subject).formatted(named ? Formatting.AQUA : Formatting.GRAY));
            line.append(Text.literal(")"));
        }

        info(line);
    }

    private void upsertDetection(String key, String name, String reason, int score, long expiresAt) {
        Detection existing = detections.get(key);

        if (existing == null) {
            detections.put(key, new Detection(name, reason, score, expiresAt));
            return;
        }

        existing.name = name;
        existing.expiresAt = Math.max(existing.expiresAt, expiresAt);

        if (score >= existing.score) {
            existing.reason = reason;
            existing.score = score;
        }
    }

    private void pruneState() {
        long now = System.currentTimeMillis();

        detections.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        signalCooldowns.entrySet().removeIf(entry -> now - entry.getValue() > 60000L);
        announceCooldowns.entrySet().removeIf(entry -> now - entry.getValue() > 60000L);
        selfPlacedBlocks.entrySet().removeIf(entry -> entry.getValue() <= now);
        confirmedDepartures.entrySet().removeIf(entry -> entry.getValue() <= now);
        automatedMechanisms.entrySet().removeIf(entry -> entry.getValue() <= now);

        weakParticleBursts.values().removeIf(burst -> {
            while (!burst.isEmpty() && now - burst.peekFirst() > 2000L) burst.removeFirst();
            return burst.isEmpty();
        });

        while (!recentExplosions.isEmpty() && now - recentExplosions.peekFirst().timeMs > 3000L) recentExplosions.removeFirst();

        pruneSignals(now);
    }

    private void pruneSignals(long now) {
        while (!signals.isEmpty() && now - signals.peekFirst().timeMs > SIGNAL_WINDOW_MS) signals.removeFirst();
    }

    private boolean nearPlayer(Vec3d source) {
        return source != null && source.squaredDistanceTo(mc.player.getPos()) <= sensorRangeSq();
    }

    private boolean nearSelf(Vec3d source, double maxDistSq) {
        return source != null && mc.player != null && source.squaredDistanceTo(mc.player.getPos()) < maxDistSq;
    }

    private boolean selfContainerActive() {
        return System.currentTimeMillis() - lastContainerActivityMs < CONTAINER_SELF_GRACE_MS;
    }

    private static boolean isContainerSignal(String id) {
        String path = AntiVanishHeuristics.path(id);
        return path.contains("chest") || path.contains("barrel") || path.contains("shulker");
    }

    private String locatedSubject(Vec3d source) {
        if (source == null || mc.player == null) return "nearby";

        Vec3d me = mc.player.getPos();
        double dx = source.x - me.x;
        double dy = source.y - me.y;
        double dz = source.z - me.z;

        long dist = Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
        String dir = compass(dx, dz);

        return dir.isEmpty() ? dist + "m" : dist + "m " + dir;
    }

    private static String compass(double dx, double dz) {
        String ns = dz < -1.0 ? "N" : (dz > 1.0 ? "S" : "");
        String ew = dx > 1.0 ? "E" : (dx < -1.0 ? "W" : "");
        return ns + ew;
    }

    private boolean hasVisibleCause(Vec3d source) {
        if (source == null) return true;

        for (Entity entity : mc.world.getEntities()) {
            if (entity == mc.player) continue;

            boolean cause = entity instanceof PlayerEntity player
                    ? !player.isInvisible() && entity.getPos().squaredDistanceTo(source) <= 16.0
                    : entity instanceof ProjectileEntity && entity.getPos().squaredDistanceTo(source) <= 16.0;

            if (cause) return true;
        }

        return false;
    }

    private boolean isExplosionRelated(Vec3d source) {
        if (source == null) return false;

        long now = System.currentTimeMillis();
        while (!recentExplosions.isEmpty() && now - recentExplosions.peekFirst().timeMs > 3000L) recentExplosions.removeFirst();

        for (ExplosionEvent explosion : recentExplosions) {
            if (explosion.center.squaredDistanceTo(source) <= explosion.radius * explosion.radius) return true;
        }

        return false;
    }

    private boolean hasAmbientParticleSource(Vec3d source, String particleId) {
        if (source == null || !shortId(particleId).contains("smoke")) return false;

        BlockPos center = BlockPos.ofFloored(source);

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    Identifier id = Registries.BLOCK.getId(mc.world.getBlockState(pos).getBlock());
                    String path = shortId(id == null ? "" : id.toString());

                    if (path.contains("campfire")
                            || path.contains("furnace")
                            || path.contains("smoker")
                            || path.contains("torch")
                            || path.contains("fire")
                            || path.contains("candle")
                            || path.contains("respawn_anchor")) return true;
                }
            }
        }

        return false;
    }

    private boolean isPoweredMechanism(Vec3d source, String soundId) {
        String path = shortId(soundId);
        if (!path.contains("door") && !path.contains("trapdoor")) return false;

        BlockPos center = BlockPos.ofFloored(source);
        long now = System.currentTimeMillis();

        for (int dy = -1; dy <= 1; dy++) {
            BlockPos pos = center.offset(0, dy, 0);
            BlockState state = mc.world.getBlockState(pos);
            long key = pos.asLong();

            boolean powered = state.contains(Properties.POWERED) && state.get(Properties.POWERED);

            if (powered || mc.world.isReceivingRedstonePower(pos)) {
                automatedMechanisms.put(key, now + 5000L);
                return true;
            }

            if (automatedMechanisms.getOrDefault(key, 0L) > now) return true;
        }

        return false;
    }

    private double sensorRangeSq() {
        double r = SENSOR_RANGE;
        return r * r;
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String soundId(Identifier id) {
        return id == null ? "" : id.toString();
    }

    private static String shortId(String id) {
        return AntiVanishHeuristics.path(id);
    }

    private void resetRuntime() {
        observations.clear();
        knownPlayers.clear();
        detections.clear();
        signalCooldowns.clear();
        announceCooldowns.clear();
        announceTimes.clear();
        selfPlacedBlocks.clear();
        confirmedDepartures.clear();
        automatedMechanisms.clear();
        weakParticleBursts.clear();
        signals.clear();
        recentExplosions.clear();
        pendingVanishes.clear();
        hiddenSpells.clear();
        recentMessages.clear();
        listedSinceMs.clear();
        completionRequestIds.clear();
        pendingChimes.clear();
        shard.clear();

        pendingCompletionNames = null;
        lastLocalActionMs = 0L;
        serverSendsLeaveMessages = false;
        nextCompletionId = 30000;
        tickCounter = 0;
        lastContainerActivityMs = 0L;
        lastCriticalMs = 0L;
        criticalUntilMs = 0L;
        criticalWatcher = "";
        ghostId = null;
        ghostName = null;
        overlayName = "";
        overlayUntilMs = 0L;
    }

    private record Observation(ObservationType type, UUID profileId, int entityId, double x, double y, double z, String detail) {
        static Observation tabRemove(UUID id) {
            return new Observation(ObservationType.TAB_REMOVE, id, -1, 0.0, 0.0, 0.0, "");
        }

        static Observation tabHide(UUID id) {
            return new Observation(ObservationType.TAB_HIDE, id, -1, 0.0, 0.0, 0.0, "");
        }

        static Observation gamemode(UUID id, String mode) {
            return new Observation(ObservationType.GAMEMODE, id, -1, 0.0, 0.0, 0.0, mode);
        }

        static Observation playerLeft(String name) {
            return new Observation(ObservationType.PLAYER_LEFT, null, -1, 0.0, 0.0, 0.0, name);
        }

        static Observation systemChat(String text) {
            return new Observation(ObservationType.SYSTEM_CHAT, null, -1, 0.0, 0.0, 0.0, text);
        }

        static Observation entityMetadata(int id) {
            return new Observation(ObservationType.ENTITY_METADATA, null, id, 0.0, 0.0, 0.0, "");
        }

        static Observation explosion(Vec3d center, float radius) {
            return new Observation(ObservationType.EXPLOSION, null, -1, center.x, center.y, center.z, Float.toString(radius));
        }

        static Observation entitySound(int id, String sound) {
            return new Observation(ObservationType.ENTITY_SOUND, null, id, 0.0, 0.0, 0.0, sound);
        }

        static Observation position(ObservationType type, double x, double y, double z, String detail) {
            return new Observation(type, null, -1, x, y, z, detail);
        }

        Vec3d position() {
            return new Vec3d(x, y, z);
        }
    }

    private enum ObservationType {
        TAB_REMOVE,
        TAB_HIDE,
        PLAYER_LEFT,
        SYSTEM_CHAT,
        ENTITY_METADATA,
        POSITIONAL_SOUND,
        ENTITY_SOUND,
        PARTICLE,
        EXPLOSION,
        GAMEMODE
    }

    private static final class Detection {
        String name;
        String reason;
        int score;
        long expiresAt;

        Detection(String name, String reason, int score, long expiresAt) {
            this.name = name;
            this.reason = reason;
            this.score = score;
            this.expiresAt = expiresAt;
        }
    }

    private record HudEntry(String name, String reason) {
    }

    private record KnownPlayer(UUID uuid, String name) {
    }

    private record PendingVanish(UUID uuid, String name, int dueTick) {
    }

    private record RegionProof(UUID id, int distance) {
    }

    private record Signal(SignalType type, String subject, RegionProof proof, String reason, int weight, long timeMs) {
    }

    private enum SignalType {
        VANISH,
        INVISIBLE,
        PARTICLE,
        SOUND
    }

    private record RecentMessage(String text, long atMs) {
    }

    private record ExplosionEvent(Vec3d center, double radius, long timeMs) {
    }

    private static final class HiddenSpell {
        final UUID uuid;
        final String name;
        final boolean spectator;
        final String detail;
        final RegionProof proof;
        final int sinceTick;
        final long sinceMs = System.currentTimeMillis();
        boolean told;
        boolean watched;

        HiddenSpell(UUID uuid, String name, boolean spectator, String detail, RegionProof proof, int sinceTick) {
            this.uuid = uuid;
            this.name = name;
            this.spectator = spectator;
            this.detail = detail;
            this.proof = proof;
            this.sinceTick = sinceTick;
        }

        Text how() {
            return spectator
                    ? switchedTo(Text.literal(""), GameMode.SPECTATOR.getName())
                    : Text.literal("vanished").formatted(Formatting.RED);
        }
    }
}

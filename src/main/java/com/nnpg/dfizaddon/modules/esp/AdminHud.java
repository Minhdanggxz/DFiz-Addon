package com.nnpg.dfizaddon.antivanish;

import com.nnpg.dfizaddon.DFizAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Formatting;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.GameMode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

public class AdminHud extends Module {
    public enum Presence {
        ONLINE,
        VANISHED,
        SPECTATOR
    }

    public enum DetectionMethod {
        Auto,
        Names
    }

    private static final String PRIORITY = "DrDonutt";
    private static final Pattern ACCOUNT_NAME = Pattern.compile("[.*]?[A-Za-z0-9_]{1,16}");
    private static final int MAX_ROWS = 8;
    private static final int SCAN_TICKS = 10;
    private static final int GROUP_THRESHOLD = 3;
    private static final int ALERT_GATHER_TICKS = 5;
    private static final int SWITCH_ECHO_TICKS = 20;
    private static final int VANISH_CONFIRM_SCANS = 2;
    private static final long ALERT_HIGHLIGHT_MS = 4000L;
    private static final long ALERT_COOLDOWN_MS = 120000L;
    private static final long SERVER_MARK_MS = 600000L;
    private static final long SHARED_ALARM_MS = 5000L;
    private static final long UNTRACKED_PRUNE_MS = 10000L;
    private static final long NEARBY_GRACE_MS = 10000L;
    private static final long TEST_ROW_MS = 20000L;
    private static final float ALARM_SECONDS = 3.0f;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAlert = settings.createGroup("Alerts");
    private final SettingGroup sgHud = settings.createGroup("HUD");

    private final Setting<DetectionMethod> detectionMethod = sgGeneral.add(new EnumSetting.Builder<DetectionMethod>()
            .name("detection-method")
            .description("Auto finds staff by the star in front of their tab name. Names only watches the names you list.")
            .defaultValue(DetectionMethod.Auto)
            .build()
    );

    private final Setting<List<String>> watchNames = sgGeneral.add(new StringListSetting.Builder()
            .name("names")
            .description("Staff names. Detection by names uses this list, and anti vanish treats these players as staff.")
            .defaultValue(StaffRoster.DEFAULT_NAMES)
            .build()
    );

    private final Setting<Boolean> soundAlert = sgGeneral.add(new BoolSetting.Builder()
            .name("sound")
            .description("Plays a sound whenever a staff member joins, leaves or changes visibility.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> chatNotify = sgGeneral.add(new BoolSetting.Builder()
            .name("chat-notify")
            .description("Sends a chat line whenever a staff member joins, leaves, vanishes or goes spectator.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> regionAlert = sgAlert.add(new BoolSetting.Builder()
            .name("region-alert")
            .description("Marks staff as on your goliath when their game mode change reaches you or you see them nearby, then raises the alarm.")
            .defaultValue(true)
            .onChanged(value -> {
                if (!value) forgetShard();
            })
            .build()
    );

    private final Setting<Boolean> alarmSound = sgAlert.add(new BoolSetting.Builder()
            .name("alarm-sound")
            .description("Plays the elder guardian siren when a staff member is proven to be on your goliath.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> showPanel = sgHud.add(new BoolSetting.Builder()
            .name("show-panel")
            .description("Draws the admin list in the top right of the screen.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> scale = sgHud.add(new IntSetting.Builder()
            .name("scale")
            .description("Overall size of the text.")
            .defaultValue(100)
            .range(70, 150)
            .sliderRange(70, 150)
            .build()
    );

    private final Map<UUID, Staff> staff = new LinkedHashMap<>();
    private final Map<UUID, Sighting> sightings = new HashMap<>();
    private final Map<UUID, TestStaff> testStaff = new LinkedHashMap<>();
    private final Queue<GameModeSwitch> switches = new ConcurrentLinkedQueue<>();
    private final Map<UUID, String> pendingAlerts = new LinkedHashMap<>();
    private final StaffAlarm alarm = new StaffAlarm();
    private final ShardTracker shard = new ShardTracker();

    private List<Staff> sorted = List.of();
    private ClientPlayNetworkHandler connection;
    private boolean resort;
    private boolean primed;
    private String watchName = "";
    private int watchOthers;
    private long watchUntil;
    private int ticksToScan;
    private int settleTicks;
    private int alertGather;
    private int tickCount;

    public AdminHud() {
        super(DFizAddon.CATEGORY, "admin-hud", "Shows which staff members are online, vanished or watching you.");
    }

    @Override
    public void onActivate() {
        forget();
        connection = mc.getNetworkHandler();
        shard.clear();
        settleTicks = 0;
        ticksToScan = 0;
        primed = false;
    }

    @Override
    public void onDeactivate() {
        forget();
        testStaff.clear();
        connection = null;
        shard.clear();
    }

    @Override
    public String getInfoString() {
        return sorted.isEmpty() ? "none" : sorted.size() + " staff";
    }

    public static AdminHud instance() {
        return Modules.get().get(AdminHud.class);
    }

    public static List<String> sharedNames() {
        AdminHud module = instance();
        return module == null ? StaffRoster.DEFAULT_NAMES : module.watchNames.get();
    }

    public static boolean namesOnly() {
        AdminHud module = instance();
        return module != null && module.detectionMethod.get() == DetectionMethod.Names;
    }

    public static boolean isInYourRegion(UUID id) {
        AdminHud module = instance();
        if (module == null || !module.isActive() || id == null) return false;

        Sighting seen = module.sightings.get(id);
        return seen != null && seen.marked(System.currentTimeMillis());
    }

    public static boolean isPresent(UUID id, String name) {
        AdminHud module = instance();
        if (module == null || !module.isActive()) return false;

        long now = System.currentTimeMillis();

        if (id != null) {
            Sighting seen = module.sightings.get(id);
            if (seen != null && seen.marked(now)) return true;
        }

        if (name == null) return false;

        for (TestStaff test : module.testStaff.values()) {
            if (test.name.equalsIgnoreCase(name) && test.expiresAt > now) return true;
        }

        Staff real = module.findStaff(name);
        if (real != null) {
            Sighting seen = module.sightings.get(real.id);
            return seen != null && seen.marked(now);
        }

        return false;
    }

    public static boolean alarmedRecently(UUID id) {
        AdminHud module = instance();
        if (module == null || !module.isActive() || id == null) return false;

        Sighting seen = module.sightings.get(id);
        return seen != null && seen.alertedAt != 0L && System.currentTimeMillis() - seen.alertedAt < SHARED_ALARM_MS;
    }

    public void fireTest(UUID id, String name) {
        if (name == null || name.isBlank()) return;

        long now = System.currentTimeMillis();
        Staff real = findStaff(name);

        if (real != null) {
            markTest(real, now);
            if (alarmSound.get()) alarm.start(ALARM_SECONDS, 1.0f);
            return;
        }

        UUID key = id != null ? id : testId(name);

        testStaff.put(key, new TestStaff(key, name.trim(), now, now + ALERT_HIGHLIGHT_MS, now + TEST_ROW_MS));
        resort = true;

        if (alarmSound.get()) alarm.start(ALARM_SECONDS, 1.0f);
    }

    private Staff findStaff(String name) {
        for (Staff s : staff.values()) {
            if (s.name.equalsIgnoreCase(name.trim())) return s;
        }

        return null;
    }

    private void markTest(Staff real, long now) {
        Sighting seen = sightings.computeIfAbsent(real.id, key -> new Sighting());
        seen.testStart = now;
        seen.testUntil = now + TEST_ROW_MS;
        seen.highlightUntil = now + ALERT_HIGHLIGHT_MS;
        resort = true;
    }

    private static UUID testId(String name) {
        return UUID.nameUUIDFromBytes(("glazed-test:" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }

    private void forget() {
        staff.clear();
        sorted = List.of();
        forgetShard();
    }

    private void forgetShard() {
        switches.clear();
        sightings.clear();
        pendingAlerts.clear();
        alarm.stop();
        resort = true;
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!regionAlert.get()) return;

        Packet<?> packet = event.packet;

        if (packet instanceof PlayerListS2CPacket info) {
            queueSwitches(info);
        } else if (packet instanceof BundleS2CPacket bundle) {
            for (Packet<?> sub : bundle.getPackets()) {
                if (sub instanceof PlayerListS2CPacket info) queueSwitches(info);
            }
        }
    }

    private void queueSwitches(PlayerListS2CPacket info) {
        EnumSet<PlayerListS2CPacket.Action> actions = info.getActions();
        if (!actions.contains(PlayerListS2CPacket.Action.UPDATE_GAME_MODE) || actions.contains(PlayerListS2CPacket.Action.ADD_PLAYER)) return;

        for (PlayerListS2CPacket.Entry entry : info.getEntries()) {
            if (entry == null || entry.profileId() == null) continue;
            switches.offer(new GameModeSwitch(entry.profileId(), entry.gameMode()));
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        alarm.tick();

        ClientPlayNetworkHandler conn = mc.getNetworkHandler();

        if (conn == null || mc.player == null || mc.world == null) {
            if (!(connection == null && staff.isEmpty() && sightings.isEmpty())) forget();

            switches.clear();
            connection = null;
            shard.clear();
            primed = false;
            return;
        }

        boolean moved = shard.moved(mc);

        if (conn != connection) {
            connection = conn;
            forget();
            settleTicks = 60;
            primed = false;
        } else if (moved) {
            forgetShard();
        }

        if (settleTicks > 0) settleTicks--;

        tickCount++;
        long now = System.currentTimeMillis();

        if (regionAlert.get()) {
            drainSwitches(conn, mc.player.getUuid(), now);
            trackBodies(now);
        }

        if (--ticksToScan <= 0) {
            ticksToScan = SCAN_TICKS;

            Changes changes = scan(conn, now);

            if (primed) {
                announce(changes);
            } else if (settleTicks == 0) {
                primed = true;
            }
        }

        if (!pendingAlerts.isEmpty() && --alertGather <= 0) raise();

        testStaff.values().removeIf(test -> test.expiresAt <= now);

        if (resort) sortView(now);
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        double gs = mc.getWindow().getScaleFactor();
        double sc = scale.get() / 100.0;
        TextRenderer text = TextRenderer.get();

        if (now < watchUntil) {
            int alpha = (int) Math.min(255L, (watchUntil - now) * 255L / 1000L);
            String title = "STAFF WATCHING YOU";
            String who = watchOthers > 0 ? watchName + " +" + watchOthers : watchName;

            text.begin(1.5 * gs);
            double titleWidth = text.getWidth(title, true);
            double titleHeight = text.getHeight(true);
            text.render(title, event.screenWidth / 2.0 - titleWidth / 2.0, 36 * gs, new Color(255, 70, 70, alpha), true);
            text.end();

            if (!who.isBlank()) {
                text.begin(1.1 * gs);
                double whoWidth = text.getWidth(who, true);
                text.render(who, event.screenWidth / 2.0 - whoWidth / 2.0, 36 * gs + titleHeight + 4 * gs, new Color(255, 255, 255, alpha), true);
                text.end();
            }
        }

        if (!showPanel.get()) return;

        List<String[]> rows = new ArrayList<>();
        List<Color> colors = new ArrayList<>();

        for (TestStaff test : testStaff.values()) {
            rows.add(new String[]{test.name, "TEST"});
            colors.add(new Color(255, 170, 0, 255));
        }

        for (Staff s : sorted) {
            Sighting seen = sightings.get(s.id);
            boolean marked = seen != null && seen.marked(now);
            boolean nearby = seen != null && seen.nearby;

            String status;
            Color color;

            if (nearby) {
                status = "NEARBY " + Math.round(seen.distance) + "m";
                color = new Color(255, 85, 85, 255);
            } else if (marked) {
                status = "ON YOUR REGION";
                color = new Color(255, 170, 0, 255);
            } else if (s.presence == Presence.VANISHED) {
                status = "VANISHED";
                color = new Color(255, 85, 85, 255);
            } else if (s.presence == Presence.SPECTATOR) {
                status = "SPECTATOR";
                color = new Color(255, 221, 85, 255);
            } else {
                status = "ONLINE";
                color = new Color(85, 255, 85, 255);
            }

            rows.add(new String[]{s.name, status});
            colors.add(color);
        }

        int more = 0;
        if (rows.size() > MAX_ROWS) {
            more = rows.size() - (MAX_ROWS - 1);
            rows = new ArrayList<>(rows.subList(0, MAX_ROWS - 1));
        }

        text.begin(0.9 * gs * sc);

        double lineHeight = text.getHeight(true) + 3 * gs * sc;
        double width = text.getWidth("STAFF", true);

        for (String[] row : rows) width = Math.max(width, text.getWidth(row[0] + "  " + row[1], true));
        if (more > 0) width = Math.max(width, text.getWidth("+" + more + " more", true));

        double x = event.screenWidth - width - 8 * gs;
        double y = 8 * gs;

        text.render("STAFF", x, y, new Color(160, 164, 172, 255), true);
        y += lineHeight;

        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            text.render(row[0], x, y, new Color(255, 255, 255, 255), true);
            text.render(row[1], x + text.getWidth(row[0] + "  ", true), y, colors.get(i), true);
            y += lineHeight;
        }

        if (more > 0) text.render("+" + more + " more", x, y, new Color(160, 164, 172, 255), true);

        text.end();
    }

    private Changes scan(ClientPlayNetworkHandler conn, long now) {
        UUID self = mc.player.getUuid();
        Set<UUID> listed = new HashSet<>();

        for (PlayerListEntry info : conn.getListedPlayerListEntries()) listed.add(info.getProfile().getId());

        Set<String> names = new HashSet<>();
        if (detectionMethod.get() == DetectionMethod.Names) {
            for (String name : watchNames.get()) {
                if (name != null && !name.isBlank()) names.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }

        Changes changes = new Changes();
        Set<UUID> inTab = new HashSet<>();

        for (PlayerListEntry info : conn.getPlayerList()) {
            UUID id = info.getProfile().getId();
            String name = info.getProfile().getName();

            if (id == null || id.equals(self) || !isAccount(id, name) || !isStaffEntry(info, id, name, names)) continue;

            inTab.add(id);

            Presence presence = info.getGameMode() == GameMode.SPECTATOR
                    ? Presence.SPECTATOR
                    : (listed.contains(id) ? Presence.ONLINE : Presence.VANISHED);

            Staff tracked = staff.get(id);

            if (tracked == null) {
                tracked = new Staff(id);
                staff.put(id, tracked);
                tracked.update(name, presence);
                changes.joined.add(tracked);
                continue;
            }

            Presence before = tracked.presence;
            tracked.update(name, presence);
            tracked.offTabScans = 0;

            if (before != presence) changes.changed.add(tracked);
        }

        Iterator<Staff> it = staff.values().iterator();

        while (it.hasNext()) {
            Staff tracked = it.next();
            if (inTab.contains(tracked.id)) continue;

            if (mc.world.getPlayerByUuid(tracked.id) != null) {
                if (++tracked.offTabScans < VANISH_CONFIRM_SCANS || tracked.presence == Presence.VANISHED) continue;

                tracked.presence = Presence.VANISHED;
                changes.changed.add(tracked);
                continue;
            }

            it.remove();
            changes.left.add(tracked);
        }

        pruneSightings(now);
        sortView(now);

        return changes;
    }

    private boolean isStaffEntry(PlayerListEntry info, UUID id, String name, Set<String> names) {
        if (name.equalsIgnoreCase(PRIORITY) || staff.containsKey(id)) return true;

        if (detectionMethod.get() == DetectionMethod.Names) return names.contains(name.toLowerCase(Locale.ROOT));

        Text display = info.getDisplayName();
        return display != null && StaffRoster.hasLeadingStar(display.getString());
    }

    private static boolean isAccount(UUID id, String name) {
        return name != null && id.version() != 2 && ACCOUNT_NAME.matcher(name).matches();
    }

    private void sortView(long now) {
        List<Staff> view = new ArrayList<>(staff.values());

        view.sort(Comparator
                .comparingInt((Staff s) -> group(s, now))
                .thenComparingInt(s -> s.name.equalsIgnoreCase(PRIORITY) ? 0 : 1)
                .thenComparing(s -> s.name, String.CASE_INSENSITIVE_ORDER));

        sorted = List.copyOf(view);
        resort = false;
    }

    private int group(Staff s, long now) {
        Sighting seen = sightings.get(s.id);

        if (seen != null && seen.nearby) return 0;
        if (seen != null && seen.marked(now)) return 1;

        return s.presence != Presence.ONLINE ? 2 : 3;
    }

    private void drainSwitches(ClientPlayNetworkHandler conn, UUID self, long now) {
        GameModeSwitch change;

        while ((change = switches.poll()) != null) {
            UUID id = change.id();
            if (id.equals(self)) continue;

            String name;
            Staff tracked = staff.get(id);

            if (tracked != null) {
                name = tracked.name;
            } else {
                PlayerListEntry info = conn.getPlayerListEntry(id);
                name = info == null ? null : info.getProfile().getName();

                if (name == null || !isAccount(id, name) || !knownStaffName(name)) continue;
            }

            Sighting seen = sightings.computeIfAbsent(id, key -> new Sighting());

            if (!seen.marked(now)) resort = true;

            seen.provenAt = now;

            if (seen.coolingDown(now)) continue;

            seen.switchAlertTick = tickCount;
            seen.switchedToSpectator = change.mode() == GameMode.SPECTATOR;

            alert(id, seen, now, name);
        }
    }

    private boolean knownStaffName(String name) {
        if (name.equalsIgnoreCase(PRIORITY)) return true;

        String key = name.toLowerCase(Locale.ROOT);

        for (String listed : watchNames.get()) {
            if (listed != null && listed.trim().toLowerCase(Locale.ROOT).equals(key)) return true;
        }

        for (String listed : StaffRoster.DEFAULT_NAMES) {
            if (listed.toLowerCase(Locale.ROOT).equals(key)) return true;
        }

        return false;
    }

    private void trackBodies(long now) {
        for (AbstractClientPlayerEntity body : mc.world.getPlayers()) {
            if (body == mc.player) continue;

            Staff tracked = staff.get(body.getUuid());
            if (tracked == null) continue;

            Sighting seen = sightings.computeIfAbsent(tracked.id, key -> new Sighting());
            seen.provenAt = now;
            seen.bodyTick = tickCount;
            seen.distance = mc.player.distanceTo(body);

            if (seen.nearby) continue;

            seen.nearby = true;
            resort = true;

            boolean newAppearance = seen.bodyLostAt == 0L || now - seen.bodyLostAt > NEARBY_GRACE_MS;
            if (!newAppearance) continue;

            alert(tracked.id, seen, now, tracked.name);
        }

        for (Sighting seen : sightings.values()) {
            if (!seen.nearby || seen.bodyTick == tickCount) continue;

            seen.nearby = false;
            seen.bodyLostAt = now;
            resort = true;
        }
    }

    private void pruneSightings(long now) {
        Iterator<Map.Entry<UUID, Sighting>> it = sightings.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<UUID, Sighting> entry = it.next();
            Sighting seen = entry.getValue();

            if (staff.containsKey(entry.getKey())) {
                seen.untrackedSince = 0L;
                continue;
            }

            if (seen.untrackedSince == 0L) {
                seen.untrackedSince = now;
                continue;
            }

            if (now - seen.untrackedSince <= UNTRACKED_PRUNE_MS) continue;

            it.remove();
            resort = true;
        }
    }

    private void alert(UUID id, Sighting seen, long now, String name) {
        seen.alertedAt = now;
        seen.highlightUntil = now + ALERT_HIGHLIGHT_MS;

        if (pendingAlerts.isEmpty()) alertGather = ALERT_GATHER_TICKS;

        pendingAlerts.put(id, name);
    }

    private void raise() {
        Map.Entry<UUID, String> first = pendingAlerts.entrySet().iterator().next();
        UUID id = first.getKey();
        String name = first.getValue();
        int others = pendingAlerts.size() - 1;

        pendingAlerts.clear();

        if (alarmSound.get()) alarm.start(ALARM_SECONDS, 1.0f);
        watchName = name;
        watchOthers = others;
        watchUntil = System.currentTimeMillis() + 7000L;
    }

    private void announce(Changes changes) {
        changes.changed.removeIf(this::echoesSwitchAlert);

        if (changes.isEmpty()) return;

        List<MutableText> lines = new ArrayList<>();

        describe(lines, changes.joined, "joined", (line, s) -> {
            line.append(Text.literal("joined").formatted(Formatting.YELLOW));

            String note = note(s);
            if (note != null) line.append(Text.literal(" ")).append(Text.literal(note).formatted(Formatting.RED));
        });

        describe(lines, changes.changed, "changed visibility", (line, s) -> {
            switch (s.presence) {
                case VANISHED -> line
                        .append(Text.literal("vanished").formatted(Formatting.RED))
                        .append(Text.literal(s.offTabScans > 0 ? " (off TAB, still nearby)" : " (hidden from TAB)"));
                case SPECTATOR -> line
                        .append(Text.literal("switched to "))
                        .append(Text.literal(GameMode.SPECTATOR.getName()).formatted(Formatting.RED));
                case ONLINE -> line
                        .append(Text.literal("is "))
                        .append(Text.literal("visible again").formatted(Formatting.GREEN));
            }
        });

        describe(lines, changes.left, "left", (line, s) -> {
            if (s.presence == Presence.VANISHED) {
                line.append(Text.literal("is ")).append(Text.literal("out of sight").formatted(Formatting.YELLOW));
            } else {
                line.append(Text.literal("left").formatted(Formatting.GREEN));
            }
        });

        if (chatNotify.get()) {
            for (MutableText line : lines) info(line);
        }

        boolean urgent = !changes.joined.isEmpty()
                || !changes.left.isEmpty()
                || changes.changed.stream().anyMatch(s -> s.presence != Presence.ONLINE);

        if (urgent && soundAlert.get() && mc.getSoundManager() != null) {
            mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_ARROW_HIT_PLAYER, 1.25f));
        }
    }

    private boolean echoesSwitchAlert(Staff s) {
        Sighting seen = sightings.get(s.id);

        if (seen == null || seen.switchAlertTick < 0 || tickCount - seen.switchAlertTick > SWITCH_ECHO_TICKS) return false;

        return s.presence == Presence.SPECTATOR
                ? seen.switchedToSpectator
                : s.presence == Presence.ONLINE && !seen.switchedToSpectator;
    }

    private static void describe(List<MutableText> lines, List<Staff> group, String groupVerb, BiConsumer<MutableText, Staff> verb) {
        if (group.isEmpty()) return;

        if (group.size() > GROUP_THRESHOLD) {
            MutableText line = Text.literal(group.size() + " staff " + groupVerb + ": ");

            for (int i = 0; i < group.size(); i++) {
                if (i > 0) line.append(Text.literal(", "));
                line.append(Text.literal(group.get(i).name).formatted(Formatting.AQUA));
            }

            lines.add(line);
            return;
        }

        for (Staff s : group) {
            MutableText line = Text.literal(s.name).formatted(Formatting.AQUA).append(Text.literal(" "));
            verb.accept(line, s);
            lines.add(line);
        }
    }

    private static String note(Staff s) {
        return switch (s.presence) {
            case VANISHED -> "vanished";
            case SPECTATOR -> "spectating";
            case ONLINE -> null;
        };
    }

    private static final class Staff {
        final UUID id;
        String name = "";
        Presence presence = Presence.ONLINE;
        int offTabScans;

        Staff(UUID id) {
            this.id = id;
        }

        void update(String name, Presence presence) {
            this.name = name;
            this.presence = presence;
        }
    }

    private static final class Sighting {
        long provenAt;
        long alertedAt;
        long highlightUntil;
        boolean nearby;
        float distance;
        long bodyLostAt;
        int bodyTick;
        int switchAlertTick = -1;
        boolean switchedToSpectator;
        long untrackedSince;
        long testStart;
        long testUntil;

        boolean marked(long now) {
            return nearby || now < testUntil || provenAt != 0L && now - provenAt < SERVER_MARK_MS;
        }

        boolean coolingDown(long now) {
            return alertedAt != 0L && now - alertedAt < ALERT_COOLDOWN_MS;
        }
    }

    private record TestStaff(UUID id, String name, long startedAt, long highlightUntil, long expiresAt) {
    }

    private record GameModeSwitch(UUID id, GameMode mode) {
    }

    private static final class Changes {
        final List<Staff> joined = new ArrayList<>();
        final List<Staff> changed = new ArrayList<>();
        final List<Staff> left = new ArrayList<>();

        boolean isEmpty() {
            return joined.isEmpty() && changed.isEmpty() && left.isEmpty();
        }
    }
}

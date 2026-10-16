package com.nnpg.dfizaddon.modules.main;

import com.nnpg.dfizaddon.DFizAddon;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class FakeBanScreen extends Module {
    public enum Mode {
        TEMP_BAN_ESP("Temp Ban (ESP)"),
        TEMP_BAN_CHEATING("Temp Ban (Cheating)"),
        ACCOUNT_ON_HOLD("Account On Hold");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum TimeUnit {
        DAY_HOURS_MINUTES("Day / Hours / Minutes"),
        SECONDS("Seconds");

        private final String label;

        TimeUnit(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static class TimeSettings {
        final Setting<TimeUnit> unit;
        final Setting<Integer> days;
        final Setting<Integer> hours;
        final Setting<Integer> minutes;
        final Setting<Integer> seconds;
        final Setting<String> banId;

        TimeSettings(SettingGroup group, Setting<Mode> mode, Mode own, String suffix, int defaultDays) {
            unit = group.add(new EnumSetting.Builder<TimeUnit>()
                    .name("time-unit" + suffix)
                    .description("Show the time as days, hours and minutes, or as seconds only.")
                    .defaultValue(TimeUnit.DAY_HOURS_MINUTES)
                    .visible(() -> mode.get() == own)
                    .build()
            );

            days = group.add(new IntSetting.Builder()
                    .name("days" + suffix)
                    .description("Days left on the fake ban.")
                    .defaultValue(defaultDays)
                    .min(0)
                    .sliderMax(100)
                    .visible(() -> mode.get() == own && unit.get() == TimeUnit.DAY_HOURS_MINUTES)
                    .build()
            );

            hours = group.add(new IntSetting.Builder()
                    .name("hours" + suffix)
                    .description("Hours left on the fake ban.")
                    .defaultValue(0)
                    .range(0, 23)
                    .sliderRange(0, 23)
                    .visible(() -> mode.get() == own && unit.get() == TimeUnit.DAY_HOURS_MINUTES)
                    .build()
            );

            minutes = group.add(new IntSetting.Builder()
                    .name("minutes" + suffix)
                    .description("Minutes left on the fake ban.")
                    .defaultValue(0)
                    .range(0, 59)
                    .sliderRange(0, 59)
                    .visible(() -> mode.get() == own && unit.get() == TimeUnit.DAY_HOURS_MINUTES)
                    .build()
            );

            seconds = group.add(new IntSetting.Builder()
                    .name("seconds" + suffix)
                    .description("Seconds left on the fake ban.")
                    .defaultValue(30)
                    .range(0, 59)
                    .sliderRange(0, 59)
                    .visible(() -> mode.get() == own && unit.get() == TimeUnit.SECONDS)
                    .build()
            );

            banId = group.add(new StringSetting.Builder()
                    .name("ban-id-(optional)" + suffix)
                    .description("Optional. Leave empty for a random ban ID.")
                    .defaultValue("")
                    .visible(() -> mode.get() == own)
                    .build()
            );
        }

        String text() {
            if (unit.get() == TimeUnit.SECONDS) return seconds.get() + " seconds";

            List<String> parts = new ArrayList<>();
            if (days.get() > 0) parts.add(days.get() + " day");
            if (hours.get() > 0) parts.add(hours.get() + " hours");
            if (minutes.get() > 0) parts.add(minutes.get() + " minutes");
            if (parts.isEmpty()) return "0 minutes";
            return String.join(" ", parts);
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
            .name("screen")
            .description("Which fake screen to show.")
            .defaultValue(Mode.TEMP_BAN_ESP)
            .build()
    );

    private final TimeSettings esp = new TimeSettings(sgGeneral, mode, Mode.TEMP_BAN_ESP, "", 60);
    private final TimeSettings cheating = new TimeSettings(sgGeneral, mode, Mode.TEMP_BAN_CHEATING, "-", 14);

    private final Setting<String> holdDate = sgGeneral.add(new StringSetting.Builder()
            .name("date")
            .description("Optional. Leave empty for today's date.")
            .defaultValue("")
            .visible(() -> mode.get() == Mode.ACCOUNT_ON_HOLD)
            .build()
    );

    private final Setting<String> holdBanId = sgGeneral.add(new StringSetting.Builder()
            .name("ban-id-(optional)--")
            .description("Optional. Leave empty for a random ban ID.")
            .defaultValue("")
            .visible(() -> mode.get() == Mode.ACCOUNT_ON_HOLD)
            .build()
    );

    public FakeBanScreen() {
        super(DFizAddon.CATEGORY, "fake-ban-screen", "Kicks you from the server with a fake ban screen.");
    }

    @Override
    public void onActivate() {
        toggle();

        if (mc.getNetworkHandler() == null) {
            error("You need to be connected to a server.");
            return;
        }

        mc.getNetworkHandler().getConnection().disconnect(buildReason());
    }

    private Text buildReason() {
        MutableText c = Text.empty();

        switch (mode.get()) {
            case TEMP_BAN_ESP -> {
                add(c, "You are temporarily banned for the use of ESP.", Formatting.RED);
                add(c, "", Formatting.WHITE);
                addLabeled(c, "Time Left: ", esp.text());
                add(c, "", Formatting.WHITE);
                addLabeled(c, "Ban ID: ", normalizeId(esp.banId.get()));
                add(c, "You may be able to appeal this ban on", Formatting.GRAY);
                add(c, "support.donutsmp.net", Formatting.WHITE);
            }
            case TEMP_BAN_CHEATING -> {
                add(c, "You are temporarily banned for cheating.", Formatting.RED);
                add(c, "", Formatting.WHITE);
                addLabeled(c, "Time Left: ", cheating.text());
                add(c, "", Formatting.WHITE);
                addLabeled(c, "Ban ID: ", normalizeId(cheating.banId.get()));
                add(c, "You may be able to appeal this ban on", Formatting.GRAY);
                add(c, "support.donutsmp.net", Formatting.WHITE);
            }
            case ACCOUNT_ON_HOLD -> {
                String shownDate = holdDate.get().trim();
                if (shownDate.isEmpty()) shownDate = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                add(c, "Your account has been placed on hold.", Formatting.WHITE, Formatting.BOLD);
                add(c, "We need to chat with you about something.", Formatting.GRAY);
                add(c, "", Formatting.WHITE);
                add(c, "Please open a ticket in the Donut SMP Discord", Formatting.GRAY);
                add(c, "and we'll get you back as soon as possible!", Formatting.GRAY);
                add(c, "", Formatting.WHITE);
                add(c, "discord.gg/donutsmp", Formatting.YELLOW);
                add(c, "", Formatting.WHITE);
                addLabeled(c, "Date: ", shownDate);
                add(c, "", Formatting.WHITE);
                addLabeled(c, "Ban ID: ", normalizeId(holdBanId.get()));
                add(c, "You may be able to appeal this ban on", Formatting.GRAY);
                add(c, "discord.gg/donutsmp", Formatting.WHITE);
            }
        }

        for (int i = 0; i < 3; i++) add(c, " ", Formatting.WHITE);

        return c;
    }

    private String normalizeId(String raw) {
        String id = raw.trim();
        if (id.isEmpty()) id = randomId();
        if (!id.startsWith("#")) id = "#" + id;
        return id;
    }

    private void add(MutableText root, String text, Formatting... styles) {
        if (!root.getSiblings().isEmpty()) root.append(Text.literal("\n"));
        root.append(Text.literal(text).formatted(styles));
    }

    private void addLabeled(MutableText root, String label, String value) {
        if (!root.getSiblings().isEmpty()) root.append(Text.literal("\n"));
        root.append(Text.literal(label).formatted(Formatting.GRAY));
        root.append(Text.literal(value).formatted(Formatting.WHITE));
    }

    private String randomId() {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
        }
        return sb.toString();
    }
}
package com.nnpg.dfizaddon.modules.main;

import com.nnpg.dfizaddon.DFizAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayList;
import java.util.List;

public class RtpMethod extends Module {
    private static final int ROWS = 4;
    private static final String[] DEFAULT_COMMANDS = {"delhome 2", "sethome 2", "rtp", "home 2"};
    private static final int[] DEFAULT_DELAYS = {300, 300, 1500};

    private final SettingGroup sgCommands = settings.getDefaultGroup();
    private final SettingGroup sgDelay = settings.createGroup("Delay");
    private final SettingGroup sgWait = settings.createGroup("Wait for message");

    private final List<Setting<String>> commands = new ArrayList<>();
    private final List<Setting<Integer>> delays = new ArrayList<>();
    private final List<Setting<String>> waitTexts = new ArrayList<>();
    private final Setting<Integer> waitTimeout;
    private final Setting<Boolean> logMessages;

    private final List<Integer> queue = new ArrayList<>();
    private boolean running;
    private int pos;
    private long sentAt;
    private boolean confirmed;
    private boolean logging;

    public RtpMethod() {
        super(DFizAddon.CATEGORY, "rtp-method",
            "Runs up to 4 commands in order with a delay between them. Bind a key to this module to trigger it.");

        for (int i = 0; i < ROWS; i++) {
            int n = i + 1;
            commands.add(sgCommands.add(new StringSetting.Builder()
                .name("command-" + n)
                .description("Command " + n + ", with or without the slash. Leave empty to skip it.")
                .defaultValue(DEFAULT_COMMANDS[i])
                .build()
            ));
        }

        for (int i = 0; i < ROWS - 1; i++) {
            int n = i + 1;
            delays.add(sgDelay.add(new IntSetting.Builder()
                .name("delay-after-" + n)
                .description("Milliseconds to wait after command " + n + " before command " + (n + 1)
                    + ". The server needs at least 250 ms between commands.")
                .defaultValue(DEFAULT_DELAYS[i])
                .min(250)
                .sliderRange(250, 5000)
                .build()
            ));
        }

        for (int i = 0; i < ROWS - 1; i++) {
            int n = i + 1;
            waitTexts.add(sgWait.add(new StringSetting.Builder()
                .name("wait-text-" + n)
                .description("After command " + n + ", also wait for a server message containing this text "
                    + "(not case sensitive). Leave empty to not wait.")
                .defaultValue(i == 1 ? "home" : "")
                .build()
            ));
        }

        waitTimeout = sgWait.add(new IntSetting.Builder()
            .name("wait-timeout")
            .description("Milliseconds to wait for the message. After this the next command runs anyway.")
            .defaultValue(2000)
            .min(250)
            .sliderRange(250, 10000)
            .build()
        );

        logMessages = sgWait.add(new BoolSetting.Builder()
            .name("log-messages")
            .description("Print server messages received while waiting, so you can copy the exact text.")
            .defaultValue(false)
            .build()
        );
    }

    @Override
    public void onActivate() {
        queue.clear();
        for (int i = 0; i < ROWS; i++) {
            if (!clean(commands.get(i).get()).isEmpty()) queue.add(i);
        }

        if (queue.isEmpty() || mc.player == null || mc.getNetworkHandler() == null) {
            toggle();
            return;
        }

        pos = 0;
        running = true;
        sendCurrent();
    }

    @Override
    public void onDeactivate() {
        running = false;
        confirmed = false;
        queue.clear();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (!running || logging) return;

        String text = event.getMessage().getString();

        if (logMessages.get()) {
            logging = true;
            info("Server: %s", text);
            logging = false;
        }

        if (confirmed) return;

        int row = queue.get(pos);
        if (row >= waitTexts.size()) return;

        String wanted = waitTexts.get(row).get().trim().toLowerCase();
        if (!wanted.isEmpty() && text.toLowerCase().contains(wanted)) confirmed = true;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!running) return;

        if (mc.player == null || mc.getNetworkHandler() == null) {
            toggle();
            return;
        }

        int row = queue.get(pos);
        long elapsed = System.currentTimeMillis() - sentAt;

        if (elapsed < delays.get(row).get()) return;

        String wanted = waitTexts.get(row).get().trim();
        if (!wanted.isEmpty() && !confirmed && elapsed < waitTimeout.get()) return;

        pos++;
        sendCurrent();
    }

    private void sendCurrent() {
        int row = queue.get(pos);
        mc.getNetworkHandler().sendChatCommand(clean(commands.get(row).get()));
        sentAt = System.currentTimeMillis();
        confirmed = false;

        if (pos == queue.size() - 1) toggle();
    }

    private static String clean(String command) {
        String c = command.trim();
        while (c.startsWith("/")) c = c.substring(1).trim();
        return c;
    }
}

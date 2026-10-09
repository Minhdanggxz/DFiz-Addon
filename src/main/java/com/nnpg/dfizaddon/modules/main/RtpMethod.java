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

public class RtpMethod extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDelay = settings.createGroup("Delay");
    private final SettingGroup sgConfirm = settings.createGroup("Sethome confirm");

    private final Setting<Integer> delhomeNumber = sgGeneral.add(new IntSetting.Builder()
        .name("delhome-number")
        .description("Number used in /delhome <number>.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> sethomeNumber = sgGeneral.add(new IntSetting.Builder()
        .name("sethome-number")
        .description("Number used in /sethome <number>.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> homeNumber = sgGeneral.add(new IntSetting.Builder()
        .name("home-number")
        .description("Number used in the final /home <number>.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> delayAfterDelhome = sgDelay.add(new IntSetting.Builder()
        .name("delay-after-delhome")
        .description("Ticks to wait after /delhome before /sethome (20 ticks = 1 second).")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Integer> delayAfterSethome = sgDelay.add(new IntSetting.Builder()
        .name("delay-after-sethome")
        .description("Minimum ticks to wait after /sethome before /rtp.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Integer> delayAfterRtp = sgDelay.add(new IntSetting.Builder()
        .name("delay-after-rtp")
        .description("Ticks to wait after /rtp before /home (30 ticks = 1.5 seconds).")
        .defaultValue(30)
        .min(0)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<Boolean> waitSethomeConfirm = sgConfirm.add(new BoolSetting.Builder()
        .name("wait-sethome-confirm")
        .description("Wait for the server message that confirms /sethome before running /rtp.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> confirmText = sgConfirm.add(new StringSetting.Builder()
        .name("confirm-text")
        .description("Text the server message must contain (not case sensitive). Empty = any message.")
        .defaultValue("home")
        .visible(waitSethomeConfirm::get)
        .build()
    );

    private final Setting<Integer> confirmTimeout = sgConfirm.add(new IntSetting.Builder()
        .name("confirm-timeout")
        .description("Ticks to wait for the confirm message. After this /rtp runs anyway.")
        .defaultValue(40)
        .min(1)
        .sliderRange(1, 200)
        .visible(waitSethomeConfirm::get)
        .build()
    );

    private final Setting<Boolean> logMessages = sgConfirm.add(new BoolSetting.Builder()
        .name("log-messages")
        .description("Print server messages received while waiting, so you can copy the exact confirm text.")
        .defaultValue(false)
        .visible(waitSethomeConfirm::get)
        .build()
    );

    private enum Step { IDLE, AFTER_DELHOME, AFTER_SETHOME, AFTER_RTP }

    private Step step = Step.IDLE;
    private int elapsed;
    private boolean confirmed;
    private boolean logging;

    public RtpMethod() {
        super(DFizAddon.CATEGORY, "rtp-method",
            "Runs /delhome, /sethome, /rtp and /home in order. Bind a key to this module to trigger it.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.getNetworkHandler() == null) {
            toggle();
            return;
        }

        confirmed = false;
        elapsed = 0;
        step = Step.AFTER_DELHOME;
        send("delhome " + delhomeNumber.get());
    }

    @Override
    public void onDeactivate() {
        step = Step.IDLE;
        elapsed = 0;
        confirmed = false;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (step != Step.AFTER_SETHOME || logging) return;

        String text = event.getMessage().getString();

        if (logMessages.get()) {
            logging = true;
            info("Server: %s", text);
            logging = false;
        }

        if (confirmed) return;

        String wanted = confirmText.get().trim().toLowerCase();
        if (wanted.isEmpty() || text.toLowerCase().contains(wanted)) confirmed = true;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (step == Step.IDLE) return;

        if (mc.player == null || mc.getNetworkHandler() == null) {
            toggle();
            return;
        }

        elapsed++;

        switch (step) {
            case AFTER_DELHOME -> {
                if (elapsed < delayAfterDelhome.get()) return;

                step = Step.AFTER_SETHOME;
                elapsed = 0;
                confirmed = false;
                send("sethome " + sethomeNumber.get());
            }
            case AFTER_SETHOME -> {
                if (elapsed < delayAfterSethome.get()) return;

                boolean ready = !waitSethomeConfirm.get() || confirmed || elapsed >= confirmTimeout.get();
                if (!ready) return;

                step = Step.AFTER_RTP;
                elapsed = 0;
                send("rtp");
            }
            case AFTER_RTP -> {
                if (elapsed < delayAfterRtp.get()) return;

                send("home " + homeNumber.get());
                toggle();
            }
            default -> { }
        }
    }

    private void send(String command) {
        if (mc.getNetworkHandler() != null) mc.getNetworkHandler().sendChatCommand(command);
    }
}

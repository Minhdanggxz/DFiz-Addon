package com.nnpg.dfizaddon.modules.main;

import com.nnpg.dfizaddon.DFizAddon;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class GodTrident extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Multiplier on the charge time a trident needs before it can be thrown.")
        .defaultValue(0.4)
        .range(0.0, 1.0)
        .sliderRange(0.0, 1.0)
        .build()
    );

    public final Setting<Boolean> noWater = sgGeneral.add(new BoolSetting.Builder()
        .name("no-water")
        .description("Let riptide tridents be used out of water and rain.")
        .defaultValue(true)
        .build()
    );

    public GodTrident() {
        super(DFizAddon.CATEGORY, "god-trident", "Riptide out of water and a shorter trident charge.");
    }

    public static GodTrident get() {
        return Modules.get().get(GodTrident.class);
    }
}

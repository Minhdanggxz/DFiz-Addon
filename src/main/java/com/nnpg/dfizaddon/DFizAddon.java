package com.nnpg.dfizaddon;

import com.nnpg.dfizaddon.antivanish.AdminHud;
import com.nnpg.dfizaddon.antivanish.AntiVanish;
import com.nnpg.dfizaddon.modules.esp.BedrockVoidESP;
import com.nnpg.dfizaddon.modules.esp.DFizHoleEsp;
import com.nnpg.dfizaddon.modules.esp.DFizPlayerEsp;
import com.nnpg.dfizaddon.modules.esp.DFizStorageEsp;
import com.nnpg.dfizaddon.modules.esp.DFizSusChunk;
import com.nnpg.dfizaddon.modules.esp.RegionMap;
import com.nnpg.dfizaddon.modules.esp.SpawnerBeam;
import com.nnpg.dfizaddon.modules.esp.SpawnerNotifier;
import com.nnpg.dfizaddon.modules.esp.SpawnerNametags;
import com.nnpg.dfizaddon.modules.esp.SusChunkFinder;
import com.nnpg.dfizaddon.modules.main.DFizFreecam;
import com.nnpg.dfizaddon.modules.main.GodTrident;
import com.nnpg.dfizaddon.modules.main.RtpMethod;
import com.nnpg.dfizaddon.modules.esp.AmethystBypass;
import com.nnpg.dfizaddon.modules.esp.BlockEntityDebug;
import com.nnpg.dfizaddon.modules.esp.DFizUndergroundLoaded;
import com.nnpg.dfizaddon.modules.main.FakeBanScreen;
import com.nnpg.dfizaddon.modules.main.KryptonFont;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class DFizAddon extends MeteorAddon {
    public static final Category CATEGORY = new Category("DFiz Addon");

    public static final Category esp = CATEGORY;

    @Override
    public void onInitialize() {
        Modules.get().add(new DFizFreecam());
        Modules.get().add(new GodTrident());
        Modules.get().add(new RtpMethod());
        Modules.get().add(new BedrockVoidESP());
        Modules.get().add(new RegionMap());
        Modules.get().add(new SusChunkFinder());
        Modules.get().add(new SpawnerBeam());
        Modules.get().add(new SpawnerNotifier());
        Modules.get().add(new SpawnerNametags());
        Modules.get().add(new DFizStorageEsp());
        Modules.get().add(new DFizPlayerEsp());
        Modules.get().add(new DFizHoleEsp());
        Modules.get().add(new DFizSusChunk());
        Modules.get().add(new AmethystBypass());
        Modules.get().add(new BlockEntityDebug());
        Modules.get().add(new DFizUndergroundLoaded());
        Modules.get().add(new AdminHud());
        Modules.get().add(new AntiVanish());
        Modules.get().add(new FakeBanScreen());
        Modules.get().add(new KryptonFont());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.nnpg.dfizaddon";
    }
}

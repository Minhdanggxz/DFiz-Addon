package com.nnpg.dfizaddon;

import com.nnpg.dfizaddon.modules.esp.BedrockVoidESP;
import com.nnpg.dfizaddon.modules.esp.DFizClusterFinder;
import com.nnpg.dfizaddon.modules.esp.DFizHoleEsp;
import com.nnpg.dfizaddon.modules.esp.DFizPlayerEsp;
import com.nnpg.dfizaddon.modules.esp.DFizStorageEsp;
import com.nnpg.dfizaddon.modules.esp.DFizSusChunks;
import com.nnpg.dfizaddon.modules.esp.RegionMap;
import com.nnpg.dfizaddon.modules.esp.SpawnerBeam;
import com.nnpg.dfizaddon.modules.esp.SusChunkFinder;
import com.nnpg.dfizaddon.modules.main.DFizFreecam;
import com.nnpg.dfizaddon.modules.main.GodTrident;
import com.nnpg.dfizaddon.modules.main.RtpMethod;
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
        Modules.get().add(new DFizStorageEsp());
        Modules.get().add(new DFizPlayerEsp());
        Modules.get().add(new DFizHoleEsp());
        Modules.get().add(new DFizClusterFinder());
        Modules.get().add(new DFizSusChunks());
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

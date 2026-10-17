package com.nnpg.dfizaddon.modules.main;

import com.nnpg.dfizaddon.DFizAddon;
import meteordevelopment.meteorclient.renderer.Fonts;
import meteordevelopment.meteorclient.renderer.text.FontFamily;
import meteordevelopment.meteorclient.renderer.text.FontInfo;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Module;

public class KryptonFont extends Module {
    public KryptonFont() {
        super(DFizAddon.CATEGORY, "krypton-font", "Switches the Meteor font to Minecraft Ten (krypton). Turning it off goes back to the default font.");
    }

    private FontFamily find() {
        for (FontFamily family : Fonts.FONT_FAMILIES) {
            String name = family.getName().toLowerCase();
            if (name.contains("krypton") || name.contains("minecraft ten")) return family;
        }
        return null;
    }

    @Override
    public void onActivate() {
        FontFamily family = find();

        if (family == null) {
            try {
                Fonts.refresh();
            } catch (Exception ignored) {
            }
            family = find();
        }

        if (family == null) {
            error("Font not found (%d fonts loaded). Put krypton.ttf in .minecraft/meteor-client/fonts and restart the game.", Fonts.FONT_FAMILIES.size());
            toggle();
            return;
        }

        Config.get().customFont.set(true);
        Config.get().font.set(family.get(FontInfo.Type.Regular));
    }

    @Override
    public void onDeactivate() {
        Config.get().customFont.set(false);
    }
}

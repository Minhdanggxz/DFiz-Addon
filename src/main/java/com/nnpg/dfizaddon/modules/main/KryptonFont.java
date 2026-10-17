package com.nnpg.dfizaddon.modules.main;

import com.nnpg.dfizaddon.DFizAddon;
import meteordevelopment.meteorclient.renderer.Fonts;
import meteordevelopment.meteorclient.renderer.text.FontInfo;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Module;

public class KryptonFont extends Module {
    private static final String FAMILY = "Minecraft Ten";

    public KryptonFont() {
        super(DFizAddon.CATEGORY, "krypton-font", "Switches the Meteor font to Minecraft Ten (krypton). Turning it off goes back to the default font.");
    }

    @Override
    public void onActivate() {
        var family = Fonts.getFamily(FAMILY);

        if (family == null) {
            try {
                Fonts.refresh();
            } catch (Exception ignored) {
            }
            family = Fonts.getFamily(FAMILY);
        }

        if (family == null) {
            error("Font not found. Put krypton.ttf in meteor-client/fonts and restart the game.");
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

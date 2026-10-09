package com.nnpg.dfizaddon.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.nnpg.dfizaddon.modules.main.GodTrident;
import net.minecraft.item.TridentItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(TridentItem.class)
public abstract class TridentItemMixin {
    @ModifyExpressionValue(method = "use", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;isTouchingWaterOrRain()Z"))
    private boolean dfiz$allowChargeOutOfWater(boolean original) {
        return dry() || original;
    }

    @ModifyExpressionValue(method = "onStoppedUsing", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;isTouchingWaterOrRain()Z"))
    private boolean dfiz$allowLaunchOutOfWater(boolean original) {
        return dry() || original;
    }

    private static boolean dry() {
        GodTrident trident = GodTrident.get();
        return trident != null && trident.isActive() && trident.noWater.get();
    }

    @ModifyConstant(method = "onStoppedUsing", constant = @Constant(intValue = 10))
    private int dfiz$modifyMinCharge(int original) {
        GodTrident trident = GodTrident.get();
        if (trident == null || !trident.isActive()) return original;

        return Math.max(1, (int) Math.round(original * trident.scale.get()));
    }
}

package com.nnpg.dfizaddon.mixins;

import com.nnpg.dfizaddon.modules.main.DFizFreecam;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(Camera.class)
public class CameraMixin {
    @Shadow
    private boolean thirdPerson;

    @Inject(method = "update", at = @At("HEAD"))
    private void dfiz$stepFreecam(BlockView area, Entity focusedEntity, boolean tp, boolean inverseView, float tickDelta, CallbackInfo ci) {
        DFizFreecam freecam = Modules.get().get(DFizFreecam.class);
        if (freecam != null && freecam.isActive()) freecam.onGameRender();
    }

    @Inject(method = "update", at = @At("TAIL"))
    private void dfiz$showBody(BlockView area, Entity focusedEntity, boolean tp, boolean inverseView, float tickDelta, CallbackInfo ci) {
        DFizFreecam freecam = Modules.get().get(DFizFreecam.class);
        if (freecam != null && freecam.isActive()) this.thirdPerson = true;
    }

    @ModifyArgs(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setPos(DDD)V"))
    private void dfiz$setPos(Args args) {
        DFizFreecam freecam = Modules.get().get(DFizFreecam.class);
        if (freecam == null || !freecam.isActive()) return;

        args.set(0, freecam.getInterpolatedX(0.0f));
        args.set(1, freecam.getInterpolatedY(0.0f));
        args.set(2, freecam.getInterpolatedZ(0.0f));
    }

    @ModifyArgs(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V"))
    private void dfiz$setRotation(Args args) {
        DFizFreecam freecam = Modules.get().get(DFizFreecam.class);
        if (freecam == null || !freecam.isActive()) return;

        args.set(0, (float) freecam.getInterpolatedYaw(0.0f));
        args.set(1, (float) freecam.getInterpolatedPitch(0.0f));
    }

    @ModifyVariable(method = "clipToSpace", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private float dfiz$noPullback(float distance) {
        DFizFreecam freecam = Modules.get().get(DFizFreecam.class);
        return freecam != null && freecam.isActive() ? 0.0f : distance;
    }
}

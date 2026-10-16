package com.nnpg.dfizaddon.antivanish;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.world.World;

public final class ShardTracker {
    private static final int OFF_MAP = Integer.MIN_VALUE;

    private ClientWorld level;
    private ClientPlayerEntity self;
    private String brand;
    private int goliath = OFF_MAP;

    public boolean moved(MinecraftClient mc) {
        ClientWorld nowLevel = mc.world;
        ClientPlayerEntity nowSelf = mc.player;

        if (nowLevel == null || nowSelf == null) {
            clear();
            return false;
        }

        ClientPlayNetworkHandler connection = mc.getNetworkHandler();
        String nowBrand = connection == null ? null : connection.getBrand();
        int nowGoliath = goliath(nowLevel, nowSelf);

        boolean moved = level != null && (
                nowLevel != level
                        || (nowSelf != self && !self.isDead())
                        || nowGoliath != goliath
                        || (nowBrand != null && brand != null && !nowBrand.equals(brand))
        );

        level = nowLevel;
        self = nowSelf;
        brand = nowBrand;
        goliath = nowGoliath;

        return moved;
    }

    public void clear() {
        level = null;
        self = null;
        brand = null;
        goliath = OFF_MAP;
    }

    private static int goliath(ClientWorld level, ClientPlayerEntity self) {
        if (level.getRegistryKey() != World.OVERWORLD) return OFF_MAP;

        return GoliathMap.goliathAt(self.getX(), self.getZ());
    }
}

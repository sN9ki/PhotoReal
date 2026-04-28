package com.photoreal.terrain.world.feature;

import com.photoreal.terrain.PhotorealTerrainMod;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.feature.Feature;

/**
 * Регистрация всех кастомных Feature мода.
 * Вызывается из PhotorealTerrainMod.onInitialize().
 */
public final class PhotorealFeatures {

    public static final Feature<EcosystemClusterConfig> ECOSYSTEM_CLUSTER =
        new EcosystemClusterFeature(EcosystemClusterConfig.CODEC);

    public static void register() {
        Registry.register(
            Registries.FEATURE,
            new Identifier(PhotorealTerrainMod.MOD_ID, "ecosystem_cluster"),
            ECOSYSTEM_CLUSTER
        );
        PhotorealTerrainMod.LOGGER.info("[PhotorealTerrain] Features зарегистрированы.");
    }

    private PhotorealFeatures() {}
}

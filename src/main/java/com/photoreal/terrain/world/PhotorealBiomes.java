package com.photoreal.terrain.world;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/**
 * Реестровые ключи для всех кастомных биомов мода.
 * Фактическое содержимое биома — в JSON-файле датапака.
 */
public final class PhotorealBiomes {

    public static final RegistryKey<net.minecraft.world.biome.Biome> PHOTOREAL_DENSE_FOREST =
        RegistryKey.of(RegistryKeys.BIOME,
            new Identifier("photoreal_terrain", "photoreal_dense_forest"));

    private PhotorealBiomes() {}
}

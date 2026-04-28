package com.photoreal.terrain;

import com.photoreal.terrain.worldgen.SmoothDensityFunction;
import net.fabricmc.api.ModInitializer;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Главный инициализатор мода Photoreal Terrain.
 *
 * Регистрирует кастомный тип DensityFunction в реестре Minecraft.
 * После регистрации этот тип можно использовать в JSON-файлах датапака
 * через идентификатор "photoreal_terrain:smooth_density".
 */
public class PhotorealTerrainMod implements ModInitializer {

    public static final String MOD_ID = "photoreal_terrain";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * Идентификатор кастомной density function.
     * Используется в JSON датапака: "type": "photoreal_terrain:smooth_density"
     */
    public static final Identifier SMOOTH_DENSITY_ID = new Identifier(MOD_ID, "smooth_density");

    @Override
    public void onInitialize() {
        LOGGER.info("[PhotorealTerrain] Инициализация — регистрация плавной функции плотности...");

        // Регистрируем кастомный кодек DensityFunction в реестре типов
        Registry.register(
            Registries.DENSITY_FUNCTION_TYPE,
            SMOOTH_DENSITY_ID,
            SmoothDensityFunction.CODEC.codec()
        );

        com.photoreal.terrain.world.feature.PhotorealFeatures.register();
        LOGGER.info("[PhotorealTerrain] SmoothDensityFunction зарегистрирована как '{}'", SMOOTH_DENSITY_ID);
    }
}

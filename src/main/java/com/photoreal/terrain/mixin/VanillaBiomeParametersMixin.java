package com.photoreal.terrain.mixin;

import com.photoreal.terrain.PhotorealTerrainMod;
import com.photoreal.terrain.world.PhotorealBiomes;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.biome.source.util.MultiNoiseUtil.ParameterRange;
import net.minecraft.world.biome.source.util.MultiNoiseUtil.NoiseHypercube;
import net.minecraft.world.biome.source.util.VanillaBiomeParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * ============================================================
 *  VanillaBiomeParametersMixin — инъекция биома в Multi-Noise
 * ============================================================
 *
 * ЦЕЛЕВОЙ КЛАСС:
 * --------------
 * net.minecraft.world.biome.source.util.VanillaBiomeParameters
 *
 * Этот класс содержит метод writeVanillaBiomeParameters() который
 * передаёт RegistryEntry<Biome> в ParameterList через Consumer.
 * Мы вклиниваемся в этот Consumer чтобы добавить СВОЙ биом.
 *
 * ПАРАМЕТРЫ НАШЕГО БИОМА (temperate, humid valley):
 * --------------------------------------------------
 *
 *   temperature:       [0.2, 0.55]   — умеренный климат
 *   humidity:          [0.55, 0.9]   — влажный (густой лес требует дождей)
 *   continentalness:   [-0.1, 0.55]  — умеренно континентальный
 *   erosion:           [0.35, 0.7]   — умеренная эрозия → широкие долины
 *   weirdness:         [-0.2, 0.2]   — нормальный рельеф (не "weird")
 *   depth:             [0.0, 0.1]    — поверхность (не пещера)
 *   offset:            0.0           — нет смещения
 *
 * ФИЗИЧЕСКАЯ ИНТЕРПРЕТАЦИЯ ПАРАМЕТРОВ:
 * ------------------------------------
 * temperature:     -1=замёрзший, 0=умеренный, 1=жаркий
 * humidity:        -1=пустыня, 0=умеренный, 1=тропики
 * continentalness: -1.05=глубокий океан, -0.19=берег, 0.03=побережье,
 *                  0.3=умеренно материковый, 1.0=горы
 * erosion:         -1=горы (минимальная эрозия), 1=равнины (максимальная)
 * weirdness:       -1=normal1, 1=normal2 (определяет "бугристость")
 * depth:           0=поверхность, 1=глубже
 *
 * ПОЧЕМУ НЕ JSON OVERWORLD.JSON:
 * --------------------------------
 * Переопределение data/minecraft/dimension/overworld.json ломает
 * совместимость с Terralith, Tectonic, BYG и другими biome-modifying модами.
 * Mixin в VanillaBiomeParameters работает ВМЕСТЕ с этими модами.
 */
@Mixin(VanillaBiomeParameters.class)
public abstract class VanillaBiomeParametersMixin {

    /**
     * Инжект в конец writeVanillaBiomeParameters() — после того как
     * все vanilla-биомы уже добавлены в параметр-лист.
     *
     * @param biomeEntries Consumer<Pair<NoiseHypercube, RegistryEntry<Biome>>>
     *                     - в него мы добавляем нашу точку
     * @param biomeRegistry lookup для получения RegistryEntry<Biome>
     */
    @Inject(
        method = "writeVanillaBiomeParameters",
        at = @At("RETURN")
    )
    private void injectPhotorealBiome(
            Consumer<MultiNoiseUtil.Entries<RegistryEntry<Biome>>> parameters,
            RegistryEntryLookup<Biome> biomeRegistry,
            CallbackInfo ci) {

        // Получаем RegistryEntry нашего биома из реестра
        var ourBiome = biomeRegistry.getOptional(PhotorealBiomes.PHOTOREAL_DENSE_FOREST);
        if (ourBiome.isEmpty()) {
            PhotorealTerrainMod.LOGGER.warn(
                "[PhotorealTerrain] Биом '{}' не найден в реестре! " +
                "Убедитесь что JSON файл биома существует в датапаке.",
                PhotorealBiomes.PHOTOREAL_DENSE_FOREST.getValue()
            );
            return;
        }

        // ── Параметры Multi-Noise для нашего биома ───────────────────────────
        //
        // Каждый параметр — ParameterRange [min, max] в пространстве [-2, 2]
        // Minecraft нормализует vanilla-значения в [-1, 1]
        //
        // Мы используем несколько отдельных NoiseHypercube-точек чтобы
        // покрыть разные комбинации conditions → биом возникает чаще.

        // Точка 1: Основная — влажная умеренная долина
        addBiome(parameters, ourBiome.get(),
            range(0.20f,  0.55f),   // temperature: умеренный
            range(0.55f,  0.90f),   // humidity: высокая
            range(-0.10f, 0.55f),   // continentalness: умеренно материковый
            range(0.35f,  0.70f),   // erosion: долины
            range(-0.20f, 0.20f),   // weirdness: нормальный
            range(0.00f,  0.10f)    // depth: поверхность
        );

        // Точка 2: Расширение — чуть менее влажная версия
        addBiome(parameters, ourBiome.get(),
            range(0.15f,  0.45f),
            range(0.45f,  0.75f),
            range(-0.05f, 0.45f),
            range(0.30f,  0.65f),
            range(-0.30f, 0.05f),
            range(0.00f,  0.10f)
        );

        PhotorealTerrainMod.LOGGER.info(
            "[PhotorealTerrain] Биом 'Photoreal Dense Forest' добавлен в Multi-Noise (2 зоны)."
        );
    }

    /** Удобный метод добавления биома с параметром-диапазонами. */
    private static void addBiome(
            Consumer<MultiNoiseUtil.Entries<RegistryEntry<Biome>>> parameters,
            RegistryEntry<Biome> biome,
            ParameterRange temperature,
            ParameterRange humidity,
            ParameterRange continentalness,
            ParameterRange erosion,
            ParameterRange weirdness,
            ParameterRange depth) {

        parameters.accept(entries -> entries.add(
            biome,
            new NoiseHypercube(
                temperature,
                humidity,
                continentalness,
                erosion,
                depth,
                weirdness,
                0L  // offset (0 = стандартный центр)
            )
        ));
    }

    /** Сокращение для создания ParameterRange. */
    private static ParameterRange range(float min, float max) {
        return MultiNoiseUtil.ParameterRange.of(min, max);
    }
}

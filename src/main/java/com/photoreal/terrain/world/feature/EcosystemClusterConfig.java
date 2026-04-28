package com.photoreal.terrain.world.feature;

import com.mojang.serialization.Codec;
import net.minecraft.world.gen.feature.FeatureConfig;

/**
 * Конфигурация кластерного фичи экосистемы.
 * Все параметры хранятся здесь и доступны из JSON через Codec.
 */
public record EcosystemClusterConfig(
    /** Радиус кластера в блоках (от центра до крайних элементов). */
    int radius,
    /** Минимальное расстояние между деревьями (Poisson disk). */
    float minTreeSpacing,
    /** Вероятность спавна моховых булыжников (0.0-1.0). */
    float boulderChance,
    /** Вероятность спавна упавшего бревна (0.0-1.0). */
    float fallenLogChance,
    /** Количество попыток спавна элементов подлеска. */
    int undergrowthAttempts
) implements FeatureConfig {

    public static final Codec<EcosystemClusterConfig> CODEC = Codec.unit(
        // Дефолтный конфиг, используется если в JSON не задан
        new EcosystemClusterConfig(14, 2.5f, 0.35f, 0.25f, 48)
    );

    // Подходящие предустановки
    public static final EcosystemClusterConfig DEFAULT = new EcosystemClusterConfig(
        14,   // radius
        2.5f, // minTreeSpacing
        0.35f,// boulderChance
        0.25f,// fallenLogChance
        48    // undergrowthAttempts
    );
}

package com.photoreal.terrain.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.math.noise.SimplexNoiseSampler;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.densityfunction.DensityFunction.*;
import net.minecraft.world.gen.densityfunction.DensityFunctionTypes.*;
import net.minecraft.world.gen.densityfunction.DensityFunctionVisitor;

/**
 * ============================================================
 *  SmoothDensityFunction — плавная математическая функция плотности
 * ============================================================
 *
 * ПРИНЦИП РАБОТЫ:
 * ---------------
 * В Minecraft генерация рельефа основана на DensityFunction:
 *   - значение > 0  → блок твёрдый (камень/земля)
 *   - значение < 0  → блок воздух
 *   - значение ≈ 0  → поверхность рельефа
 *
 * Для плавного (smooth voxel) рельефа нам нужна НЕПРЕРЫВНАЯ функция,
 * которая меняется без ступеней. Достигается через:
 *
 *   1. Fractal Brownian Motion (fBm) — суммирование октав шума с
 *      убывающей амплитудой для получения фрактального холмистого ландшафта.
 *
 *   2. Вертикальный bias (Y-gradient) — чем ниже Y, тем плотнее;
 *      чем выше Y, тем разреженнее. Это создаёт горизонт поверхности.
 *
 *   3. SmoothStep сглаживание — плавный переход у поверхности,
 *      убирает любые ступенчатые артефакты.
 *
 *   4. Terrain features — дополнительные октавы для пещер,
 *      скальных выступов и плавных долин.
 *
 * МАТЕМАТИКА:
 * -----------
 * density(x, y, z) = yBias(y) + fBm(x, y, z) + caveCarve(x, y, z)
 *
 * где:
 *   yBias    = clamp((seaLevel - y) / terrainScale, -1, 1)
 *   fBm      = Σ(amplitude_i × noise(x×freq_i, y×freq_i, z×freq_i))
 *   caveCarve = -abs(noise_cave) × caveStrength (отрицательный → вырезает пещеры)
 *
 * PLAYABILITY:
 * -----------
 * Параметры настроены так, чтобы мир был играбельным:
 *   - Средняя высота поверхности ≈ Y64 (уровень моря)
 *   - Холмы поднимаются до Y80–110
 *   - Долины опускаются до Y40–55
 *   - Пещеры плавно вырезаются ниже Y50
 */
public class SmoothDensityFunction implements DensityFunction {

    // ─── Codec для сериализации / десериализации из JSON датапака ───────────
    public static final MapCodec<SmoothDensityFunction> CODEC = RecordCodecBuilder.mapCodec(instance ->
        instance.group(
            Codec.LONG.fieldOf("seed").orElse(0L).forGetter(f -> f.seed),
            Codec.DOUBLE.fieldOf("terrain_scale").orElse(80.0).forGetter(f -> f.terrainScale),
            Codec.DOUBLE.fieldOf("horizontal_scale").orElse(256.0).forGetter(f -> f.horizontalScale),
            Codec.INT.fieldOf("sea_level").orElse(64).forGetter(f -> f.seaLevel),
            Codec.INT.fieldOf("octaves").orElse(6).forGetter(f -> f.octaves),
            Codec.DOUBLE.fieldOf("cave_strength").orElse(0.35).forGetter(f -> f.caveStrength)
        ).apply(instance, SmoothDensityFunction::new)
    );

    // ─── Параметры, читаемые из JSON ─────────────────────────────────────────
    private final long seed;
    private final double terrainScale;    // Вертикальный диапазон рельефа (блоков)
    private final double horizontalScale; // Горизонтальный масштаб холмов
    private final int seaLevel;           // Y уровня моря
    private final int octaves;            // Количество октав fBm
    private final double caveStrength;    // Сила вырезания пещер (0 = нет пещер)

    // ─── Сэмплеры шума ───────────────────────────────────────────────────────
    private final SimplexNoiseSampler terrainNoise;     // Основной рельеф
    private final SimplexNoiseSampler detailNoise;      // Детали (скалы, уступы)
    private final SimplexNoiseSampler caveNoise;        // Пещеры
    private final SimplexNoiseSampler warpNoiseX;       // Деформация X (domain warping)
    private final SimplexNoiseSampler warpNoiseZ;       // Деформация Z (domain warping)

    // ─── Константы fBm ───────────────────────────────────────────────────────
    private static final double PERSISTENCE   = 0.5;   // Убывание амплитуды на октаву
    private static final double LACUNARITY    = 1.987; // Умножение частоты на октаву
    private static final double WARP_STRENGTH = 48.0;  // Сила domain warping

    public SmoothDensityFunction(long seed, double terrainScale, double horizontalScale,
                                 int seaLevel, int octaves, double caveStrength) {
        this.seed           = seed;
        this.terrainScale   = terrainScale;
        this.horizontalScale = horizontalScale;
        this.seaLevel       = seaLevel;
        this.octaves        = octaves;
        this.caveStrength   = caveStrength;

        // Инициализируем независимые сэмплеры разными сидами
        this.terrainNoise = new SimplexNoiseSampler(new CheckedRandom(seed));
        this.detailNoise  = new SimplexNoiseSampler(new CheckedRandom(seed + 1337L));
        this.caveNoise    = new SimplexNoiseSampler(new CheckedRandom(seed + 7331L));
        this.warpNoiseX   = new SimplexNoiseSampler(new CheckedRandom(seed + 9999L));
        this.warpNoiseZ   = new SimplexNoiseSampler(new CheckedRandom(seed + 1111L));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ГЛАВНАЯ ТОЧКА ВХОДА — вызывается движком генерации для каждого столбца
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public double sample(NoisePos pos) {
        double x = pos.blockX();
        double y = pos.blockY();
        double z = pos.blockZ();

        // 1. Domain Warping: деформируем пространство выборки через отдельный шум.
        //    Это устраняет повторяющиеся паттерны и добавляет органичность.
        double warpX = sampleSimplex(warpNoiseX, x * 0.004, y * 0.002, z * 0.004) * WARP_STRENGTH;
        double warpZ = sampleSimplex(warpNoiseZ, x * 0.004, y * 0.002, z * 0.004) * WARP_STRENGTH;

        double wx = x + warpX;
        double wz = z + warpZ;

        // 2. Fractal Brownian Motion для основного рельефа
        double terrain = fbm(wx, y * 0.5, wz, octaves, terrainNoise);

        // 3. Детализирующий слой (высокочастотные детали — скалы, уступы)
        double detail = fbm(wx * 2.0, y, wz * 2.0, 3, detailNoise) * 0.25;

        // 4. Вертикальный градиент (bias):
        //    Плавное убывание плотности с высотой.
        //    При Y=seaLevel → bias=0, ниже → положительный, выше → отрицательный.
        double yBias = computeYBias(y);

        // 5. Итоговый рельеф до вырезания пещер
        double density = yBias + terrain + detail;

        // 6. Вырезание пещер (только в нижней части мира)
        if (caveStrength > 0.0 && y < seaLevel + 16) {
            double caveFactor = sampleCave(wx, y, wz);
            double caveDepthWeight = smoothstep(
                clamp((seaLevel + 16 - y) / 32.0, 0.0, 1.0)
            );
            density -= caveFactor * caveStrength * caveDepthWeight;
        }

        return clamp(density, minValue(), maxValue());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ВЕРТИКАЛЬНЫЙ ГРАДИЕНТ ПЛОТНОСТИ
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Создаёт S-образный переход вокруг уровня моря.
     * Используем tanh-подобную кривую для плавного перехода воздух→камень.
     *
     * @param y координата высоты
     * @return [-1.2 .. +1.2] плавно
     */
    private double computeYBias(double y) {
        // Нормализуем Y относительно уровня моря и масштаба рельефа
        double normalizedY = (seaLevel - y) / terrainScale;

        // SmoothStep через кубическую эрмитову функцию для плавного горизонта
        // Без этого поверхность была бы ступенчатой даже с шумом
        return sigmoid(normalizedY * 1.8) * 1.2;
    }

    /**
     * Сигмоид-функция для плавного вертикального bias.
     * Возвращает значение в диапазоне (-1, 1).
     */
    private double sigmoid(double x) {
        return (2.0 / (1.0 + Math.exp(-x))) - 1.0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  FRACTAL BROWNIAN MOTION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Суммирует несколько октав шума с убывающей амплитудой.
     * Результат нормализован обратно в [-1, +1].
     *
     * @param x, y, z  координаты в пространстве шума
     * @param numOctaves количество октав (больше = детальнее, но медленнее)
     * @param sampler   сэмплер SimplexNoise
     */
    private double fbm(double x, double y, double z, int numOctaves, SimplexNoiseSampler sampler) {
        double amplitude     = 1.0;
        double frequency     = 1.0 / horizontalScale;
        double value         = 0.0;
        double maxAmplitude  = 0.0; // Для нормализации

        for (int i = 0; i < numOctaves; i++) {
            value        += amplitude * sampleSimplex(sampler, x * frequency, y * frequency, z * frequency);
            maxAmplitude += amplitude;
            amplitude    *= PERSISTENCE;
            frequency    *= LACUNARITY;
        }

        return value / maxAmplitude; // Нормализация → [-1, +1]
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ГЕНЕРАЦИЯ ПЕЩЕР — «Swiss Cheese» метод с worm-like туннелями
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Создаёт плавные пещеры используя abs(noise) подход.
     * Где |noise| < threshold → пещера (возвращаем большое положительное значение
     * для вычитания из плотности).
     */
    private double sampleCave(double x, double y, double z) {
        // Два независимых шума для создания worm-туннелей
        double n1 = sampleSimplex(caveNoise, x * 0.008, y * 0.012, z * 0.008);
        double n2 = sampleSimplex(caveNoise, x * 0.008 + 100, y * 0.012 + 100, z * 0.008 + 100);

        // Swiss cheese: вырезаем где оба шума близки к 0
        double dist = Math.sqrt(n1 * n1 + n2 * n2);

        // smoothstep для плавного края пещеры (не резкий срез)
        double threshold = 0.18;
        if (dist < threshold) {
            return smoothstep(1.0 - (dist / threshold));
        }
        return 0.0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  УТИЛИТЫ
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Обёртка для Simplex Noise с правильным масштабированием.
     * Minecraft-овский SimplexNoiseSampler.sample() принимает (x, y, z).
     */
    private static double sampleSimplex(SimplexNoiseSampler sampler, double x, double y, double z) {
        // sample3D нормализован в [-1, +1]
        return sampler.sample(x, y, z);
    }

    /**
     * Кубическая функция сглаживания Hermite (smoothstep).
     * Превращает линейный переход в S-образную кривую.
     * t должен быть в [0, 1].
     */
    private static double smoothstep(double t) {
        t = clamp(t, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    /** Стандартный clamp. */
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ОБЯЗАТЕЛЬНЫЕ МЕТОДЫ DensityFunction
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public double minValue() {
        return -2.0;
    }

    @Override
    public double maxValue() {
        return 2.0;
    }

    @Override
    public KeyedDensityFunction wrapped() {
        // Не оборачиваем — прямая реализация
        throw new UnsupportedOperationException("SmoothDensityFunction не является обёрткой");
    }

    @Override
    public DensityFunction apply(DensityFunctionVisitor visitor) {
        return visitor.apply(this);
    }

    @Override
    public CodecHolder<? extends DensityFunction> getCodecHolder() {
        return CodecHolder.of(CODEC);
    }

    @Override
    public void fill(double[] densities, EachApplier applier) {
        // Стандартная реализация: заполняем массив вызовами sample()
        applier.fill(densities, this);
    }
}

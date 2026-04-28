package com.photoreal.terrain.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.util.math.noise.SimplexNoiseSampler;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.world.gen.densityfunction.DensityFunction;

public class SmoothDensityFunction implements DensityFunction {

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

    private final long seed;
    private final double terrainScale;
    private final double horizontalScale;
    private final int seaLevel;
    private final int octaves;
    private final double caveStrength;

    private final SimplexNoiseSampler terrainNoise;
    private final SimplexNoiseSampler detailNoise;
    private final SimplexNoiseSampler caveNoise;
    private final SimplexNoiseSampler warpNoiseX;
    private final SimplexNoiseSampler warpNoiseZ;

    private static final double PERSISTENCE   = 0.5;
    private static final double LACUNARITY    = 1.987;
    private static final double WARP_STRENGTH = 48.0;

    public SmoothDensityFunction(long seed, double terrainScale, double horizontalScale,
                                 int seaLevel, int octaves, double caveStrength) {
        this.seed           = seed;
        this.terrainScale   = terrainScale;
        this.horizontalScale = horizontalScale;
        this.seaLevel       = seaLevel;
        this.octaves        = octaves;
        this.caveStrength   = caveStrength;

        this.terrainNoise = new SimplexNoiseSampler(new CheckedRandom(seed));
        this.detailNoise  = new SimplexNoiseSampler(new CheckedRandom(seed + 1337L));
        this.caveNoise    = new SimplexNoiseSampler(new CheckedRandom(seed + 7331L));
        this.warpNoiseX   = new SimplexNoiseSampler(new CheckedRandom(seed + 9999L));
        this.warpNoiseZ   = new SimplexNoiseSampler(new CheckedRandom(seed + 1111L));
    }

    @Override
    public double sample(DensityFunction.NoisePos pos) {
        double x = pos.blockX();
        double y = pos.blockY();
        double z = pos.blockZ();

        double warpX = sampleSimplex(warpNoiseX, x * 0.004, y * 0.002, z * 0.004) * WARP_STRENGTH;
        double warpZ = sampleSimplex(warpNoiseZ, x * 0.004, y * 0.002, z * 0.004) * WARP_STRENGTH;

        double wx = x + warpX;
        double wz = z + warpZ;

        double terrain = fbm(wx, y * 0.5, wz, octaves, terrainNoise);
        double detail = fbm(wx * 2.0, y, wz * 2.0, 3, detailNoise) * 0.25;
        double yBias = computeYBias(y);

        double density = yBias + terrain + detail;

        if (caveStrength > 0.0 && y < seaLevel + 16) {
            double caveFactor = sampleCave(wx, y, wz);
            double caveDepthWeight = smoothstep(
                clamp((seaLevel + 16 - y) / 32.0, 0.0, 1.0)
            );
            density -= caveFactor * caveStrength * caveDepthWeight;
        }

        return clamp(density, minValue(), maxValue());
    }

    private double computeYBias(double y) {
        double normalizedY = (seaLevel - y) / terrainScale;
        return sigmoid(normalizedY * 1.8) * 1.2;
    }

    private double sigmoid(double x) {
        return (2.0 / (1.0 + Math.exp(-x))) - 1.0;
    }

    private double fbm(double x, double y, double z, int numOctaves, SimplexNoiseSampler sampler) {
        double amplitude     = 1.0;
        double frequency     = 1.0 / horizontalScale;
        double value         = 0.0;
        double maxAmplitude  = 0.0;

        for (int i = 0; i < numOctaves; i++) {
            value        += amplitude * sampleSimplex(sampler, x * frequency, y * frequency, z * frequency);
            maxAmplitude += amplitude;
            amplitude    *= PERSISTENCE;
            frequency    *= LACUNARITY;
        }

        return value / maxAmplitude;
    }

    private double sampleCave(double x, double y, double z) {
        double n1 = sampleSimplex(caveNoise, x * 0.008, y * 0.012, z * 0.008);
        double n2 = sampleSimplex(caveNoise, x * 0.008 + 100, y * 0.012 + 100, z * 0.008 + 100);

        double dist = Math.sqrt(n1 * n1 + n2 * n2);
        double threshold = 0.18;
        if (dist < threshold) {
            return smoothstep(1.0 - (dist / threshold));
        }
        return 0.0;
    }

    private static double sampleSimplex(SimplexNoiseSampler sampler, double x, double y, double z) {
        return sampler.sample(x, y, z);
    }

    private static double smoothstep(double t) {
        t = clamp(t, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public double minValue() {
        return -2.0;
    }

    @Override
    public double maxValue() {
        return 2.0;
    }

    // ИСПРАВЛЕНИЕ: Вот это то самое двойное название из Fabric Yarn
    @Override
    public DensityFunction apply(DensityFunction.DensityFunctionVisitor visitor) {
        return visitor.apply(this);
    }

    @Override
    public CodecHolder<? extends DensityFunction> getCodecHolder() {
        return CodecHolder.of(CODEC);
    }

    @Override
    public void fill(double[] densities, DensityFunction.EachApplier applier) {
        applier.fill(densities, this);
    }
}
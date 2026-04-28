package com.photoreal.terrain.world.feature;

import com.mojang.serialization.Codec;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PillarBlock;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.FeaturePlacementContext;
import net.minecraft.world.gen.feature.PlacedFeature;
import net.minecraft.world.gen.feature.util.FeatureContext;

import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================
 *  EcosystemClusterFeature — органический кластер экосистемы
 * ============================================================
 *
 * КОНЦЕПЦИЯ:
 * ----------
 * Вместо равномерного случайного размещения деревьев (vanilla подход)
 * этот Feature генерирует ЕДИНЫЙ СВЯЗНЫЙ КЛАСТЕР:
 *
 *   Центр (r < 3):          Массивное дерево (Dark Oak / Jungle)
 *   Средний пояс (3 < r < 8): 3-5 деревьев среднего размера
 *   Внешний пояс (8 < r < 14): Подлесок, кусты, папоротники
 *   Случайно:               Моховые булыжники, упавшие бревна
 *
 * АЛГОРИТМ РАЗМЕЩЕНИЯ: Poisson Disk Sampling
 * -------------------------------------------
 * Стандартный Random даёт равномерное распределение → деревья
 * выглядят «рассыпанными». Poisson Disk гарантирует минимальное
 * расстояние между точками → органическое «стояние» деревьев.
 *
 * Алгоритм Бриджсона (упрощённая версия):
 *   1. Начинаем с центральной точки
 *   2. Для каждой активной точки пробуем k=30 новых точек
 *      на расстоянии [r, 2r] от неё
 *   3. Если точка не конфликтует с существующими → добавляем
 *   4. Иначе → точка становится неактивной
 *
 * СЕМЕНА (seed) ДЛЯ ДЕТЕРМИНИЗМА:
 * --------------------------------
 * Используем BlockPos.asLong() как seed → один и тот же кластер
 * при перезагрузке мира. Никаких аллокаций UUID.
 */
public class EcosystemClusterFeature extends Feature<EcosystemClusterConfig> {

    // Ключи PlacedFeature для элементов кластера
    // (регистрируются в PhotorealFeatures, используются через RegistryEntryLookup)
    private static final Identifier CANOPY_TREE_ID =
        new Identifier("photoreal_terrain", "photoreal_canopy_tree");
    private static final Identifier MEDIUM_TREE_ID =
        new Identifier("photoreal_terrain", "photoreal_medium_tree");
    private static final Identifier BUSH_ID =
        new Identifier("photoreal_terrain", "photoreal_bush");
    private static final Identifier UNDERGROWTH_ID =
        new Identifier("photoreal_terrain", "photoreal_undergrowth_patch");
    private static final Identifier BOULDER_ID =
        new Identifier("photoreal_terrain", "photoreal_mossy_boulder");

    public EcosystemClusterFeature(Codec<EcosystemClusterConfig> configCodec) {
        super(configCodec);
    }

    @Override
    public boolean generate(FeatureContext<EcosystemClusterConfig> ctx) {
        EcosystemClusterConfig config = ctx.getConfig();
        StructureWorldAccess world    = ctx.getWorld();
        BlockPos origin               = ctx.getOrigin();
        Random random                 = ctx.getRandom();

        // Находим поверхность в центральной точке
        BlockPos surfaceCenter = getSurface(world, origin);
        if (surfaceCenter == null) return false;

        int placed = 0;

        // ── Шаг 1: Центральное дерево (массивный дуб/тёмный дуб) ─────────────
        placed += placeFeatureAt(world, random, surfaceCenter, CANOPY_TREE_ID) ? 1 : 0;

        // ── Шаг 2: Poisson Disk Sampling для деревьев среднего яруса ─────────
        List<BlockPos> treePositions = poissonDiskSample(
            surfaceCenter, config.radius() - 2, config.minTreeSpacing(), random, 12
        );

        for (BlockPos treePos : treePositions) {
            float r = (float) Math.sqrt(
                Math.pow(treePos.getX() - surfaceCenter.getX(), 2) +
                Math.pow(treePos.getZ() - surfaceCenter.getZ(), 2)
            );

            BlockPos surface = getSurface(world, treePos);
            if (surface == null) continue;

            if (r < 4f) {
                // Ближняя зона → средние деревья
                placed += placeFeatureAt(world, random, surface, MEDIUM_TREE_ID) ? 1 : 0;
            } else if (r < 9f) {
                // Средняя зона → малые деревья или кусты
                Identifier id = random.nextFloat() < 0.6f ? MEDIUM_TREE_ID : BUSH_ID;
                placed += placeFeatureAt(world, random, surface, id) ? 1 : 0;
            } else {
                // Внешняя зона → только кусты и подлесок
                placed += placeFeatureAt(world, random, surface, BUSH_ID) ? 1 : 0;
            }
        }

        // ── Шаг 3: Активный подлесок (случайные попытки в радиусе) ──────────
        for (int i = 0; i < config.undergrowthAttempts(); i++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double dist  = random.nextDouble() * config.radius();
            int dx = (int)(Math.cos(angle) * dist);
            int dz = (int)(Math.sin(angle) * dist);
            BlockPos pos = getSurface(world, origin.add(dx, 0, dz));
            if (pos != null) {
                placeFeatureAt(world, random, pos, UNDERGROWTH_ID);
            }
        }

        // ── Шаг 4: Моховые булыжники (bulders) ───────────────────────────────
        if (random.nextFloat() < config.boulderChance()) {
            int boulderCount = 1 + random.nextInt(3);
            for (int i = 0; i < boulderCount; i++) {
                placeMossyBoulder(world, random, surfaceCenter, config.radius());
            }
        }

        // ── Шаг 5: Упавшие брёвна ────────────────────────────────────────────
        if (random.nextFloat() < config.fallenLogChance()) {
            placeFallenLog(world, random, surfaceCenter, config.radius());
        }

        return placed > 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  POISSON DISK SAMPLING
    //  Генерирует органично распределённые точки без слипания
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Упрощённый алгоритм Бриджсона для 2D Poisson Disk Sampling.
     *
     * @param center     центральная точка
     * @param radius     радиус области размещения
     * @param minDist    минимальное расстояние между точками
     * @param random     RNG
     * @param maxPoints  максимальное количество точек
     * @return список BlockPos (Y не задан, использовать getSurface())
     */
    private List<BlockPos> poissonDiskSample(BlockPos center, int radius,
                                              float minDist, Random random,
                                              int maxPoints) {
        List<BlockPos> points  = new ArrayList<>();
        List<BlockPos> active  = new ArrayList<>();

        // Первая точка: среднее расстояние от центра (не в самом центре — там canopy tree)
        BlockPos first = center.add(
            (int)(Math.cos(random.nextDouble() * Math.PI * 2) * (minDist + 2)),
            0,
            (int)(Math.sin(random.nextDouble() * Math.PI * 2) * (minDist + 2))
        );
        points.add(first);
        active.add(first);

        int k = 20; // попыток на активную точку

        while (!active.isEmpty() && points.size() < maxPoints) {
            int idx = random.nextInt(active.size());
            BlockPos current = active.get(idx);
            boolean placed = false;

            for (int attempt = 0; attempt < k; attempt++) {
                // Случайная точка на кольце [minDist, 2*minDist] от current
                double angle = random.nextDouble() * 2 * Math.PI;
                double dist  = minDist + random.nextDouble() * minDist;
                int nx = current.getX() + (int)(Math.cos(angle) * dist);
                int nz = current.getZ() + (int)(Math.sin(angle) * dist);

                // Проверка: в пределах радиуса
                double dr = Math.sqrt(
                    Math.pow(nx - center.getX(), 2) + Math.pow(nz - center.getZ(), 2)
                );
                if (dr > radius) continue;

                BlockPos candidate = new BlockPos(nx, center.getY(), nz);

                // Проверка: не слишком близко к другим точкам
                boolean tooClose = false;
                for (BlockPos existing : points) {
                    double d2 = Math.sqrt(
                        Math.pow(existing.getX() - nx, 2) + Math.pow(existing.getZ() - nz, 2)
                    );
                    if (d2 < minDist) { tooClose = true; break; }
                }

                if (!tooClose) {
                    points.add(candidate);
                    active.add(candidate);
                    placed = true;
                    break;
                }
            }

            if (!placed) {
                active.remove(idx); // Исчерпана — удаляем из активных
            }
        }

        return points;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PLACE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /** Находит верхний блок воздуха над твёрдой поверхностью. */
    private BlockPos getSurface(StructureWorldAccess world, BlockPos base) {
        BlockPos.Mutable mutable = new BlockPos.Mutable(base.getX(), base.getY(), base.getZ());

        // Ищем твёрдую поверхность сверху вниз в диапазоне ±16 блоков
        for (int dy = 16; dy > -16; dy--) {
            mutable.setY(base.getY() + dy);
            if (world.getBlockState(mutable).isSolidBlock(world, mutable)) {
                return mutable.up().toImmutable();
            }
        }
        return null;
    }

    /**
     * Размещает PlacedFeature по ID из реестра.
     * Использует мировой RegistryEntry lookup (нет аллокаций RegistryKey).
     */
    private boolean placeFeatureAt(StructureWorldAccess world, Random random,
                                    BlockPos pos, Identifier featureId) {
        var lookup = world.getRegistryManager().getOptional(RegistryKeys.PLACED_FEATURE);
        if (lookup.isEmpty()) return false;

        var entry = lookup.get().getOptional(RegistryKey.of(RegistryKeys.PLACED_FEATURE, featureId));
        if (entry.isEmpty()) return false;

        return entry.get().generateUnregistered(world, null, random, pos);
    }

    /** Размещает моховый булыжник (2-3 блока) на случайной позиции. */
    private void placeMossyBoulder(StructureWorldAccess world, Random random,
                                    BlockPos center, int radius) {
        double angle = random.nextDouble() * 2 * Math.PI;
        double dist  = 3 + random.nextDouble() * (radius - 4);
        int bx = center.getX() + (int)(Math.cos(angle) * dist);
        int bz = center.getZ() + (int)(Math.sin(angle) * dist);
        BlockPos surface = getSurface(world, new BlockPos(bx, center.getY(), bz));
        if (surface == null) return;

        // Основной блок + 1-2 соседних для вида кучки
        world.setBlockState(surface, Blocks.MOSSY_COBBLESTONE.getDefaultState(), 2);
        if (random.nextBoolean()) {
            world.setBlockState(surface.east(), Blocks.MOSSY_COBBLESTONE.getDefaultState(), 2);
        }
        if (random.nextBoolean()) {
            world.setBlockState(surface.up(), Blocks.MOSSY_COBBLESTONE.getDefaultState(), 2);
        }
        if (random.nextBoolean()) {
            world.setBlockState(surface.north(), Blocks.MOSSY_STONE_BRICKS.getDefaultState(), 2);
        }
    }

    /** Размещает упавшее бревно (горизонтальное) на случайной позиции. */
    private void placeFallenLog(StructureWorldAccess world, Random random,
                                 BlockPos center, int radius) {
        double angle  = random.nextDouble() * 2 * Math.PI;
        double dist   = 4 + random.nextDouble() * (radius - 5);
        int bx = center.getX() + (int)(Math.cos(angle) * dist);
        int bz = center.getZ() + (int)(Math.sin(angle) * dist);
        BlockPos surface = getSurface(world, new BlockPos(bx, center.getY(), bz));
        if (surface == null) return;

        // Горизонтальное бревно длиной 3-4 блока
        // Ориентация: случайная (X или Z)
        Direction.Axis axis = random.nextBoolean() ? Direction.Axis.X : Direction.Axis.Z;
        BlockState log = Blocks.OAK_LOG.getDefaultState()
            .with(PillarBlock.AXIS, axis);

        int logLength = 3 + random.nextInt(2);
        for (int i = 0; i < logLength; i++) {
            BlockPos logPos = axis == Direction.Axis.X
                ? surface.add(i, 0, 0)
                : surface.add(0, 0, i);
            if (world.getBlockState(logPos).isAir()) {
                world.setBlockState(logPos, log, 2);
            }
            // Мох на бревне
            if (random.nextFloat() < 0.4f) {
                BlockPos moss = logPos.up();
                if (world.getBlockState(moss).isAir()) {
                    world.setBlockState(moss, Blocks.MOSS_CARPET.getDefaultState(), 2);
                }
            }
        }
    }
}

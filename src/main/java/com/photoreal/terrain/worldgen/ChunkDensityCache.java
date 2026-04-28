package com.photoreal.terrain.worldgen;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.densityfunction.DensityFunctionTypes;

/**
 * ============================================================
 *  ChunkDensityCache — кэш плотности для одного чанка (клиент)
 * ============================================================
 *
 * ЗАДАЧА:
 * -------
 * При обходе вершин quad'a нам нужно знать плотность в 8 соседних
 * точках вокруг каждой вершины. Наивный подход — вызывать
 * SmoothDensityFunction.sample() для каждого запроса — слишком
 * дорог: 4 вершины × 8 соседей × ~4096 блоков/чанк = ~131k вызовов.
 *
 * РЕШЕНИЕ: Перед обработкой чанка предвычисляем плотность
 * в РАСШИРЕННОЙ сетке (26×26×26 = секция 16³ + 5-блочное поле вокруг)
 * и сохраняем в плоском float[]-массиве.
 *
 * Все повторные запросы — O(1) индексирование массива.
 *
 * РАЗМЕРНОСТЬ:
 * -----------
 * Мы сохраняем сетку [-1..CHUNK+4] по каждой оси (5-блочный запас),
 * чтобы рассчитывать градиент у границ чанка без артефактов.
 *
 * Итоговый размер: GRID_SIZE³ = 22³ = 10648 floats ≈ 42 KB на поток.
 */
public class ChunkDensityCache {

    // Размер кэша: 16 блоков секции + 3 блока отступа с каждой стороны
    public static final int PADDING   = 3;
    public static final int CHUNK_DIM = 16;
    public static final int GRID_SIZE = CHUNK_DIM + PADDING * 2;  // = 22

    // Плоский массив [x][y][z] → индекс
    private final float[] densities = new float[GRID_SIZE * GRID_SIZE * GRID_SIZE];

    // Начальные мировые координаты секции (угол BX,BY,BZ в мировых координатах)
    private int baseX, baseY, baseZ;
    private boolean initialized = false;

    // Singleton для текущего потока рендеринга
    private static final ThreadLocal<ChunkDensityCache> THREAD_LOCAL =
        ThreadLocal.withInitial(ChunkDensityCache::new);

    public static ChunkDensityCache get() {
        return THREAD_LOCAL.get();
    }

    /**
     * Заполняет кэш плотностей для данной секции чанка.
     * Должен вызываться один раз перед обработкой всех блоков секции.
     *
     * @param sectionX  координата секции X (chunkX * 16)
     * @param sectionY  координата секции Y (sectionY * 16)
     * @param sectionZ  координата секции Z (chunkZ * 16)
     * @param function  наша SmoothDensityFunction (или любая DensityFunction)
     */
    public void populate(int sectionX, int sectionY, int sectionZ, DensityFunction function) {
        this.baseX = sectionX - PADDING;
        this.baseY = sectionY - PADDING;
        this.baseZ = sectionZ - PADDING;
        this.initialized = true;

        // Используем SinglePointContext для минимального оверхеда
        DensityFunction.SinglePointContext ctx = new DensityFunction.SinglePointContext(0, 0, 0);

        for (int lx = 0; lx < GRID_SIZE; lx++) {
            for (int ly = 0; ly < GRID_SIZE; ly++) {
                for (int lz = 0; lz < GRID_SIZE; lz++) {
                    int wx = baseX + lx;
                    int wy = baseY + ly;
                    int wz = baseZ + lz;

                    // Создаём NoisePos для sampling функции плотности
                    double d = function.sample(new DensityFunction.SinglePointContext(wx, wy, wz));
                    densities[index(lx, ly, lz)] = (float) d;
                }
            }
        }
    }

    /**
     * Возвращает плотность в мировой точке (wx, wy, wz).
     * Если точка вне кэша — возвращает -1 (воздух).
     */
    public float getDensity(int wx, int wy, int wz) {
        if (!initialized) return -1f;

        int lx = wx - baseX;
        int ly = wy - baseY;
        int lz = wz - baseZ;

        if (lx < 0 || lx >= GRID_SIZE || ly < 0 || ly >= GRID_SIZE || lz < 0 || lz >= GRID_SIZE) {
            return -1f;
        }
        return densities[index(lx, ly, lz)];
    }

    /**
     * Интерполяция плотности в дробной точке (суббоксельные координаты).
     * Используется для вычисления градиента у вершин quad'a.
     *
     * Трилинейная интерполяция между 8 угловыми точками единичного куба.
     *
     * @param wx, wy, wz  мировые координаты (могут быть дробными: 0.0–1.0 внутри блока)
     */
    public float getDensityInterpolated(double wx, double wy, double wz) {
        int ix = (int) Math.floor(wx);
        int iy = (int) Math.floor(wy);
        int iz = (int) Math.floor(wz);

        double fx = wx - ix;
        double fy = wy - iy;
        double fz = wz - iz;

        // 8 угловых значений
        float d000 = getDensity(ix,     iy,     iz    );
        float d100 = getDensity(ix + 1, iy,     iz    );
        float d010 = getDensity(ix,     iy + 1, iz    );
        float d110 = getDensity(ix + 1, iy + 1, iz    );
        float d001 = getDensity(ix,     iy,     iz + 1);
        float d101 = getDensity(ix + 1, iy,     iz + 1);
        float d011 = getDensity(ix,     iy + 1, iz + 1);
        float d111 = getDensity(ix + 1, iy + 1, iz + 1);

        // Трилинейная интерполяция
        double dx = lerp(lerp(d000, d100, fx), lerp(d010, d110, fx), fy);
        double dy = lerp(lerp(d001, d101, fx), lerp(d011, d111, fx), fy);
        return (float) lerp(dx, dy, fz);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int index(int x, int y, int z) {
        return x * GRID_SIZE * GRID_SIZE + y * GRID_SIZE + z;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /** Сброс кэша (например, при смене секции). */
    public void invalidate() {
        this.initialized = false;
    }
}

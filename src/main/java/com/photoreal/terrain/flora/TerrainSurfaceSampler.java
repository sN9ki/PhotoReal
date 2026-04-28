package com.photoreal.terrain.flora;

import com.photoreal.terrain.worldgen.ChunkDensityCache;

/**
 * ============================================================
 *  TerrainSurfaceSampler — поиск Y изоповерхности рельефа
 * ============================================================
 *
 * ЗАДАЧА:
 * -------
 * После DGVP-смещения рельеф перестал совпадать с сеткой блоков.
 * Стебель травы или нижние вершины бревна должны касаться
 * РЕАЛЬНОЙ поверхности DGVP-меша, а не Y=blockY.
 *
 * Нам нужна функция: surfaceY(x, z) → точная высота изоповерхности
 *
 * АЛГОРИТМ: Бинарный поиск нуля плотности
 * ----------------------------------------
 * Изоповерхность рельефа — это множество точек, где D(x,y,z) = 0.
 * Для фиксированных X и Z это монотонная функция Y (грубо говоря):
 *   D < 0 (воздух) при Y > surface
 *   D > 0 (камень) при Y < surface
 *
 * Алгоритм:
 *   1. Начинаем с y_low = blockY - 4 (точно в камне)
 *      и y_high = blockY + 4 (точно в воздухе)
 *   2. Бинарный поиск MAX_ITERS итераций между ними
 *   3. Прецизия ≈ SEARCH_RANGE / 2^MAX_ITERS = 8 / 2^8 ≈ 0.03 блока (достаточно)
 *
 * ПРИМЕНЕНИЕ:
 * -----------
 * - Трава: base vertices смещаются на (surfaceY - blockY) по оси Y
 * - Бревно: нижние Ring-0 вершины прижимаются к surfaceY
 * - Цветы: стебель растёт перпендикулярно к нормали поверхности
 */
public final class TerrainSurfaceSampler {

    /** Диапазон поиска в блоках выше/ниже номинального Y блока. */
    private static final float SEARCH_RANGE = 4.0f;

    /** Количество итераций бинарного поиска. Точность: 8/2^8 ≈ 0.03 блока. */
    private static final int MAX_ITERS = 8;

    private TerrainSurfaceSampler() {}

    /**
     * Находит точную Y-координату изоповерхности рельефа в точке (wx, wz).
     *
     * @param cache   кэш плотностей секции (уже заполнен ChunkBuilderMeshingTaskMixin)
     * @param wx      мировая X (может быть дробной — центр или край блока)
     * @param nominalY номинальная Y блока травы/бревна (начало поиска)
     * @param wz      мировая Z
     * @return точная Y изоповерхности, или nominalY если поиск не сошёлся
     */
    public static float findSurfaceY(ChunkDensityCache cache, float wx, float nominalY, float wz) {
        if (!cache.isInitialized()) return nominalY;

        float yLow  = nominalY - SEARCH_RANGE;
        float yHigh = nominalY + SEARCH_RANGE;

        // Проверяем: является ли диапазон корректным (перекрывает нулевой переход)
        float dLow  = cache.getDensityInterpolated(wx, yLow,  wz);
        float dHigh = cache.getDensityInterpolated(wx, yHigh, wz);

        // Если нет смены знака в диапазоне — поверхность не в этой зоне
        // Возвращаем nominalY как fallback
        if (dLow * dHigh > 0) {
            return nominalY;
        }

        // Гарантируем: dLow > 0 (камень снизу), dHigh < 0 (воздух сверху)
        // Если наоборот — меняем местами
        if (dLow < 0) {
            float tmp = yLow; yLow = yHigh; yHigh = tmp;
            // dLow и dHigh обновятся в цикле не нужно — bisection сам разберётся
        }

        // Бинарный поиск (метод бисекции)
        for (int i = 0; i < MAX_ITERS; i++) {
            float yMid = (yLow + yHigh) * 0.5f;
            float dMid = cache.getDensityInterpolated(wx, yMid, wz);

            if (dMid > 0f) {
                yLow = yMid;   // мидпоинт в камне → поверхность выше
            } else {
                yHigh = yMid;  // мидпоинт в воздухе → поверхность ниже
            }
        }

        return (yLow + yHigh) * 0.5f;
    }

    /**
     * Вычисляет нормаль поверхности рельефа в точке (wx, surfaceY, wz).
     * Нормаль = нормализованный градиент поля плотности.
     *
     * @return float[3] { nx, ny, nz } — нормализованный вектор нормали
     */
    public static float[] surfaceNormal(ChunkDensityCache cache,
                                         float wx, float surfaceY, float wz) {
        if (!cache.isInitialized()) return new float[]{0f, 1f, 0f};

        float h = 0.5f;
        float gx = (cache.getDensityInterpolated(wx + h, surfaceY, wz)
                  - cache.getDensityInterpolated(wx - h, surfaceY, wz)) / (2f * h);
        float gy = (cache.getDensityInterpolated(wx, surfaceY + h, wz)
                  - cache.getDensityInterpolated(wx, surfaceY - h, wz)) / (2f * h);
        float gz = (cache.getDensityInterpolated(wx, surfaceY, wz + h)
                  - cache.getDensityInterpolated(wx, surfaceY, wz - h)) / (2f * h);

        float len = (float) Math.sqrt(gx*gx + gy*gy + gz*gz);
        if (len < 0.0001f) return new float[]{0f, 1f, 0f};
        return new float[]{ gx/len, gy/len, gz/len };
    }

    /**
     * Вычисляет матрицу поворота для выравнивания объекта по нормали поверхности.
     * Объект по умолчанию ориентирован вверх (локальная ось Y = [0,1,0]).
     * Матрица поворачивает [0,1,0] → surfaceNormal.
     *
     * Использует метод Rodrigues rotation:
     *   R = I + [v]× + [v]×² × (1 - cos θ) / sin² θ
     *   где v = [0,1,0] × normal, θ = arccos([0,1,0]·normal)
     *
     * @param normal  нормаль поверхности (нормализованная)
     * @return        матрица поворота 3×3, row-major (float[9])
     */
    public static float[] alignmentRotation(float[] normal) {
        float nx = normal[0], ny = normal[1], nz = normal[2];

        // Если нормаль уже совпадает с Y — возвращаем единичную матрицу
        if (Math.abs(ny - 1f) < 0.0001f) {
            return new float[]{ 1,0,0, 0,1,0, 0,0,1 };
        }

        // Если нормаль направлена вниз — поворот на 180° вокруг X
        if (Math.abs(ny + 1f) < 0.0001f) {
            return new float[]{ 1,0,0, 0,-1,0, 0,0,-1 };
        }

        // Ось вращения: v = up × normal
        float vx = -nz; // [0,1,0] × [nx,ny,nz] = [-nz, 0, nx]
        float vy = 0f;
        float vz =  nx;

        // Угол: cos θ = up·normal = ny
        float cosTheta = ny;
        float sinTheta = (float) Math.sqrt(1f - cosTheta * cosTheta);

        // Матрица Родригеса (антисимметричная матрица [v]×)
        float t = 1f - cosTheta;
        float vLen = (float) Math.sqrt(vx*vx + vz*vz);
        if (vLen > 0f) { vx /= vLen; vz /= vLen; }

        // row-major: R[row*3 + col]
        return new float[]{
            cosTheta + vx*vx*t,      vx*vy*t - vz*sinTheta,   vx*vz*t + vy*sinTheta,
            vy*vx*t + vz*sinTheta,   cosTheta + vy*vy*t,       vy*vz*t - vx*sinTheta,
            vz*vx*t - vy*sinTheta,   vz*vy*t + vx*sinTheta,   cosTheta + vz*vz*t
        };
    }

    /**
     * Применяет матрицу поворота к вектору (x, y, z).
     * @param rot матрица 3×3 row-major
     */
    public static float[] rotate(float[] rot, float x, float y, float z) {
        return new float[]{
            rot[0]*x + rot[1]*y + rot[2]*z,
            rot[3]*x + rot[4]*y + rot[5]*z,
            rot[6]*x + rot[7]*y + rot[8]*z
        };
    }
}

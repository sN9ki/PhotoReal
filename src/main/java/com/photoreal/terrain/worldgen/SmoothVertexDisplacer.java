package com.photoreal.terrain.worldgen;

/**
 * ============================================================
 *  SmoothVertexDisplacer — математика смещения вершин
 * ============================================================
 *
 * ВЫБРАННЫЙ АЛГОРИТМ: Density Gradient Vertex Projection (DGVP)
 * =============================================================
 *
 * Это лёгкая альтернатива Marching Cubes, работающая ВНУТРИ
 * существующего pipeline Minecraft (не заменяет геометрию, а смещает).
 *
 * ПРИНЦИП:
 * --------
 * Для каждой вершины (x, y, z) на поверхности блока:
 *
 *   1. Вычисляем градиент поля плотности ∇D методом центральных разностей:
 *      ∇D = ( D(x+h, y, z) - D(x-h, y, z) ) / 2h   (по X)
 *           ( D(x, y+h, z) - D(x, y-h, z) ) / 2h   (по Y)
 *           ( D(x, y, z+h) - D(x, y, z-h) ) / 2h   (по Z)
 *
 *   2. Вычисляем «шаг» к изоповерхности (где D=0):
 *      t = D(x, y, z) / |∇D|²
 *      offset = -t × ∇D
 *
 *      Это одна итерация метода Ньютона для уравнения D(p) = 0.
 *      Для линейного поля — точное решение, для нелинейного — отличная аппроксимация.
 *
 *   3. Применяем смещение с clamp:
 *      p' = p + clamp(offset, -MAX_SHIFT, +MAX_SHIFT)
 *
 * ПОЧЕМУ НЕ MARCHING CUBES:
 * -------------------------
 * Marching Cubes полностью заменяет геометрию (создаёт треугольники вместо квадов).
 * Это требует переписывания всего Sodium mesh builder — очень сложно и несовместимо
 * с большинством других модов.
 *
 * DGVP работает в рамках существующих quad'ов — просто сдвигает 4 вершины.
 * Результат визуально похож на smooth mesh при малых шагах плотности.
 *
 * НОРМАЛИ:
 * --------
 * Нормаль в любой точке изоповерхности = нормализованный градиент ∇D.
 * После вычисления ∇D для каждой вершины — нормализуем → получаем
 * физически корректную нормаль для освещения кривых поверхностей.
 *
 * ПРОИЗВОДИТЕЛЬНОСТЬ:
 * -------------------
 * - Все значения плотности берутся из ChunkDensityCache (O(1))
 * - Центральные разности = 6 кэш-обращений на вершину
 * - 4 вершины/квад = 24 обращения → ~24 float-чтения из массива
 * - Нет аллокаций (все промежуточные значения — локальные переменные)
 */
public final class SmoothVertexDisplacer {

    // Шаг конечных разностей для градиента (в блоках)
    private static final float H = 0.5f;

    // Максимальное смещение вершины (не даём выйти за пределы смежных блоков)
    public static final float MAX_SHIFT = 0.45f;

    // Минимальная длина градиента (защита от деления на ноль)
    private static final float MIN_GRADIENT_SQ = 0.001f;

    // Порог плотности для применения смещения (только у поверхности)
    // Вершины глубоко в камне или глубоко в воздухе — не смещаем
    private static final float DENSITY_THRESHOLD = 1.2f;

    /** Результат обработки вершины (избегаем аллокаций — используем reuse-объект). */
    public static final class VertexResult {
        public float x, y, z;
        public float nx, ny, nz; // нормаль

        /** Применяет смещение к базовым координатам. */
        void set(float bx, float by, float bz, float dx, float dy, float dz,
                 float gnx, float gny, float gnz) {
            this.x = bx + clamp(dx, -MAX_SHIFT, MAX_SHIFT);
            this.y = by + clamp(dy, -MAX_SHIFT, MAX_SHIFT);
            this.z = bz + clamp(dz, -MAX_SHIFT, MAX_SHIFT);
            this.nx = gnx;
            this.ny = gny;
            this.nz = gnz;
        }
    }

    /**
     * Процессирует одну вершину quad'а — вычисляет смещение и нормаль.
     *
     * @param cache   кэш плотностей для текущей секции
     * @param wx      мировая X-координата вершины (может быть дробной: 0.0–1.0 внутри блока)
     * @param wy      мировая Y-координата вершины
     * @param wz      мировая Z-координата вершины
     * @param result  объект-результат (reuse, без аллокаций)
     */
    public static void processVertex(ChunkDensityCache cache,
                                     float wx, float wy, float wz,
                                     VertexResult result) {
        // 1. Плотность в данной точке
        float density = cache.getDensityInterpolated(wx, wy, wz);

        // Если далеко от поверхности — не трогаем (оптимизация)
        if (Math.abs(density) > DENSITY_THRESHOLD) {
            result.x = wx;
            result.y = wy;
            result.z = wz;
            // Флат-нормаль (будет переопределена face normal'ем Sodium)
            result.nx = 0f;
            result.ny = 1f;
            result.nz = 0f;
            return;
        }

        // 2. Центрально-разностный градиент ∇D (6 кэш-обращений)
        float gx = (cache.getDensityInterpolated(wx + H, wy,     wz    )
                  - cache.getDensityInterpolated(wx - H, wy,     wz    )) / (2f * H);
        float gy = (cache.getDensityInterpolated(wx,     wy + H, wz    )
                  - cache.getDensityInterpolated(wx,     wy - H, wz    )) / (2f * H);
        float gz = (cache.getDensityInterpolated(wx,     wy,     wz + H)
                  - cache.getDensityInterpolated(wx,     wy,     wz - H)) / (2f * H);

        float gradSq = gx * gx + gy * gy + gz * gz;

        // 3. Смещение к изоповерхности (метод Ньютона)
        //    t = D / |∇D|²
        //    offset = -t × ∇D = -(D / |∇D|²) × ∇D
        float dx = 0f, dy = 0f, dz = 0f;
        float nx, ny, nz;

        if (gradSq > MIN_GRADIENT_SQ) {
            float t = density / gradSq;
            dx = -t * gx;
            dy = -t * gy;
            dz = -t * gz;

            // 4. Нормаль = нормализованный градиент в СДВИНУТОЙ точке
            //    Это физически точная нормаль для изоповерхности поля плотности
            float atX = wx + dx;
            float atY = wy + dy;
            float atZ = wz + dz;

            float ngx = (cache.getDensityInterpolated(atX + H, atY,     atZ    )
                       - cache.getDensityInterpolated(atX - H, atY,     atZ    )) / (2f * H);
            float ngy = (cache.getDensityInterpolated(atX,     atY + H, atZ    )
                       - cache.getDensityInterpolated(atX,     atY - H, atZ    )) / (2f * H);
            float ngz = (cache.getDensityInterpolated(atX,     atY,     atZ + H)
                       - cache.getDensityInterpolated(atX,     atY,     atZ - H)) / (2f * H);

            float len = (float) Math.sqrt(ngx * ngx + ngy * ngy + ngz * ngz);
            if (len > 0.0001f) {
                nx = ngx / len;
                ny = ngy / len;
                nz = ngz / len;
            } else {
                nx = 0f; ny = 1f; nz = 0f;
            }
        } else {
            // Градиент слишком мал (плоская зона) — нормаль по Y
            nx = 0f; ny = 1f; nz = 0f;
        }

        result.set(wx, wy, wz, dx, dy, dz, nx, ny, nz);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}

package com.photoreal.terrain.flora;

import com.photoreal.terrain.worldgen.ChunkDensityCache;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.Direction;

/**
 * ============================================================
 *  TerrainAlignedCross — трава и цветы, прижатые к DGVP-рельефу
 * ============================================================
 *
 * СТАНДАРТНАЯ ТРАВА (ванилья):
 * ----------------------------
 * Ванильная трава — два crossed quad'а под 45° с плоским основанием на Y=0.
 * Проблема: основание (4 нижних вершины) находится на уровне сетки блоков,
 * тогда как DGVP сдвинул поверхность земли.
 *
 * НАША ТРАВА:
 * -----------
 * 1. Сохраняем крест из двух квадов (гарантированно billboard-ready)
 * 2. Для каждой из 4 нижних вершин вычисляем surfaceY через TerrainSurfaceSampler
 * 3. Опционально: поворачиваем весь объект по матрице выравнивания,
 *    если уклон поверхности > TILT_THRESHOLD (трава не стоит вертикально
 *    на крутом склоне — она наклоняется)
 *
 * ГЕОМЕТРИЯ КВАДА ТРАВЫ:
 * ----------------------
 *
 *   v2 ──────── v3     ← верх (Y = surfaceY + GRASS_HEIGHT)
 *   │            │
 *   │    /\/\    │     ← текстура травы
 *   │            │
 *   v0 ──────── v1     ← низ (Y = surfaceY[i])
 *
 * Первый квад: X-ось (от -0.5 до +0.5 по X, constant Z=0.5)
 * Второй квад: Z-ось (от -0.5 до +0.5 по Z, constant X=0.5)
 *
 * BILBOARDING (для листвы):
 * -------------------------
 * Отдельный метод emitLeafCluster() создаёт кластер из 6 случайно
 * ориентированных alpha-квадов — симулирует объёмную листву.
 * Ориентация рассчитывается из BlockPos.seed() для детерминизма.
 */
public final class TerrainAlignedCross {

    public static final float GRASS_HEIGHT = 0.9f;  // Высота стебля травы
    public static final float TILT_THRESHOLD = 0.35f; // cos угла наклона для tilting

    // Смещения 4-х нижних вершин двух crossed квадов
    // В локальных координатах блока [0..1]
    // Quad A (вдоль X): (x0,z=0.5), (x1,z=0.5)
    // Quad B (вдоль Z): (x=0.5,z0), (x=0.5,z1)
    private static final float[][] BOTTOM_XZ = {
        {0.1f, 0.5f}, {0.9f, 0.5f},   // Quad A: левый-нижний, правый-нижний
        {0.5f, 0.1f}, {0.5f, 0.9f},   // Quad B: передний-нижний, задний-нижний
    };

    private TerrainAlignedCross() {}

    /**
     * Emits a terrain-aligned cross mesh for grass/flowers.
     *
     * @param emitter       Fabric FRAPI QuadEmitter
     * @param sprite        текстура травы/цветка
     * @param cache         кэш плотности текущей секции
     * @param worldX        мировая X блока (floor)
     * @param worldY        номинальная Y блока (floor) — используем как стартовую точку поиска
     * @param worldZ        мировая Z блока (floor)
     * @param tint          цвет биома (0xRRGGBB), -1 если без окраски
     * @param tiltToNormal  если true — наклоняем стебель по нормали поверхности
     */
    public static void emit(QuadEmitter emitter,
                            Sprite sprite,
                            ChunkDensityCache cache,
                            float worldX, float worldY, float worldZ,
                            int tint,
                            boolean tiltToNormal) {

        // ── Находим Y поверхности под каждой нижней вершиной ─────────────────
        float[] bottomY = new float[4];
        for (int i = 0; i < 4; i++) {
            float wx = worldX + BOTTOM_XZ[i][0];
            float wz = worldZ + BOTTOM_XZ[i][1];
            float sy = TerrainSurfaceSampler.findSurfaceY(cache, wx, worldY, wz);
            bottomY[i] = sy - worldY; // локальная Y (относительно начала блока)
        }

        // ── Нормаль поверхности для выравнивания наклона ─────────────────────
        float[] rot = null;
        if (tiltToNormal) {
            float[] norm = TerrainSurfaceSampler.surfaceNormal(
                cache, worldX + 0.5f, worldY, worldZ + 0.5f
            );
            // Наклоняем только если поверхность достаточно крутая
            if (norm[1] < (1f - TILT_THRESHOLD)) {
                rot = TerrainSurfaceSampler.alignmentRotation(norm);
            }
        }

        // ── Quad A: вдоль X (z = 0.5, переменный x) ──────────────────────────
        emitCrossQuad(emitter, sprite, tint, rot,
            // Нижний-левый                    Нижний-правый
            0.1f, bottomY[0], 0.5f,           0.9f, bottomY[1], 0.5f,
            // Верхний-правый                  Верхний-левый
            0.9f, bottomY[1] + GRASS_HEIGHT, 0.5f,  0.1f, bottomY[0] + GRASS_HEIGHT, 0.5f
        );

        // ── Quad B: вдоль Z (x = 0.5, переменный z) ──────────────────────────
        emitCrossQuad(emitter, sprite, tint, rot,
            0.5f, bottomY[2], 0.1f,           0.5f, bottomY[3], 0.9f,
            0.5f, bottomY[3] + GRASS_HEIGHT, 0.9f,  0.5f, bottomY[2] + GRASS_HEIGHT, 0.1f
        );
    }

    /**
     * Испускает один квад кросс-меша с опциональным поворотом по матрице.
     */
    private static void emitCrossQuad(QuadEmitter emitter, Sprite sprite, int tint,
                                       float[] rot,
                                       float x0, float y0, float z0,
                                       float x1, float y1, float z1,
                                       float x2, float y2, float z2,
                                       float x3, float y3, float z3) {
        // Применяем поворот если нужно (вокруг центра блока [0.5, 0, 0.5])
        if (rot != null) {
            float[] v0 = applyRotCentered(rot, x0, y0, z0);
            float[] v1 = applyRotCentered(rot, x1, y1, z1);
            float[] v2 = applyRotCentered(rot, x2, y2, z2);
            float[] v3 = applyRotCentered(rot, x3, y3, z3);
            x0=v0[0]; y0=v0[1]; z0=v0[2];
            x1=v1[0]; y1=v1[1]; z1=v1[2];
            x2=v2[0]; y2=v2[1]; z2=v2[2];
            x3=v3[0]; y3=v3[1]; z3=v3[2];
        }

        // UV: getFrameU/V принимают значения 0–16 (пиксели в 16×16 спрайте)
        float u0  = sprite.getFrameU(0);  float u1  = sprite.getFrameU(16);
        float vBot = sprite.getFrameV(16); float vTop = sprite.getFrameV(0);

        emitter.pos(0, x0, y0, z0).uv(0, u0, vBot);
        emitter.pos(1, x1, y1, z1).uv(1, u1, vBot);
        emitter.pos(2, x2, y2, z2).uv(2, u1, vTop);
        emitter.pos(3, x3, y3, z3).uv(3, u0, vTop);

        // Цвета вершин: -1 = 0xFFFFFFFF = белый (показываем текстуру без затемнения)
        if (tint != -1) {
            emitter.spriteColor(0, tint, tint, tint, tint);
        } else {
            emitter.color(0, -1); emitter.color(1, -1);
            emitter.color(2, -1); emitter.color(3, -1);
        }

        // nominalFace = UP для корректного AO и освещения
        emitter.nominalFace(Direction.UP);
        emitter.cullFace(null);
        emitter.emit();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ЛИСТВА: кластер из случайно ориентированных квадов
    //  Используется для oak_leaves, birch_leaves и т.д.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Испускает кластер из nQuads случайно ориентированных листвяных квадов.
     * Детерминированный seed из blockPos гарантирует одинаковый вид при перегенерации.
     *
     * @param emitter     QuadEmitter
     * @param sprite      текстура листа
     * @param tint        цвет биома
     * @param posX/Y/Z   локальные координаты центра блока
     * @param seed        детерминированный seed (из BlockPos.asLong())
     * @param nQuads      количество квадов (рекомендуется 6–10)
     * @param spread      радиус разброса от центра (обычно 0.4f)
     */
    public static void emitLeafCluster(QuadEmitter emitter, Sprite sprite, int tint,
                                        float posX, float posY, float posZ,
                                        long seed, int nQuads, float spread) {
        // Простой LCG-генератор (без аллокаций, детерминированный)
        long rng = seed ^ 0x6A09E667F3BCC908L;

        float u0 = sprite.getMinU(), u1 = sprite.getMaxU();
        float v0 = sprite.getMinV(), v1 = sprite.getMaxV();
        float halfSize = 0.45f;

        for (int q = 0; q < nQuads; q++) {
            // Случайный центр квада внутри spread-сферы
            rng = lcg(rng); float cx = posX + (((rng >> 16) & 0xFF) / 127.5f - 1f) * spread;
            rng = lcg(rng); float cy = posY + (((rng >> 16) & 0xFF) / 127.5f - 1f) * spread;
            rng = lcg(rng); float cz = posZ + (((rng >> 16) & 0xFF) / 127.5f - 1f) * spread;

            // Случайная ориентация (2 угла Эйлера)
            rng = lcg(rng); float yaw   = (rng & 0xFFFF) / 65535f * 2f * (float)Math.PI;
            rng = lcg(rng); float pitch = (rng & 0xFFFF) / 65535f * (float)Math.PI - (float)Math.PI/2f;

            float cosY = (float)Math.cos(yaw);
            float sinY = (float)Math.sin(yaw);
            float cosP = (float)Math.cos(pitch);
            float sinP = (float)Math.sin(pitch);

            // Локальные оси квада (right и up в 3D)
            float rx = cosY,   ry = 0f,   rz = -sinY;            // right
            float ux = sinY*sinP, uy = cosP, uz = cosY*sinP;     // up

            // 4 вершины квада: center ± halfSize*right ± halfSize*up
            emitter.pos(0, cx - halfSize*rx - halfSize*ux, cy - halfSize*ry - halfSize*uy, cz - halfSize*rz - halfSize*uz).uv(0, u0, v1);
            emitter.pos(1, cx + halfSize*rx - halfSize*ux, cy + halfSize*ry - halfSize*uy, cz + halfSize*rz - halfSize*uz).uv(1, u1, v1);
            emitter.pos(2, cx + halfSize*rx + halfSize*ux, cy + halfSize*ry + halfSize*uy, cz + halfSize*rz + halfSize*uz).uv(2, u1, v0);
            emitter.pos(3, cx - halfSize*rx + halfSize*ux, cy - halfSize*ry + halfSize*uy, cz - halfSize*rz + halfSize*uz).uv(3, u0, v0);

            // Нормаль = cross(right, up)
            float nx = ry*uz - rz*uy;
            float ny = rz*ux - rx*uz;
            float nz = rx*uy - ry*ux;
            emitter.normal(0, nx, ny, nz); emitter.normal(1, nx, ny, nz);
            emitter.normal(2, nx, ny, nz); emitter.normal(3, nx, ny, nz);

            if (tint != -1) {
                emitter.spriteColor(0, tint, tint, tint, tint);
            } else {
                emitter.color(0, -1); emitter.color(1, -1);
                emitter.color(2, -1); emitter.color(3, -1);
            }
            emitter.nominalFace(Direction.UP);
            emitter.cullFace(null);
            emitter.emit();
        }
    }

    // ─── LCG для детерминированного RNG без аллокаций ────────────────────────
    private static long lcg(long seed) {
        return seed * 6364136223846793005L + 1442695040888963407L;
    }

    // ─── Поворот вектора вокруг [0.5, 0, 0.5] центра блока ──────────────────
    private static float[] applyRotCentered(float[] rot, float x, float y, float z) {
        float dx = x - 0.5f, dz = z - 0.5f;
        float[] r = TerrainSurfaceSampler.rotate(rot, dx, y, dz);
        return new float[]{ r[0] + 0.5f, r[1], r[2] + 0.5f };
    }
}

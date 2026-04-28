package com.photoreal.terrain.flora;

import net.fabricmc.fabric.api.renderer.v1.mesh.MeshBuilder;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.Direction;

/**
 * ============================================================
 *  ProceduralCylinder — процедурный цилиндр для стволов деревьев
 * ============================================================
 *
 * ГЕОМЕТРИЯ:
 * ----------
 * Октагональный (8-сторонний) цилиндр. Это оптимальный баланс
 * для деревьев: выглядит круглым с нормального расстояния,
 * но имеет всего 8×2=16 уникальных вершин.
 *
 * Структура:
 *   - 2 кольца вершин: Ring 0 (низ) и Ring 1 (верх)
 *   - 8 боковых квадов (между рингами)
 *   - 1 верхняя крышка (разбита на 6 треугольников → 6 вырожденных квадов)
 *   - Ring 0 вершины выровнены по TerrainSurfaceSampler
 *
 * КООРДИНАТЫ ВЕРШИН ОКТАГОНА:
 * ---------------------------
 * Угол i = (2π/8) * i = 45°×i
 * x_i = cos(45°×i) × radius
 * z_i = sin(45°×i) × radius
 *
 * ВЫРАВНИВАНИЕ ПО РЕЛЬЕФУ:
 * ------------------------
 * 1. Находим surfaceY для каждого угола октагона (xCenter+x_i, zCenter+z_i)
 * 2. Ring 0 каждой боковой грани получает Y = surfaceY в этой угловой точке
 * 3. Ring 1 = Ring 0 + LOG_HEIGHT
 *
 * Это создаёт органическое "прорастание" ствола из изогнутой почвы.
 *
 * UV-МАППИНГ:
 * ----------
 * Боковые грани: U = [0..1] вдоль периметра, V = [0..1] вдоль высоты
 * Крышка: круговая проекция
 */
public final class ProceduralCylinder {

    /** Радиус ствола дерева (в долях блока). */
    public static final float RADIUS = 0.25f;

    /** Высота одного сегмента (Y одного блока бревна). */
    public static final float SEGMENT_HEIGHT = 1.0f;

    /** Количество сторон октагона. */
    public static final int SIDES = 8;

    // Предвыисленные trig-значения для SIDES=8
    private static final float[] COS = new float[SIDES];
    private static final float[] SIN = new float[SIDES];

    static {
        for (int i = 0; i < SIDES; i++) {
            double angle = 2.0 * Math.PI * i / SIDES;
            COS[i] = (float) Math.cos(angle);
            SIN[i] = (float) Math.sin(angle);
        }
    }

    private ProceduralCylinder() {}

    /**
     * Emits a full log cylinder into the Fabric Rendering API QuadEmitter.
     *
     * @param emitter       Fabric FRAPI QuadEmitter (поддерживается Sodium через Indium)
     * @param barkSprite    текстура коры (боковая поверхность)
     * @param capSprite     текстура среза (верхняя крышка)
     * @param cache         кэш плотности для выравнивания по рельефу
     * @param worldX        мировая X центра блока
     * @param worldY        мировая Y основания блока (номинальная)
     * @param worldZ        мировая Z центра блока
     * @param centerX       локальная X относительно начала блока (обычно 0.5)
     * @param centerZ       локальная Z относительно начала блока (обычно 0.5)
     * @param alignToTerrain  если true — Ring 0 прижимается к изоповерхности DGVP
     */
    public static void emit(QuadEmitter emitter,
                            Sprite barkSprite,
                            Sprite capSprite,
                            com.photoreal.terrain.worldgen.ChunkDensityCache cache,
                            float worldX, float worldY, float worldZ,
                            float centerX, float centerZ,
                            boolean alignToTerrain) {

        // ── Вычисляем Y основания для каждого угла октагона ─────────────────
        float[] baseY = new float[SIDES];
        if (alignToTerrain && cache.isInitialized()) {
            for (int i = 0; i < SIDES; i++) {
                float vx = worldX + centerX + COS[i] * RADIUS;
                float vz = worldZ + centerZ + SIN[i] * RADIUS;
                // Бинарный поиск изоповерхности в этой угловой точке
                baseY[i] = TerrainSurfaceSampler.findSurfaceY(cache, vx, worldY, vz) - worldY;
            }
        } else {
            for (int i = 0; i < SIDES; i++) baseY[i] = 0f;
        }

        // ── Боковые грани цилиндра ───────────────────────────────────────────
        emitSides(emitter, barkSprite, baseY, centerX, centerZ);

        // ── Верхняя крышка ───────────────────────────────────────────────────
        emitTopCap(emitter, capSprite, baseY, centerX, centerZ);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  БОКОВЫЕ ГРАНИ: 8 квадов, каждый соединяет два соседних угла октагона
    // ─────────────────────────────────────────────────────────────────────────

    private static void emitSides(QuadEmitter emitter, Sprite sprite,
                                   float[] baseY, float cx, float cz) {
        for (int i = 0; i < SIDES; i++) {
            int next = (i + 1) % SIDES;

            // 4 вершины бокового квада (против часовой стрелки снаружи)
            float x0 = cx + COS[i]    * RADIUS;  float z0 = cz + SIN[i]    * RADIUS;
            float x1 = cx + COS[next] * RADIUS;  float z1 = cz + SIN[next] * RADIUS;
            float y0 = baseY[i];
            float y1 = baseY[next];
            float y0top = y0 + SEGMENT_HEIGHT;
            float y1top = y1 + SEGMENT_HEIGHT;

            // Нормаль грани = осреднённая нормаль двух боковых углов
            float nx = (COS[i] + COS[next]) * 0.5f;
            float nz = (SIN[i] + SIN[next]) * 0.5f;
            float nLen = (float) Math.sqrt(nx*nx + nz*nz);
            nx /= nLen; nz /= nLen;

            // UV: getFrameU/V принимают 0–16 (пиксельные координаты в 16×16-спрайте)
            float u0 = sprite.getFrameU((float) i / SIDES * 16);
            float u1 = sprite.getFrameU((float)(i + 1) / SIDES * 16);
            float vLow  = sprite.getFrameV(0);
            float vHigh = sprite.getFrameV(16);

            emitter.pos(0, x0, y0,    z0).uv(0, u0, vLow ).normal(0, nx, 0f, nz);
            emitter.pos(1, x1, y1,    z1).uv(1, u1, vLow ).normal(1, nx, 0f, nz);
            emitter.pos(2, x1, y1top, z1).uv(2, u1, vHigh).normal(2, nx, 0f, nz);
            emitter.pos(3, x0, y0top, z0).uv(3, u0, vHigh).normal(3, nx, 0f, nz);
            // Цвета вершин: -1 = 0xFFFFFFFF = белый (без затемнения)
            emitter.color(0, -1); emitter.color(1, -1);
            emitter.color(2, -1); emitter.color(3, -1);
            emitter.nominalFace(Direction.NORTH);
            emitter.emit();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ВЕРХНЯЯ КРЫШКА: fan-decomposition через деление на квады
    //  (FRAPI emitter работает только с квадами, не с треугольниками)
    // ─────────────────────────────────────────────────────────────────────────

    private static void emitTopCap(QuadEmitter emitter, Sprite sprite,
                                    float[] baseY, float cx, float cz) {
        float topY = SEGMENT_HEIGHT; // крышка всегда на высоте одного блока

        // Разбиваем октагон на 4 квада (fan из центра, по 2 сектора на квад)
        for (int i = 0; i < SIDES; i += 2) {
            int a = i;
            int b = (i + 1) % SIDES;
            int c = (i + 2) % SIDES;

            float x0 = cx;                          float z0 = cz;               // центр
            float x1 = cx + COS[a] * RADIUS;        float z1 = cz + SIN[a] * RADIUS;
            float x2 = cx + COS[b] * RADIUS;        float z2 = cz + SIN[b] * RADIUS;
            float x3 = cx + COS[c] * RADIUS;        float z3 = cz + SIN[c] * RADIUS;

            // UV круговая проекция, значения 0–16
            float u0 = sprite.getFrameU(8);                              float v0 = sprite.getFrameV(8);
            float u1 = sprite.getFrameU((COS[a] + 1f) * 8);             float v1 = sprite.getFrameV((SIN[a] + 1f) * 8);
            float u2 = sprite.getFrameU((COS[b] + 1f) * 8);             float v2 = sprite.getFrameV((SIN[b] + 1f) * 8);
            float u3 = sprite.getFrameU((COS[c] + 1f) * 8);             float v3 = sprite.getFrameV((SIN[c] + 1f) * 8);

            emitter.pos(0, x0, topY, z0).uv(0, u0, v0).normal(0, 0f, 1f, 0f);
            emitter.pos(1, x1, topY, z1).uv(1, u1, v1).normal(1, 0f, 1f, 0f);
            emitter.pos(2, x2, topY, z2).uv(2, u2, v2).normal(2, 0f, 1f, 0f);
            emitter.pos(3, x3, topY, z3).uv(3, u3, v3).normal(3, 0f, 1f, 0f);
            emitter.color(0, -1); emitter.color(1, -1);
            emitter.color(2, -1); emitter.color(3, -1);
            emitter.nominalFace(Direction.UP);
            emitter.emit();
        }
    }
}

package com.photoreal.terrain.util;

/**
 * ============================================================
 *  NormalEncoder — кодирование нормалей в формат Sodium
 * ============================================================
 *
 * Sodium 0.5.3 хранит нормали вершин в компактном формате:
 * 4 байта, упакованных в int32 (совпадает с GL_INT_2_10_10_10_REV).
 *
 * Биты:
 *   [31:22] X: 10-битное знаковое целое (диапазон [-512, 511])
 *   [21:12] Y: 10-битное знаковое целое
 *   [11: 2] Z: 10-битное знаковое целое
 *   [ 1: 0] W: 2-битное (всегда 0b01 для нормалей = 1.0)
 *
 * АКТУАЛЬНЫЙ ФОРМАТ (Sodium 0.5.3, ChunkVertexType):
 * ---------------------------------------------------
 * Нормаль в ChunkVertexEncoder.Vertex — три отдельных byte-поля:
 *   vertex.nx = (byte)(nx * 127)
 *   vertex.ny = (byte)(ny * 127)
 *   vertex.nz = (byte)(nz * 127)
 *
 * Это signed byte (-128..127) соответствующий float (-1.0..1.0).
 * 127 → 1.0, -127 → -1.0.
 *
 * ИСПОЛЬЗОВАНИЕ В MIКСИНЕ:
 * -------------------------
 * При перехвате vertex-записи в BlockRenderer мы заменяем стандартные
 * chunk-face нормали (например, [0,1,0] для TOP) на градиент-нормали
 * из SmoothVertexDisplacer.VertexResult.
 *
 * Кодирование выполняется один раз на вершину — никаких аллокаций.
 */
public final class NormalEncoder {

    private NormalEncoder() {}

    /**
     * Кодирует float-нормаль в signed byte для Sodium ChunkVertexEncoder.
     * Input должен быть нормализован (|n| ≈ 1.0).
     *
     * @param component  X, Y или Z компонент нормали (-1.0..1.0)
     * @return           Закодированный signed byte (-127..127)
     */
    public static byte encodeByte(float component) {
        // Clamp для защиты от чуть-выходящих-за-диапазон значений
        float clamped = Math.max(-1.0f, Math.min(1.0f, component));
        return (byte) Math.round(clamped * 127.0f);
    }

    /**
     * Кодирует нормаль в формат GL_INT_2_10_10_10_REV (для альтернативного пути).
     * Используется если Sodium хранит нормаль как упакованный int.
     *
     * Формат: каждый компонент — 10-битное знаковое целое ([-511, 511]),
     * W-компонент — 2 бита (фиксирован в 1).
     *
     * @param nx, ny, nz  нормализованные компоненты нормали
     * @return             упакованный int32
     */
    public static int encodePackedInt(float nx, float ny, float nz) {
        int ix = clampSnorm(nx, 511);
        int iy = clampSnorm(ny, 511);
        int iz = clampSnorm(nz, 511);
        int iw = 1; // W = 1.0

        return (ix & 0x3FF)
            | ((iy & 0x3FF) << 10)
            | ((iz & 0x3FF) << 20)
            | ((iw & 0x003) << 30);
    }

    /**
     * Преобразует float (-1..1) в знаковое целое в диапазоне [-max, max].
     */
    private static int clampSnorm(float f, int max) {
        return Math.max(-max, Math.min(max, Math.round(f * max)));
    }

    /**
     * Декодирует нормаль из GL_INT_2_10_10_10_REV обратно в float.
     * Полезно для дебага и верификации round-trip.
     */
    public static float[] decodePackedInt(int packed) {
        // Извлекаем 10-битные знаковые компоненты
        int ix = signExtend10(packed & 0x3FF);
        int iy = signExtend10((packed >> 10) & 0x3FF);
        int iz = signExtend10((packed >> 20) & 0x3FF);
        return new float[]{ ix / 511.0f, iy / 511.0f, iz / 511.0f };
    }

    /**
     * Знаковое расширение 10-битного значения до 32-битного int.
     */
    private static int signExtend10(int value) {
        if ((value & 0x200) != 0) {
            return value | 0xFFFFFC00;
        }
        return value;
    }

    /**
     * Нормализация вектора (inline, без аллокаций).
     * Возвращает false если вектор нулевой.
     */
    public static boolean normalize(float[] v) {
        float len = (float) Math.sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
        if (len < 1e-6f) return false;
        v[0] /= len;
        v[1] /= len;
        v[2] /= len;
        return true;
    }
}

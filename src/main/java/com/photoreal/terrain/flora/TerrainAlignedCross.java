package com.photoreal.terrain.flora;

import com.photoreal.terrain.worldgen.ChunkDensityCache;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.Direction;

/**
 * ============================================================
 *  TerrainAlignedCross — Grass, flowers, and leaf clusters
 * ============================================================
 *
 * GRASS / FLOWER:
 * ---------------
 * Two crossed quads whose bottom vertices are terrain-aligned via
 * TerrainSurfaceSampler. Optionally tilted to follow the surface normal.
 *
 * LEAF CLUSTER:
 * -------------
 * A set of randomly oriented, scaled, and displaced billboard quads that
 * merge into a continuous fluffy canopy across adjacent leaf blocks.
 * See emitLeafCluster() for full documentation.
 *
 * WINDING CONVENTION:
 * -------------------
 * All quads use FRAPI's required CCW winding (viewed from the face's
 * normal direction):
 *
 *   v3 ─── v2      ← top
 *   │        │
 *   v0 ─── v1      ← bottom
 *
 * CULLING:
 * --------
 * Every quad — grass, flower, and leaf — sets cullFace(null) to prevent
 * Sodium and vanilla from discarding faces at chunk/section borders.
 */
public final class TerrainAlignedCross {

    public static final float GRASS_HEIGHT  = 0.9f;   // Stem height
    public static final float TILT_THRESHOLD = 0.35f; // cos(angle) for slope tilt

    // Bottom-vertex XZ positions of the two crossed quads (local block coords)
    private static final float[][] BOTTOM_XZ = {
        {0.1f, 0.5f}, {0.9f, 0.5f},   // Quad A (along X): left, right
        {0.5f, 0.1f}, {0.5f, 0.9f},   // Quad B (along Z): front, back
    };

    private TerrainAlignedCross() {}

    // ─────────────────────────────────────────────────────────────────────────
    //  LUSH PLANT GENERATOR (Grass / Flower)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emits a lush, dense "bush" model for grass and flowers instead of a vanilla cross.
     * Uses 3 quads rotated at 0, 60, and 120 degrees with randomized scale and offset.
     * Vertex normals are forced UP for soft, fluffy foliage lighting.
     *
     * @param emitter       Fabric FRAPI QuadEmitter
     * @param sprite        Grass / flower texture
     * @param cache         Chunk density cache (unused in lush mode, kept for signature)
     * @param worldX        Integer world X of the block
     * @param worldY        Integer world Y (nominal block floor)
     * @param worldZ        Integer world Z of the block
     * @param tint          Biome tint (0xRRGGBB) or -1 for none
     * @param tiltToNormal  Unused in lush mode (we sink it into the ground instead)
     */
    public static void emit(QuadEmitter emitter,
                            Sprite sprite,
                            ChunkDensityCache cache,
                            float worldX, float worldY, float worldZ,
                            int tint,
                            boolean tiltToNormal) {

        long seed = new net.minecraft.util.math.BlockPos((int)worldX, (int)worldY, (int)worldZ).asLong();
        long rng = lcg(seed ^ 0x3141592653589793L);

        // Scale: 0.75 to 1.3
        rng = lcg(rng);
        float scale = 0.75f + ((rng & 0xFFFF) / 65535f) * 0.55f; 

        // Offset X/Z: +/- 0.3
        rng = lcg(rng);
        float offsetX = (((rng >> 16) & 0xFF) / 127.5f - 1f) * 0.3f;
        rng = lcg(rng);
        float offsetZ = (((rng >> 16) & 0xFF) / 127.5f - 1f) * 0.3f;
        
        // Offset Y: -0.1 to -0.25 (sinks into the ground to prevent floating)
        rng = lcg(rng);
        float offsetY = -0.10f - ((rng & 0xFFFF) / 65535f) * 0.15f;

        float width = scale;
        float height = GRASS_HEIGHT * scale;
        
        float cx = 0.5f + offsetX;
        float cy = offsetY;
        float cz = 0.5f + offsetZ;

        float halfW = width / 2.0f;
        
        float uLeft = sprite.getFrameU(0f);
        float uRight = sprite.getFrameU(16f);
        float vBot = sprite.getFrameV(16f);
        float vTop = sprite.getFrameV(0f);

        // Generate 3 quads rotated at 0, 60, 120 degrees
        for (int i = 0; i < 3; i++) {
            float angle = (float) (i * Math.PI / 3.0);
            float dx = (float) Math.cos(angle) * halfW;
            float dz = (float) Math.sin(angle) * halfW;

            // v0: bottom-left
            float x0 = cx - dx; float y0 = cy;          float z0 = cz - dz;
            // v1: bottom-right
            float x1 = cx + dx; float y1 = cy;          float z1 = cz + dz;
            // v2: top-right
            float x2 = cx + dx; float y2 = cy + height; float z2 = cz + dz;
            // v3: top-left
            float x3 = cx - dx; float y3 = cy + height; float z3 = cz - dz;

            // FRONT FACE
            emitter.pos(0, x0, y0, z0).uv(0, uLeft, vBot).normal(0, 0f, 1f, 0f);
            emitter.pos(1, x1, y1, z1).uv(1, uRight, vBot).normal(1, 0f, 1f, 0f);
            emitter.pos(2, x2, y2, z2).uv(2, uRight, vTop).normal(2, 0f, 1f, 0f);
            emitter.pos(3, x3, y3, z3).uv(3, uLeft, vTop).normal(3, 0f, 1f, 0f);
            applyTint(emitter, tint);
            emitter.nominalFace(Direction.UP);
            emitter.cullFace(null);
            emitter.emit();

            // BACK FACE (reversed winding, soft UP normal retained)
            emitter.pos(0, x3, y3, z3).uv(0, uLeft, vTop).normal(0, 0f, 1f, 0f);
            emitter.pos(1, x2, y2, z2).uv(1, uRight, vTop).normal(1, 0f, 1f, 0f);
            emitter.pos(2, x1, y1, z1).uv(2, uRight, vBot).normal(2, 0f, 1f, 0f);
            emitter.pos(3, x0, y0, z0).uv(3, uLeft, vBot).normal(3, 0f, 1f, 0f);
            applyTint(emitter, tint);
            emitter.nominalFace(Direction.UP);
            emitter.cullFace(null);
            emitter.emit();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  LEAF CLUSTER — fluffy canopy from randomized billboards
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emits a cluster of randomized leaf quads that together form a fluffy,
     * volumetric canopy. Adjacent leaf blocks' clusters overlap and interleave,
     * merging into a continuous mass without visible block-grid boundaries.
     *
     * <p><b>Per-quad randomization (all derived from the block seed):</b></p>
     * <ol>
     *   <li><b>Position offset</b> — each quad's centre is scattered within a
     *       sphere of radius {@code spread}, causing quads from neighbouring
     *       blocks to overlap.</li>
     *   <li><b>Scale</b> — half-size scaled by a random factor in
     *       [{@code SCALE_MIN=0.8}, {@code SCALE_MAX=1.5}] — wider range than
     *       before for more variety.</li>
     *   <li><b>Orientation</b> — independent yaw + pitch gives full 3D rotation.
     *       Prevents the flat, grid-aligned slab appearance of vanilla leaves.</li>
     * </ol>
     *
     * <p><b>Double-sided rendering:</b> Each quad is emitted TWICE with opposite
     * winding orders (the second pass reverses vertex order). This guarantees
     * leaves are visible from both sides without requiring a shader, matching
     * the vanilla leaves rendering approach.</p>
     *
     * <p><b>Culling:</b> {@code cullFace(null)} is set on every quad so Sodium
     * never discards a leaf face at chunk/section borders.</p>
     *
     * @param emitter  Fabric FRAPI QuadEmitter
     * @param sprite   Leaf texture atlas sprite
     * @param tint     Biome tint (0xRRGGBB) or -1 for no tint
     * @param posX     Local X of the block centre within local mesh space
     * @param posY     Local Y of the block centre within local mesh space
     * @param posZ     Local Z of the block centre within local mesh space
     * @param seed     Deterministic seed (e.g. {@code BlockPos.asLong()})
     * @param nQuads   Number of unique leaf quads (each emitted double-sided)
     *                 Recommended: 6–10
     * @param spread   Scatter radius in blocks. Recommended: 0.45–0.65
     */
    public static void emitLeafCluster(QuadEmitter emitter, Sprite sprite, int tint,
                                        float posX, float posY, float posZ,
                                        long seed, int nQuads, float spread) {

        // Deterministic LCG — no heap allocations, safe on worker threads.
        // XOR with a large prime to de-correlate positions that are multiples
        // of small integers (common with block-grid coordinates).
        long rng = seed ^ 0x6A09E667F3BCC908L;

        // Atlas UV corners (min/max, not frame-relative, since leaf quads
        // are free-floating and not aligned to any axis)
        float u0 = sprite.getMinU(), u1 = sprite.getMaxU();
        float v0 = sprite.getMinV(), v1 = sprite.getMaxV();

        final float BASE_HALF = 0.45f;

        // Scale range: [0.8, 1.5] — wider than before for visible size variation
        final float SCALE_MIN   = 0.80f;
        final float SCALE_RANGE = 0.70f; // 1.5 - 0.8

        for (int q = 0; q < nQuads; q++) {

            // ── 1. Random scatter within spread sphere ────────────────────────
            rng = lcg(rng);
            float cx = posX + (((rng >> 16) & 0xFF) / 127.5f - 1f) * spread;
            rng = lcg(rng);
            float cy = posY + (((rng >> 16) & 0xFF) / 127.5f - 1f) * spread;
            rng = lcg(rng);
            float cz = posZ + (((rng >> 16) & 0xFF) / 127.5f - 1f) * spread;

            // ── 2. Random scale ───────────────────────────────────────────────
            rng = lcg(rng);
            float halfSize = BASE_HALF * (SCALE_MIN + ((rng & 0xFFFF) / 65535f) * SCALE_RANGE);

            // ── 3. Random full 3D orientation (yaw + pitch) ───────────────────
            rng = lcg(rng);
            float yaw   = (rng & 0xFFFF) / 65535f * 2f * (float) Math.PI;
            rng = lcg(rng);
            float pitch = (rng & 0xFFFF) / 65535f * (float) Math.PI - (float) Math.PI / 2f;

            float cosYaw   = (float) Math.cos(yaw);
            float sinYaw   = (float) Math.sin(yaw);
            float cosPitch = (float) Math.cos(pitch);
            float sinPitch = (float) Math.sin(pitch);

            // Local right and up axes of the quad plane
            //   right = (cosYaw, 0, -sinYaw)
            //   up    = (sinYaw·sinPitch, cosPitch, cosYaw·sinPitch)
            float rx = cosYaw,          ry = 0f,       rz = -sinYaw;
            float ux = sinYaw*sinPitch, uy = cosPitch, uz = cosYaw*sinPitch;

            // ── 4. Compute the 4 corner positions ────────────────────────────
            //  CCW from "front" (face normal direction):
            //    p0 = centre - right*h - up*h   (bottom-left)
            //    p1 = centre + right*h - up*h   (bottom-right)
            //    p2 = centre + right*h + up*h   (top-right)
            //    p3 = centre - right*h + up*h   (top-left)
            float p0x = cx - halfSize*rx - halfSize*ux;
            float p0y = cy - halfSize*ry - halfSize*uy;
            float p0z = cz - halfSize*rz - halfSize*uz;

            float p1x = cx + halfSize*rx - halfSize*ux;
            float p1y = cy + halfSize*ry - halfSize*uy;
            float p1z = cz + halfSize*rz - halfSize*uz;

            float p2x = cx + halfSize*rx + halfSize*ux;
            float p2y = cy + halfSize*ry + halfSize*uy;
            float p2z = cz + halfSize*rz + halfSize*uz;

            float p3x = cx - halfSize*rx + halfSize*ux;
            float p3y = cy - halfSize*ry + halfSize*uy;
            float p3z = cz - halfSize*rz + halfSize*uz;

            // ── 5. Face normal = cross(right, up) ────────────────────────────
            float nx = ry*uz - rz*uy;
            float ny = rz*ux - rx*uz;
            float nz = rx*uy - ry*ux;

            // ── 6. Emit FRONT FACE (CCW from outside, normal = nx/ny/nz) ─────
            emitter.pos(0, p0x, p0y, p0z).uv(0, u0, v1); // bottom-left
            emitter.pos(1, p1x, p1y, p1z).uv(1, u1, v1); // bottom-right
            emitter.pos(2, p2x, p2y, p2z).uv(2, u1, v0); // top-right
            emitter.pos(3, p3x, p3y, p3z).uv(3, u0, v0); // top-left
            emitter.normal(0, nx, ny, nz); emitter.normal(1, nx, ny, nz);
            emitter.normal(2, nx, ny, nz); emitter.normal(3, nx, ny, nz);
            applyTint(emitter, tint);
            emitter.nominalFace(Direction.UP);
            emitter.cullFace(null); // !! Never skip at chunk/section borders
            emitter.emit();

            // ── 7. Emit BACK FACE (reversed winding, normal = -nx/-ny/-nz) ───
            // Reversing the vertex order flips the winding so the face is
            // visible from the opposite side. This simulates a double-sided
            // material without any shader requirement.
            emitter.pos(0, p3x, p3y, p3z).uv(0, u0, v0); // was v3 (top-left)
            emitter.pos(1, p2x, p2y, p2z).uv(1, u1, v0); // was v2 (top-right)
            emitter.pos(2, p1x, p1y, p1z).uv(2, u1, v1); // was v1 (bottom-right)
            emitter.pos(3, p0x, p0y, p0z).uv(3, u0, v1); // was v0 (bottom-left)
            emitter.normal(0, -nx, -ny, -nz); emitter.normal(1, -nx, -ny, -nz);
            emitter.normal(2, -nx, -ny, -nz); emitter.normal(3, -nx, -ny, -nz);
            applyTint(emitter, tint);
            emitter.nominalFace(Direction.UP);
            emitter.cullFace(null);
            emitter.emit();
        }
    }

    /** Applies biome tint or white vertex colour to all 4 vertices. */
    private static void applyTint(QuadEmitter emitter, int tint) {
        if (tint != -1) {
            emitter.spriteColor(0, tint, tint, tint, tint);
        } else {
            emitter.color(0, -1); emitter.color(1, -1);
            emitter.color(2, -1); emitter.color(3, -1);
        }
    }

    // ─── Deterministic LCG (Knuth multiplicative) — no allocations ───────────
    private static long lcg(long seed) {
        return seed * 6364136223846793005L + 1442695040888963407L;
    }

    // ─── Rotation around [0.5, 0, 0.5] block centre ──────────────────────────
    private static float[] applyRotCentered(float[] rot, float x, float y, float z) {
        float dx = x - 0.5f, dz = z - 0.5f;
        float[] r = TerrainSurfaceSampler.rotate(rot, dx, y, dz);
        return new float[]{ r[0] + 0.5f, r[1], r[2] + 0.5f };
    }
}

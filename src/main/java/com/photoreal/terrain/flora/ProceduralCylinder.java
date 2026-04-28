package com.photoreal.terrain.flora;

import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.Direction;

/**
 * ============================================================
 *  ProceduralCylinder — Organic tree trunk geometry
 * ============================================================
 *
 * GEOMETRY OVERVIEW:
 * ------------------
 * Octagonal (8-sided) cylinder. 8 sides gives a convincingly round
 * profile at normal game viewing distances with only 16 unique
 * vertices per log block (8 bottom + 8 top).
 *
 *   Layout per block:
 *     • 8 lateral quads  (one per octagon edge, Ring0→Ring1)
 *     • 4 top-cap quads  (octagon fan: 8 sectors → 4 quads of 2 sectors each)
 *
 * ─────────────────────────────────────────────────────────────
 * FIX 1 — CORRECT WINDING ORDER (solves missing faces)
 * ─────────────────────────────────────────────────────────────
 * Minecraft / FRAPI expects quad vertices in Counter-Clockwise order
 * when viewed from OUTSIDE (i.e. from the direction the face normal
 * points). The canonical layout is:
 *
 *   v3 ───── v2      ← top
 *   │         │
 *   │  face   │      ← texture
 *   │         │
 *   v0 ───── v1      ← bottom
 *
 *   Vertex order: 0=bottom-left, 1=bottom-right, 2=top-right, 3=top-left
 *   (CCW when viewed from outside = the face normal points toward the viewer)
 *
 * For a lateral cylinder face:
 *   Looking outward from the trunk axis, "left" = angle i, "right" = angle i+1
 *   (because our COS/SIN table goes CCW in the XZ plane).
 *
 *   Correct CCW order (viewed from outside):
 *     0 = bottom-left  (angle i,    y=base)
 *     1 = bottom-right (angle i+1,  y=base)
 *     2 = top-right    (angle i+1,  y=top )
 *     3 = top-left     (angle i,    y=top )
 *
 * ─────────────────────────────────────────────────────────────
 * FIX 2 — cullFace(null) ON EVERY QUAD (solves chunk-border culling)
 * ─────────────────────────────────────────────────────────────
 * FRAPI's default cullFace is the nominalFace direction. If Sodium sees
 * a quad with cullFace=NORTH and the NORTH neighbour is solid, it skips
 * the quad entirely. Cylinder faces point in all directions, and many of
 * them cross chunk/section borders. Passing null disables this per-quad
 * culling check so every cylinder face always renders.
 *
 * ─────────────────────────────────────────────────────────────
 * FIX 3 — ABSOLUTE-Y CONTINUITY (solves disconnected pipe look)
 * ─────────────────────────────────────────────────────────────
 * Both taper and bend are evaluated using ABSOLUTE world Y (worldY +
 * local height), NOT height-above-root. This means:
 *
 *   Block at world Y=65:
 *     Ring0 uses   taperRadius(65.0f)   and   bendOffset(wx, wz, 65.0f)
 *     Ring1 uses   taperRadius(66.0f)   and   bendOffset(wx, wz, 66.0f)
 *
 *   Block at world Y=66 (next block up):
 *     Ring0 uses   taperRadius(66.0f)   and   bendOffset(wx, wz, 66.0f)  ← SAME VALUES
 *     Ring1 uses   taperRadius(67.0f)   and   bendOffset(wx, wz, 67.0f)
 *
 * Because both blocks evaluate the shared ring at the SAME absolute Y (66),
 * they produce identical vertex positions → perfect seam-free continuity
 * regardless of which chunk section each block belongs to.
 *
 * ─────────────────────────────────────────────────────────────
 * UV MAPPING:
 * ─────────────────────────────────────────────────────────────
 * Lateral:  U wraps around circumference (0→1 per full turn),
 *           V = 0 at bottom, 1 at top (bark grows upward).
 * Cap:      Circular projection centred at sprite (8,8) in 16×16 space.
 *
 * ─────────────────────────────────────────────────────────────
 * THREAD SAFETY:
 * ─────────────────────────────────────────────────────────────
 * All state is stack-local inside emit(). Static COS/SIN arrays are
 * read-only after class initialisation. Safe on Sodium worker threads.
 */
public final class ProceduralCylinder {

    // ─────────────────────────────────────────────────────────────────────────
    //  Configuration
    // ─────────────────────────────────────────────────────────────────────────

    /** Trunk radius at world Y = 64 (sea level). Wide base. */
    public static final float RADIUS_BASE = 0.42f;

    /** Trunk radius at world Y = 64 + TAPER_HEIGHT and above. Slim crown. */
    public static final float RADIUS_TOP = 0.18f;

    /**
     * Vertical distance (in world blocks) over which tapering from
     * RADIUS_BASE to RADIUS_TOP occurs.
     * Use a large value (e.g. 10) for gradual taper, small (e.g. 4) for
     * dramatic baobab-style trunks.
     */
    public static final float TAPER_HEIGHT = 8.0f;

    /** Height of one block segment. Always 1. */
    public static final float SEGMENT_HEIGHT = 1.0f;

    /** Number of faces on the octagonal cross-section. */
    public static final int SIDES = 8;

    // ─── Organic bend ─────────────────────────────────────────────────────────

    /**
     * Peak lateral displacement of the trunk centreline (blocks).
     * Visible at normal distance but not cartoonish: 0.10f ≈ 2 pixels.
     */
    private static final float BEND_AMP = 0.10f;

    /**
     * Vertical frequency of the sinusoidal bend (radians per block).
     * 1.4 ≈ one full S-curve over ~4.5 blocks of trunk height.
     */
    private static final float BEND_FREQ = 1.4f;

    /**
     * World XZ scale used to seed per-tree bend phase.
     * Larger value = nearby trees differ more. 0.41 is irrational
     * relative to typical block spacing, avoiding repetitive patterns.
     */
    private static final float BEND_SEED_SCALE = 0.41f;

    // ─── Pre-computed trig LUT for SIDES = 8 ─────────────────────────────────

    private static final float[] COS = new float[SIDES];
    private static final float[] SIN = new float[SIDES];

    static {
        for (int i = 0; i < SIDES; i++) {
            double a = 2.0 * Math.PI * i / SIDES;
            COS[i] = (float) Math.cos(a);
            SIN[i] = (float) Math.sin(a);
        }
    }

    private ProceduralCylinder() {}

    // ─────────────────────────────────────────────────────────────────────────
    //  PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emits one log-block segment of an organic tapered cylinder.
     *
     * <p>Invoke once per log block in the trunk. The geometry is computed
     * from absolute world coordinates so that adjacent blocks always share
     * identical ring vertices — guaranteeing seam-free continuity.</p>
     *
     * @param emitter        FRAPI QuadEmitter (Indium-backed for Sodium)
     * @param barkSprite     Bark texture (lateral faces)
     * @param capSprite      Cross-cut texture (top cap only)
     * @param cache          Chunk density cache for terrain surface alignment
     * @param worldX         Integer world X of this log block
     * @param worldY         Integer world Y of this log block (ABSOLUTE — not relative to root)
     * @param worldZ         Integer world Z of this log block
     * @param centerX        Local X of trunk axis within the block (typically 0.5)
     * @param centerZ        Local Z of trunk axis within the block (typically 0.5)
     * @param alignToTerrain If true, Ring0 is snapped to the DGVP isosurface
     * @param emitCap        If true, emits the top cap quad set. Pass false for
     *                       blocks that are immediately below another log block.
     */
    public static void emit(QuadEmitter emitter,
                            Sprite barkSprite,
                            Sprite capSprite,
                            com.photoreal.terrain.worldgen.ChunkDensityCache cache,
                            float worldX, float worldY, float worldZ,
                            float centerX, float centerZ,
                            boolean alignToTerrain,
                            boolean emitCap) {

        // ── Radii: use ABSOLUTE world Y so adjacent blocks agree ──────────────
        float radiusBot = taperRadius(worldY);
        float radiusTop = taperRadius(worldY + SEGMENT_HEIGHT);

        // ── Bend centreline offsets: ABSOLUTE Y ensures continuity ────────────
        float[] bendBot = bendOffset(worldX, worldZ, worldY);
        float[] bendTop = bendOffset(worldX, worldZ, worldY + SEGMENT_HEIGHT);

        // ── Terrain surface alignment: per-angle Y of the base ring ───────────
        float[] baseY = new float[SIDES];
        if (alignToTerrain && cache.isInitialized()) {
            for (int i = 0; i < SIDES; i++) {
                float vx = worldX + centerX + COS[i] * radiusBot + bendBot[0];
                float vz = worldZ + centerZ + SIN[i] * radiusBot + bendBot[1];
                baseY[i] = TerrainSurfaceSampler.findSurfaceY(cache, vx, worldY, vz) - worldY;
            }
        } else {
            // All eight base vertices sit flush at the block floor
            for (int i = 0; i < SIDES; i++) baseY[i] = 0f;
        }

        // ── Emit geometry ─────────────────────────────────────────────────────
        emitSides(emitter, barkSprite, baseY,
                  centerX, centerZ,
                  radiusBot, radiusTop,
                  bendBot, bendTop);

        if (emitCap) {
            emitTopCap(emitter, capSprite,
                       centerX, centerZ,
                       radiusTop, bendTop);
        }
    }

    /**
     * Backwards-compatible overload: always emits the cap (legacy behaviour).
     * Call the 11-argument version when you want to suppress duplicate caps
     * between vertically adjacent log blocks.
     */
    public static void emit(QuadEmitter emitter,
                            Sprite barkSprite,
                            Sprite capSprite,
                            com.photoreal.terrain.worldgen.ChunkDensityCache cache,
                            float worldX, float worldY, float worldZ,
                            float centerX, float centerZ,
                            boolean alignToTerrain) {
        emit(emitter, barkSprite, capSprite, cache,
             worldX, worldY, worldZ, centerX, centerZ,
             alignToTerrain, true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TAPER — smooth radius as a function of absolute world Y
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns trunk radius at the given absolute world Y.
     *
     * <p>Uses a smooth-step blend so the taper accelerates gently rather
     * than changing at a constant linear rate:</p>
     * <pre>
     *   t_linear  = clamp((y - Y_BASE) / TAPER_HEIGHT, 0, 1)
     *   t_smooth  = 3t² - 2t³
     *   radius    = lerp(RADIUS_BASE, RADIUS_TOP, t_smooth)
     * </pre>
     *
     * <p>Y_BASE is 64 (vanilla sea level) — adjust if your terrain is shifted.</p>
     */
    private static float taperRadius(float absoluteWorldY) {
        // Taper starts at sea level (Y=64). Adjust to match your terrain.
        final float Y_BASE = 64f;
        float t = Math.max(0f, Math.min((absoluteWorldY - Y_BASE) / TAPER_HEIGHT, 1f));
        t = t * t * (3f - 2f * t); // smooth-step
        return RADIUS_BASE + (RADIUS_TOP - RADIUS_BASE) * t;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  BEND — sinusoidal centreline offset as a function of absolute world Y
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns {deltaX, deltaZ} trunk-axis offset at the given absolute world Y.
     *
     * <p>Using absolute Y as the argument (not height-above-root) ensures that
     * the block at Y=66 computing its Ring0 (= bend at Y=66) gets the identical
     * value as the block at Y=65 computing its Ring1 (= bend at Y=65+1=66).
     * This is the key invariant that prevents visible seams.</p>
     *
     * <p>Formula:
     * <pre>
     *   phase_x = wx * BEND_SEED_SCALE
     *   phase_z = wz * BEND_SEED_SCALE
     *   deltaX  = BEND_AMP * sin(absoluteY * BEND_FREQ + phase_x)
     *   deltaZ  = BEND_AMP * cos(absoluteY * BEND_FREQ + phase_z)
     * </pre>
     * sin for X and cos for Z produce an elliptical arc rather than a
     * straight lean, giving a more natural appearance.</p>
     */
    private static float[] bendOffset(float wx, float wz, float absoluteWorldY) {
        float px = wx * BEND_SEED_SCALE;
        float pz = wz * BEND_SEED_SCALE;
        float dx = BEND_AMP * (float) Math.sin(absoluteWorldY * BEND_FREQ + px);
        float dz = BEND_AMP * (float) Math.cos(absoluteWorldY * BEND_FREQ + pz);
        return new float[]{ dx, dz };
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  LATERAL FACES — 8 quads, each one octagon segment tall
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emits the 8 outward-facing side quads of the cylinder.
     *
     * <p>Vertex winding: CCW from outside (standard Minecraft/FRAPI convention).
     * Layout per quad:</p>
     * <pre>
     *   v3(top-left)  ─── v2(top-right)
     *       │                   │
     *   v0(bot-left)  ─── v1(bot-right)
     *
     *   Index order: 0, 1, 2, 3  (CCW when facing outward)
     * </pre>
     *
     * <p>For our octagon, "left" = current angle index {@code i},
     * "right" = {@code i+1}, because COS/SIN are laid out CCW.</p>
     */
    private static void emitSides(QuadEmitter emitter, Sprite sprite,
                                   float[] baseY,
                                   float cx, float cz,
                                   float radiusBot, float radiusTop,
                                   float[] bendBot, float[] bendTop) {
        for (int i = 0; i < SIDES; i++) {
            int next = (i + 1) % SIDES;

            // ── Bottom-ring (Ring0) XZ positions ────────────────────────────
            // "left" vertex: angle i
            float x0b = cx + bendBot[0] + COS[i]    * radiusBot;
            float z0b = cz + bendBot[1] + SIN[i]    * radiusBot;
            // "right" vertex: angle i+1
            float x1b = cx + bendBot[0] + COS[next] * radiusBot;
            float z1b = cz + bendBot[1] + SIN[next] * radiusBot;
            // Y from terrain sampler (local, relative to block floor)
            float y0b = baseY[i];
            float y1b = baseY[next];

            // ── Top-ring (Ring1) XZ positions ────────────────────────────────
            float x0t = cx + bendTop[0] + COS[i]    * radiusTop;
            float z0t = cz + bendTop[1] + SIN[i]    * radiusTop;
            float x1t = cx + bendTop[0] + COS[next] * radiusTop;
            float z1t = cz + bendTop[1] + SIN[next] * radiusTop;
            float y0t = y0b + SEGMENT_HEIGHT;
            float y1t = y1b + SEGMENT_HEIGHT;

            // ── Outward face normal ───────────────────────────────────────────
            // Average of the two edge normals, projected to XZ plane.
            float nx = (COS[i] + COS[next]) * 0.5f;
            float nz = (SIN[i] + SIN[next]) * 0.5f;
            float nLen = (float) Math.sqrt(nx * nx + nz * nz);
            if (nLen > 1e-6f) { nx /= nLen; nz /= nLen; }

            // ── UV ────────────────────────────────────────────────────────────
            // U: wraps around circumference. V: 0 = bottom (bark root), 16 = top.
            float uLeft  = sprite.getFrameU((float)  i      / SIDES * 16f);
            float uRight = sprite.getFrameU((float) (i + 1) / SIDES * 16f);
            float vBot   = sprite.getFrameV(0f);   // bottom of texture
            float vTop   = sprite.getFrameV(16f);  // top of texture

            // ── CCW winding: bottom-left, bottom-right, top-right, top-left ──
            // Vertex 0: bottom-left  (angle i,    Ring0)
            emitter.pos(0, x0b, y0b, z0b).uv(0, uLeft,  vBot).normal(0, nx, 0f, nz);
            // Vertex 1: bottom-right (angle i+1,  Ring0)
            emitter.pos(1, x1b, y1b, z1b).uv(1, uRight, vBot).normal(1, nx, 0f, nz);
            // Vertex 2: top-right    (angle i+1,  Ring1)
            emitter.pos(2, x1t, y1t, z1t).uv(2, uRight, vTop).normal(2, nx, 0f, nz);
            // Vertex 3: top-left     (angle i,    Ring1)
            emitter.pos(3, x0t, y0t, z0t).uv(3, uLeft,  vTop).normal(3, nx, 0f, nz);

            // White vertex colour (texture shows at full brightness)
            emitter.color(0, -1); emitter.color(1, -1);
            emitter.color(2, -1); emitter.color(3, -1);

            // nominalFace for AO bucket. NORTH is conventional for non-axis-aligned faces.
            emitter.nominalFace(Direction.NORTH);

            // !! CRITICAL: null = never cull this quad based on neighbour solidity.
            // Without this, Sodium skips cylinder faces at chunk/section borders.
            emitter.cullFace(null);

            emitter.emit();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TOP CAP — octagon decomposed into 4 quads (fan from centre)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emits the 4 quads that tile the octagonal top cap.
     *
     * <p>FRAPI only supports quads. We decompose the octagon fan into 4 quads,
     * each spanning 2 adjacent sectors (centre + 3 rim vertices).</p>
     *
     * <p>Winding: CCW when viewed from above (normal = +Y).</p>
     */
    private static void emitTopCap(QuadEmitter emitter, Sprite sprite,
                                    float cx, float cz,
                                    float radiusTop,
                                    float[] bendTop) {
        // All cap vertices share the same Y = 1 block above the base ring
        final float capY = SEGMENT_HEIGHT;

        // Bent octagon centre
        float centreX = cx + bendTop[0];
        float centreZ = cz + bendTop[1];

        for (int i = 0; i < SIDES; i += 2) {
            int a = i;
            int b = (i + 1) % SIDES;
            int c = (i + 2) % SIDES;

            // Three rim vertices (CCW from above: a → b → c going CCW)
            float x1 = cx + bendTop[0] + COS[a] * radiusTop;
            float z1 = cz + bendTop[1] + SIN[a] * radiusTop;
            float x2 = cx + bendTop[0] + COS[b] * radiusTop;
            float z2 = cz + bendTop[1] + SIN[b] * radiusTop;
            float x3 = cx + bendTop[0] + COS[c] * radiusTop;
            float z3 = cz + bendTop[1] + SIN[c] * radiusTop;

            // Circular UV: centre maps to (8,8), rim at sprite boundary
            float uC = sprite.getFrameU(8f),             vC = sprite.getFrameV(8f);
            float u1 = sprite.getFrameU((COS[a]+1f)*8f), v1 = sprite.getFrameV((SIN[a]+1f)*8f);
            float u2 = sprite.getFrameU((COS[b]+1f)*8f), v2 = sprite.getFrameV((SIN[b]+1f)*8f);
            float u3 = sprite.getFrameU((COS[c]+1f)*8f), v3 = sprite.getFrameV((SIN[c]+1f)*8f);

            // CCW winding from above: 0=centre, 1=rim-a, 2=rim-b, 3=rim-c
            emitter.pos(0, centreX, capY, centreZ).uv(0, uC, vC).normal(0, 0f, 1f, 0f);
            emitter.pos(1, x1,     capY, z1      ).uv(1, u1, v1).normal(1, 0f, 1f, 0f);
            emitter.pos(2, x2,     capY, z2      ).uv(2, u2, v2).normal(2, 0f, 1f, 0f);
            emitter.pos(3, x3,     capY, z3      ).uv(3, u3, v3).normal(3, 0f, 1f, 0f);

            emitter.color(0, -1); emitter.color(1, -1);
            emitter.color(2, -1); emitter.color(3, -1);

            emitter.nominalFace(Direction.UP);
            emitter.cullFace(null); // Never skip the cap, even at section borders
            emitter.emit();
        }
    }
}

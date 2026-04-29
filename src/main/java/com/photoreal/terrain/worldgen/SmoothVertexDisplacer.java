package com.photoreal.terrain.worldgen;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;

/**
 * ============================================================
 *  SmoothVertexDisplacer v2 — NoCubes-style Height Interpolation
 * ============================================================
 *
 * ALGORITHM: Neighbor-Aware Bilinear Height Interpolation
 * ========================================================
 *
 * PROBLEM WITH v1 (DGVP / noise-based):
 * --------------------------------------
 * The old approach sampled a 3D density field and used Newton's method to
 * project vertices toward the iso-surface. While mathematically elegant, it
 * produced "melted plastic" artifacts because the density function has bumps
 * everywhere — even on flat terrain where all blocks are at the same Y level.
 *
 * TRUE NOCUBES PRINCIPLE:
 * -----------------------
 * A vertex at the CORNER of block (bx, by, bz) sits at the intersection of
 * four vertical "columns" of blocks:
 *
 *       (bx-1,bz-1) │ (bx,bz-1)
 *       ─────────────┼─────────────
 *       (bx-1,bz)   │ (bx,bz)
 *                    ^
 *                 vertex here
 *
 * For each of these 4 columns, we find the surface height — i.e., the Y of
 * the highest solid terrain block at or near the vertex's Y level.
 *
 * The vertex's target Y is the BILINEAR AVERAGE of these 4 column heights.
 *
 * FLAT GROUND GUARANTEE:
 * ----------------------
 * If all 4 columns have the same surface height H, the average = H, and the
 * vertex displacement = H - vy = 0.0f. ZERO bumps on flat terrain.
 *
 * STAIRCASE → SLOPE:
 * ------------------
 * If two columns are at Y=64 and two are at Y=65:
 *   average = (64+64+65+65) / 4 = 64.5
 *   vertex Y = 64.5 → perfect 45° slope between the levels.
 *
 * SCOPE:
 * ------
 * Only top-face vertices of terrain blocks are displaced (Y-axis only).
 * X and Z are left unchanged to preserve texture alignment.
 * Cave / underground blocks are protected by an air-above check.
 *
 * NORMALS:
 * --------
 * Normal = cross product of the two slope vectors computed from the 4
 * sampled heights. On flat ground this yields exactly (0, 1, 0).
 *
 * PERFORMANCE:
 * ------------
 * Uses BlockRenderView for direct block lookups (no density cache needed).
 * A small 5×5×5 query box per vertex is cheap because Sodium already holds
 * the WorldSlice (a local copy of the chunk + neighbors) in memory.
 */
public final class SmoothVertexDisplacer {

    // ─── Configuration ────────────────────────────────────────────────────────

    /**
     * Maximum Y-axis displacement per vertex (in blocks).
     * 0.5 = can bridge a full one-block step cleanly.
     * Do NOT exceed 0.5 or vertices will cross their neighbours' block boundaries
     * and create geometry tears.
     */
    public static final float MAX_Y_SHIFT = 0.5f;

    /**
     * How many blocks UP/DOWN to scan when looking for the surface in a column.
     * 3 blocks is enough for most natural terrain slopes; increase for cliffs.
     */
    private static final int COLUMN_SCAN_RADIUS = 3;

    // ─── Result object (reused per-thread to avoid allocations) ───────────────

    /** Carries the displaced vertex position and its surface normal. */
    public static final class VertexResult {
        public float x, y, z;
        /** Surface normal — (0,1,0) on flat ground, tilted on slopes. */
        public float nx, ny, nz;

        void setFlat(float wx, float wy, float wz) {
            this.x = wx; this.y = wy; this.z = wz;
            this.nx = 0f; this.ny = 1f; this.nz = 0f;
        }

        void set(float wx, float wy, float wz,
                 float targetY,
                 float nx, float ny, float nz) {
            this.x = wx;
            this.y = clamp(targetY, wy - MAX_Y_SHIFT, wy + MAX_Y_SHIFT);
            this.z = wz;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Displaces a single top-face vertex using neighbor-aware height interpolation.
     *
     * <p>The vertex must be the TOP face vertex of a terrain block. Bottom-face
     * and side-face vertices are not displaced (pass through unchanged).</p>
     *
     * @param world   the block render view (WorldSlice or ClientWorld)
     * @param blockX  integer X of the block this vertex belongs to
     * @param blockY  integer Y of the block this vertex belongs to
     * @param blockZ  integer Z of the block this vertex belongs to
     * @param wx      actual vertex X in world space (blockX + 0.0 or blockX + 1.0)
     * @param wy      actual vertex Y in world space (blockY + 1.0 for top face)
     * @param wz      actual vertex Z in world space (blockZ + 0.0 or blockZ + 1.0)
     * @param result  reusable result object (no allocation)
     */
    public static void processTopVertex(BlockRenderView world,
                                        int blockX, int blockY, int blockZ,
                                        float wx, float wy, float wz,
                                        VertexResult result) {

        // ── Guard: only top-face vertices (wy ≈ blockY + 1.0) ────────────────
        if (Math.abs(wy - (blockY + 1.0f)) > 0.01f) {
            result.setFlat(wx, wy, wz);
            return;
        }

        // ── Guard: block must be a surface terrain block ───────────────────────
        BlockState state = world.getBlockState(new BlockPos(blockX, blockY, blockZ));
        if (!isSurfaceTerrainBlock(state)) {
            result.setFlat(wx, wy, wz);
            return;
        }

        // ── Guard: must have air (or non-solid) above → real surface ──────────
        BlockState above = world.getBlockState(new BlockPos(blockX, blockY + 1, blockZ));
        if (above.isOpaqueFullCube(world, new BlockPos(blockX, blockY + 1, blockZ))) {
            // Underground block — do not displace (would break cave geometry)
            result.setFlat(wx, wy, wz);
            return;
        }

        // ─────────────────────────────────────────────────────────────────────
        //  CORE: Sample surface height in the 4 columns surrounding this vertex
        //
        //  Vertex corner layout (vertex is at ●):
        //
        //    col(-1,-1)  col(0,-1)
        //         ┌───────┬───────┐
        //         │       │       │
        //    col(-1,0) ───●─── col(0,0)
        //         │       │       │
        //         └───────┴───────┘
        //
        //  col(dx, dz) samples the column at (blockX+dx, blockZ+dz).
        //  dx ∈ {-1, 0},  dz ∈ {-1, 0}
        // ─────────────────────────────────────────────────────────────────────

        float h00 = getSurfaceHeight(world, blockX,     blockY, blockZ    );
        float h10 = getSurfaceHeight(world, blockX - 1, blockY, blockZ    );
        float h01 = getSurfaceHeight(world, blockX,     blockY, blockZ - 1);
        float h11 = getSurfaceHeight(world, blockX - 1, blockY, blockZ - 1);

        // Bilinear average → target vertex Y (top of the interpolated surface)
        float avgHeight = (h00 + h10 + h01 + h11) * 0.25f;

        // Target Y = avgHeight + 1.0 (vertex sits on TOP of the surface block)
        float targetY = avgHeight + 1.0f;

        // ── Compute surface normal from the height field ──────────────────────
        // Two tangent vectors along the surface:
        //   T_x = (1, h00-h10, 0)   (slope in X direction)
        //   T_z = (0, h00-h01, 1)   (slope in Z direction)
        // Normal = T_x × T_z (cross product)

        float slopeX = h00 - h10;   // rise per unit step in X
        float slopeZ = h00 - h01;   // rise per unit step in Z

        // Cross product: T_x(1,sx,0) × T_z(0,sz,1)
        //   = ( sx*1 - 0*sz,  0*0 - 1*1,  1*sz - sx*0 )
        //   = ( sx, -1, sz )   then flip Y for upward-facing normal
        float rnx = -slopeX;
        float rny =  1.0f;
        float rnz = -slopeZ;

        float len = (float) Math.sqrt(rnx * rnx + rny * rny + rnz * rnz);
        if (len > 0.0001f) {
            rnx /= len; rny /= len; rnz /= len;
        } else {
            rnx = 0f; rny = 1f; rnz = 0f;
        }

        result.set(wx, wy, wz, targetY, rnx, rny, rnz);
    }

    // ─── Column height sampling ───────────────────────────────────────────────

    /**
     * Finds the Y-coordinate of the topmost solid terrain block in column (cx, cz)
     * within ±COLUMN_SCAN_RADIUS of the reference Y.
     *
     * <p>Strategy:
     * <ol>
     *   <li>If the block at exactly (cx, refY, cz) is solid terrain, return refY.</li>
     *   <li>Scan upward for solid terrain (handles steps up).</li>
     *   <li>Scan downward for solid terrain (handles steps down / air gaps).</li>
     *   <li>If no terrain found in radius, return refY (no displacement).</li>
     * </ol>
     *
     * @param world  block view
     * @param cx     column X
     * @param refY   reference Y (the block Y of the vertex's block)
     * @param cz     column Z
     * @return       Y of the highest solid terrain block in scan range
     */
    private static float getSurfaceHeight(BlockRenderView world,
                                          int cx, int refY, int cz) {
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        // First check: the exact block at refY in this column
        mutable.set(cx, refY, cz);
        BlockState state = world.getBlockState(mutable);
        if (isSurfaceTerrainBlock(state)) {
            // Confirm it is a real surface (has air / non-solid above)
            mutable.set(cx, refY + 1, cz);
            BlockState stateAbove = world.getBlockState(mutable);
            if (!stateAbove.isOpaqueFullCube(world, mutable)) {
                return refY; // exact match
            }
        }

        // Scan upward first (neighbour column is higher)
        for (int dy = 1; dy <= COLUMN_SCAN_RADIUS; dy++) {
            int sy = refY + dy;
            mutable.set(cx, sy, cz);
            state = world.getBlockState(mutable);
            if (isSurfaceTerrainBlock(state)) {
                mutable.set(cx, sy + 1, cz);
                BlockState sa = world.getBlockState(mutable);
                if (!sa.isOpaqueFullCube(world, mutable)) {
                    return sy;
                }
            }
        }

        // Scan downward (neighbour column is lower or has an air gap)
        for (int dy = 1; dy <= COLUMN_SCAN_RADIUS; dy++) {
            int sy = refY - dy;
            mutable.set(cx, sy, cz);
            state = world.getBlockState(mutable);
            if (isSurfaceTerrainBlock(state)) {
                mutable.set(cx, sy + 1, cz);
                BlockState sa = world.getBlockState(mutable);
                if (!sa.isOpaqueFullCube(world, mutable)) {
                    return sy;
                }
            }
        }

        // No terrain found in scan radius → no displacement for this column
        return refY;
    }

    // ─── Block type check ─────────────────────────────────────────────────────

    /**
     * Returns {@code true} if this block is part of the smooth terrain surface.
     *
     * <p>Covers: grass, dirt, coarse dirt, podzol, mycelium, mud,
     * stone, granite, diorite, andesite, deepslate, tuff, calcite,
     * sand, red sand, gravel, clay.</p>
     */
    public static boolean isSurfaceTerrainBlock(BlockState state) {
        if (state.isAir()) return false;
        return state.isIn(BlockTags.DIRT)
            || state.isIn(BlockTags.BASE_STONE_OVERWORLD)
            || state.isIn(BlockTags.SAND)
            || state.isOf(Blocks.GRAVEL)
            || state.isOf(Blocks.CLAY)
            || state.isOf(Blocks.GRASS_BLOCK)
            || state.isOf(Blocks.PODZOL)
            || state.isOf(Blocks.MYCELIUM);
    }

    // ─── Utility ──────────────────────────────────────────────────────────────

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}

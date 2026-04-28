package com.photoreal.terrain.mixin;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ============================================================
 *  TerrainCullingMixin — Aggressive Sodium 0.5.x X-Ray fix
 * ============================================================
 *
 * WHY THE OLD isSideInvisible APPROACH FAILED:
 * --------------------------------------------
 * isSideInvisible / isSideInvisibleTo control vanilla's per-face
 * "skip this face between two adjacent same-material blocks" check.
 * Sodium 0.5.3 (Embeddium, Rubidium) does NOT use this method for
 * its own face-culling pass. Sodium reads two separate data sources:
 *
 *   1. getCullingShape(world, pos)  ← correct Yarn 1.20.1 name!
 *      (NOT getCullShape — that name does NOT exist in 1.20.1 Yarn mappings)
 *      Sodium calls this on the NEIGHBOUR block to decide whether the
 *      current block's face touching that neighbour should be rendered.
 *      If the returned VoxelShape fills the entire face (i.e. equals the
 *      full block face), Sodium culls the face. Returning VoxelShapes.empty()
 *      means "I expose nothing — do NOT cull through me", forcing every
 *      adjacent face to render.
 *
 *   2. isOpaqueFullCube(world, pos)
 *      Used to build Sodium's per-section occlusion graph. If a block
 *      reports itself as a full opaque cube, entire sections behind it
 *      may be skipped. Returning false opts us out of that graph entirely.
 *
 * WHAT WE DO:
 * -----------
 *   • getCullingShape  → VoxelShapes.empty()  for all smooth terrain blocks
 *   • isOpaqueFullCube → false               for all smooth terrain blocks
 *
 * We keep isSideInvisible intact (removed from this version) because
 * Sodium does not call it and adding it caused a duplicate-injection
 * error in some Mixin chain orders.
 *
 * PERFORMANCE NOTE:
 * -----------------
 * getCullShape is called once per face per chunk build (not per frame).
 * The tag lookup (isIn) is O(1) via bitset after Minecraft's registry
 * warm-up. No meaningful performance impact.
 *
 * SMOOTH TERRAIN TAGS (vanilla 1.20.1):
 *   minecraft:dirt               — grass_block, dirt, coarse_dirt, podzol,
 *                                  mycelium, rooted_dirt, mud
 *   minecraft:base_stone_overworld — stone, granite, diorite, andesite,
 *                                   deepslate, tuff, calcite
 *   minecraft:sand               — sand, red_sand
 */
@Mixin(AbstractBlock.AbstractBlockState.class)
public abstract class TerrainCullingMixin {

    // ─────────────────────────────────────────────────────────────────────────
    //  Shared helper — cached set membership check for smooth terrain blocks
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns true if this state is part of the smooth terrain mesh. */
    private static boolean isSmoothTerrainBlock(BlockState state) {
        return state.isIn(BlockTags.DIRT)
            || state.isIn(BlockTags.BASE_STONE_OVERWORLD)
            || state.isIn(BlockTags.SAND);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Inject 1: getCullShape  (THE key hook Sodium 0.5.x actually uses)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Injected at HEAD (cancellable) into
     * {@code AbstractBlockState.getCullingShape(BlockView world, BlockPos pos)}.
     *
     * <p>IMPORTANT: In Yarn 1.20.1 the method is named {@code getCullingShape},
     * NOT {@code getCullShape}. Using the wrong name causes an
     * InvalidInjectionException at startup because the refmap cannot resolve it.
     *
     * <p>Sodium calls this on the NEIGHBOUR of the block being meshed to
     * decide whether a shared face should be culled. The check is roughly:
     * <pre>
     *   if (neighbourCullingShape.face(dir).isFull()) → cull the face
     * </pre>
     *
     * <p>By returning {@code VoxelShapes.empty()} we guarantee that no face
     * of any smooth terrain block is used to cull its neighbours. This is the
     * correct, Sodium-aware approach — it works regardless of whether
     * {@code isSideInvisible} is overridden.</p>
     *
     * <p>Returning an empty shape is also what glass, slabs, and leaves do,
     * so this does not introduce any novel rendering assumptions.</p>
     */
    @Inject(
        method = "getCullingShape",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void photoreal_emptyCullShape(BlockView world, BlockPos pos,
                                          CallbackInfoReturnable<VoxelShape> cir) {
        BlockState self = (BlockState) (Object) this;
        if (isSmoothTerrainBlock(self)) {
            // empty() = no solid faces → Sodium will never cull through this block
            cir.setReturnValue(VoxelShapes.empty());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Inject 2: isOpaqueFullCube  (defeats Sodium's section occlusion graph)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Injected at HEAD (cancellable) into
     * {@code AbstractBlockState.isOpaqueFullCube(BlockView world, BlockPos pos)}.
     *
     * <p>Sodium builds a graph of which chunk sections are "occluded" — i.e.
     * completely hidden behind a wall of opaque full-cube blocks. If a smooth
     * terrain block is mistakenly marked as an opaque full cube, Sodium may
     * skip rendering entire sections visible through the displaced mesh,
     * creating large rectangular X-Ray windows.</p>
     *
     * <p>Returning {@code false} means: "do not use me as an occluder."
     * Performance impact is negligible — this is sampled once per block
     * at section build time, not per frame.</p>
     */
    @Inject(
        method = "isOpaqueFullCube",
        at = @At("HEAD"),
        cancellable = true
    )
    private void photoreal_notOpaqueFullCube(BlockView world, BlockPos pos,
                                              CallbackInfoReturnable<Boolean> cir) {
        BlockState self = (BlockState) (Object) this;
        if (isSmoothTerrainBlock(self)) {
            cir.setReturnValue(false);
        }
    }
}

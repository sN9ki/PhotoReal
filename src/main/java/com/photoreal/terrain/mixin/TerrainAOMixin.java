package com.photoreal.terrain.mixin;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ============================================================
 *  TerrainAOMixin — Eliminates black AO artifacts on smooth terrain
 * ============================================================
 *
 * PROBLEM:
 * --------
 * Minecraft calculates Ambient Occlusion (AO) by sampling the brightness of
 * surrounding blocks and darkening vertices that are "near" opaque geometry.
 * When smooth terrain displaces block faces into non-standard positions, the
 * vanilla AO samples wrong neighbours, producing jet-black blotches and seams
 * at block boundaries — a classic sign of per-block AO being applied to
 * sub-block geometry.
 *
 * THE FIX:
 * --------
 * {@code getAmbientOcclusionLightLevel()} returns a float [0.0, 1.0] that
 * acts as a multiplier on AO contribution. Returning {@code 1.0F} means
 * "this block contributes zero darkening to its neighbours" — effectively
 * opting out of vanilla AO entirely for these blocks.
 *
 * WHY THIS IS CORRECT FOR OUR SETUP:
 * -----------------------------------
 * We rely on Iris + Bliss shader pack for all actual lighting and shading.
 * Bliss computes screen-space AO (SSAO) and ray-traced GI in the shader,
 * which correctly respects the displaced geometry. Letting vanilla AO run
 * on top of shader-computed lighting would double-darken corners — hence
 * we disable it at the source.
 *
 * SCOPE:
 * ------
 * Only smooth terrain blocks are affected (DIRT, BASE_STONE_OVERWORLD, SAND).
 * All other blocks retain vanilla AO behaviour unchanged.
 */
@Mixin(AbstractBlock.AbstractBlockState.class)
public abstract class TerrainAOMixin {

    // ─────────────────────────────────────────────────────────────────────────
    //  Helper — shared tag check (mirrors TerrainCullingMixin for clarity).
    //  Having it duplicated in each mixin avoids cross-mixin coupling and
    //  keeps each file independently comprehensible.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Renamed to photoreal_isAOTerrainBlock (NOT isSmoothTerrainBlock) to avoid
     * a Mixin "Method overwrite conflict".
     *
     * Both TerrainCullingMixin and TerrainAOMixin target AbstractBlock.AbstractBlockState.
     * When Mixin merges them, two private static methods with the same name in the
     * same target class are treated as a conflict — the second is silently dropped
     * (WARN: "Skipping method"). Giving the helper a unique name in each mixin
     * prevents the conflict entirely.
     */
    private static boolean photoreal_isAOTerrainBlock(BlockState state) {
        return state.isIn(BlockTags.DIRT)
            || state.isIn(BlockTags.BASE_STONE_OVERWORLD)
            || state.isIn(BlockTags.SAND);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Inject: getAmbientOcclusionLightLevel
    //  Forces full brightness (1.0F) for smooth terrain blocks so vanilla AO
    //  cannot darken adjacent vertices.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Injected at HEAD (cancellable) into
     * {@code AbstractBlockState.getAmbientOcclusionLightLevel(BlockView, BlockPos)}.
     *
     * <p>The vanilla value for a typical opaque block (e.g. dirt) is {@code 0.2F},
     * meaning it darkens neighbouring vertices by 80%. We return {@code 1.0F}
     * to eliminate this contribution entirely.</p>
     *
     * <p>Note: This does NOT affect the block's own brightness. It only controls
     * how much shadow this block casts on adjacent geometry through vanilla AO.</p>
     */
    @Inject(
        method = "getAmbientOcclusionLightLevel",
        at = @At("HEAD"),
        cancellable = true
    )
    private void photoreal_fullAOBrightness(BlockView world, BlockPos pos,
                                             CallbackInfoReturnable<Float> cir) {
        BlockState self = (BlockState) (Object) this;

        if (photoreal_isAOTerrainBlock(self)) {
            // 1.0F = no AO contribution; shader handles all shading.
            cir.setReturnValue(1.0F);
        }
    }
}

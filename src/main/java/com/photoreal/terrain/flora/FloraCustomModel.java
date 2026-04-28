package com.photoreal.terrain.flora;

import com.photoreal.terrain.worldgen.ChunkDensityCache;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random; // Правильный Random для 1.20.1
import net.minecraft.world.BlockRenderView;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class FloraCustomModel implements BakedModel, FabricBakedModel {

    public enum FloraType {
        CROSS_PLANT,
        LOG_CYLINDER,
        LEAF_CLUSTER
    }

    private final FloraType type;
    private final Sprite primarySprite;
    private final Sprite secondarySprite;
    private final Sprite particleSprite;

    public FloraCustomModel(FloraType type, Sprite primarySprite,
                             Sprite secondarySprite, Sprite particleSprite) {
        this.type = type;
        this.primarySprite  = primarySprite;
        this.secondarySprite = secondarySprite;
        this.particleSprite = particleSprite;
    }

    @Override
    public void emitBlockQuads(BlockRenderView blockView,
                                BlockState state,
                                BlockPos pos,
                                Supplier<Random> randomSupplier,
                                RenderContext context) {

        ChunkDensityCache cache = ChunkDensityCache.get();

        float wx = pos.getX();
        float wy = pos.getY();
        float wz = pos.getZ();

        int tint = -1;
        if (type == FloraType.CROSS_PLANT || type == FloraType.LEAF_CLUSTER) {
            tint = blockView.getColor(pos, (biome, bx, bz) -> biome.getFoliageColor());
        }

        QuadEmitter emitter = context.getEmitter();

        switch (type) {
            case CROSS_PLANT -> TerrainAlignedCross.emit(emitter, primarySprite, cache, wx, wy, wz, tint, true);
            case LOG_CYLINDER -> ProceduralCylinder.emit(emitter, primarySprite, secondarySprite != null ? secondarySprite : primarySprite, cache, wx, wy, wz, 0.5f, 0.5f, true);
            case LEAF_CLUSTER -> {
                long seed = pos.asLong();
                TerrainAlignedCross.emitLeafCluster(emitter, primarySprite, tint, 0.5f, 0.5f, 0.5f, seed, 8, 0.4f);
            }
        }
    }

    @Override
    public boolean isVanillaAdapter() {
        return false;
    }

    @Override
    public void emitItemQuads(ItemStack stack, Supplier<Random> randomSupplier, RenderContext context) {
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
        return Collections.emptyList();
    }

    @Override
    public boolean useAmbientOcclusion() {
        return type != FloraType.CROSS_PLANT;
    }

    // ИСПРАВЛЕНИЕ: В 1.20.1 метод называется hasDepth()
    @Override
    public boolean hasDepth() {
        return true;
    }

    @Override
    public boolean isBuiltin() {
        return false;
    }

    // ИСПРАВЛЕНИЕ: Обязательный метод для 1.20.1
    @Override
    public boolean isSideLit() {
        return true;
    }

    @Override
    public Sprite getParticleSprite() {
        return particleSprite;
    }

    @Override
    public ModelTransformation getTransformation() {
        return ModelTransformation.NONE;
    }

    @Override
    public ModelOverrideList getOverrides() {
        return ModelOverrideList.EMPTY;
    }
}
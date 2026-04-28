package com.photoreal.terrain.flora;

import com.photoreal.terrain.worldgen.ChunkDensityCache;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MeshBuilder;
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
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * ============================================================
 *  FloraCustomModel — кастомная BakedModel через Fabric Rendering API
 * ============================================================
 *
 * АРХИТЕКТУРНЫЙ ВЫБОР: FRAPI + Indium
 * =====================================
 *
 * Почему НЕ BlockEntityRenderer:
 * --------------------------------
 * - BlockEntityRenderer работает в главном потоке рендеринга (GL thread)
 * - Каждый BER — отдельный draw call → катастрофа для 1000+ деревьев
 * - Sodium не оптимизирует их (нет batching/instancing)
 * - Требует маркировки каждого блока как BlockEntity → огромные аллокации
 *
 * Почему НЕ прямые миксины в ChunkBuildBuffers:
 * -----------------------------------------------
 * - Нестабильные внутренние API (ломаются каждым патчем Sodium)
 * - Race conditions в многопоточном chunk builder
 * - Невозможно отменить write после partial flush
 *
 * Почему FRAPI + Indium:
 * -----------------------
 * + FabricBakedModel.isVanillaAdapter() = false → Sodium передаёт рендеринг
 *   в RenderContext (Indium обеспечивает совместимость с Sodium)
 * + QuadEmitter → идентичен Sodium's vertex format (Indium транслирует)
 * + Геометрия запекается в compile thread → 0 overhead в main thread
 * + Работает с Sodium AO и lightmap системами
 * + Поддерживает Materials (cutout, translucent) через RenderMaterial
 *
 * КАК РАБОТАЕТ:
 * -------------
 * 1. FloraModelProvider перехватывает ModelLoader через Mixin
 * 2. Для flora-блоков возвращает FloraCustomModel вместо ванильной модели
 * 3. Sodium видит FabricBakedModel.isVanillaAdapter()=false
 * 4. Вызывает emitBlockQuads() → мы читаем ChunkDensityCache и испускаем геометрию
 * 5. Indium транслирует FRAPI-квады в Sodium vertex format
 * 6. Геометрия попадает в ChunkMeshBufferBuilder (как и у обычных блоков)
 *
 * BATCHING:
 * ---------
 * Все блоки одной секции компилируются в один ChunkMeshBuildResult.
 * Sodium группирует их по материалу (solid/cutout/translucent).
 * Наша модель использует CUTOUT для травы/листьев → всё в одном draw call.
 */
public class FloraCustomModel implements BakedModel, FabricBakedModel {

    // ─── Типы флоры ──────────────────────────────────────────────────────────
    public enum FloraType {
        /** Трава, папоротник, цветы — cross-mesh с выравниванием по рельефу */
        CROSS_PLANT,
        /** Стволы деревьев — процедурный октагональный цилиндр */
        LOG_CYLINDER,
        /** Листва — кластер случайно ориентированных квадов */
        LEAF_CLUSTER
    }

    private final FloraType type;
    private final Sprite primarySprite;
    private final Sprite secondarySprite; // кора/срез для бревна, null для остальных
    private final Sprite particleSprite;

    public FloraCustomModel(FloraType type, Sprite primarySprite,
                             Sprite secondarySprite, Sprite particleSprite) {
        this.type = type;
        this.primarySprite  = primarySprite;
        this.secondarySprite = secondarySprite;
        this.particleSprite = particleSprite;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  КЛЮЧЕВОЙ МЕТОД: Fabric Rendering API — вызывается Sodium через Indium
    //  в compile thread (многопоточно, поэтому используем ThreadLocal-кэш)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void emitBlockQuads(BlockRenderView blockView,
                                BlockState state,
                                BlockPos pos,
                                Supplier<Random> randomSupplier,
                                RenderContext context) {

        // Получаем кэш плотности текущего потока (заполнен ChunkBuilderMeshingTaskMixin)
        ChunkDensityCache cache = ChunkDensityCache.get();

        float wx = pos.getX();
        float wy = pos.getY();
        float wz = pos.getZ();

        // Биомный тинт (для травы и листьев)
        int tint = -1;
        if (type == FloraType.CROSS_PLANT || type == FloraType.LEAF_CLUSTER) {
            tint = blockView.getColor(pos, biome ->
                biome.getFoliageColor()  // или getGrassColorAt() для травы
            );
        }

        QuadEmitter emitter = context.getEmitter();

        switch (type) {
            case CROSS_PLANT -> {
                // Трава: крест с выравниванием нижних вершин по DGVP-поверхности
                TerrainAlignedCross.emit(
                    emitter, primarySprite, cache,
                    wx, wy, wz,
                    tint,
                    true   // tiltToNormal = true → наклоняем по нормали склона
                );
            }
            case LOG_CYLINDER -> {
                // Ствол: октагональный цилиндр с Ring 0 → terrain surface
                ProceduralCylinder.emit(
                    emitter,
                    primarySprite,            // bark texture
                    secondarySprite != null ? secondarySprite : primarySprite, // cap
                    cache,
                    wx, wy, wz,
                    0.5f, 0.5f,             // center offset (половина блока)
                    true                     // alignToTerrain
                );
            }
            case LEAF_CLUSTER -> {
                // Листва: кластер 8 случайных биллборд-квадов
                long seed = pos.asLong();
                TerrainAlignedCross.emitLeafCluster(
                    emitter, primarySprite, tint,
                    0.5f, 0.5f, 0.5f,  // локальный центр блока
                    seed, 8, 0.4f      // 8 квадов, spread 0.4 блока
                );
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  FabricBakedModel interface
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * КРИТИЧЕСКИ ВАЖНО: возвращаем false.
     * Это сигнал Sodium/Indium использовать наш emitBlockQuads() вместо
     * стандартного BakedModel.getQuads(). Без этого — ванильный рендеринг.
     */
    @Override
    public boolean isVanillaAdapter() {
        return false;
    }

    @Override
    public void emitItemQuads(ItemStack stack, Supplier<Random> randomSupplier,
                               RenderContext context) {
        // Item rendering — используем простую ванильную модель
        // (флора в инвентаре отображается стандартно)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  BakedModel interface — ванильные методы (не используются Sodium)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
        return Collections.emptyList(); // Sodium использует FRAPI, не этот метод
    }

    @Override
    public boolean useAmbientOcclusion() {
        return type != FloraType.CROSS_PLANT; // AO для бревна и листвы
    }

    @Override
    public boolean hasDepthInGui() {
        return true;
    }

    @Override
    public boolean isBuiltin() {
        return false;
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

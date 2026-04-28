package com.photoreal.terrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.photoreal.terrain.PhotorealTerrainMod;
import com.photoreal.terrain.flora.FloraModelProvider;
import com.photoreal.terrain.util.NormalEncoder;
import com.photoreal.terrain.worldgen.ChunkDensityCache;
import com.photoreal.terrain.worldgen.SmoothDensityFunction;
import com.photoreal.terrain.worldgen.SmoothVertexDisplacer;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ============================================================
 *  BlockRendererMixin v2 — надёжный подход через @Redirect
 * ============================================================
 *
 * ПОЧЕМУ @Redirect, А НЕ @ModifyVariable:
 * ----------------------------------------
 * @ModifyVariable с index + ordinal хрупок: индексы локальных переменных
 * зависят от конкретной версии JVM и компилятора, используемого при сборке
 * Sodium. Если Sodium собран с другим javac или JVM 21, индексы могут
 * сдвинуться на -1/-2, и мод молча не будет работать или упадёт.
 *
 * @Redirect перехватывает ВЫЗОВ функции (или доступ к полю), а не
 * локальную переменную. Это намного устойчивее к изменениям байткода.
 *
 * СТРАТЕГИЯ:
 * ----------
 * В writeGeometry() Sodium вызывает ChunkVertexEncoder для каждой вершины:
 *
 *   ChunkVertexEncoder.Vertex vertex = vertices[dstIndex];
 *   vertex.x = quad.getX(dstIndex) + (float) offset.x;
 *   vertex.y = quad.getY(dstIndex) + (float) offset.y;
 *   vertex.z = quad.getZ(dstIndex) + (float) offset.z;
 *   vertex.nx = <faceNormal.x>;
 *   vertex.ny = <faceNormal.y>;
 *   vertex.nz = <faceNormal.z>;
 *   ... UV, color, light ...
 *   buffer.push(vertices, material);
 *
 * Мы перехватываем вызов buffer.push() — в этот момент массив vertices[]
 * УЖЕ заполнен стандартными данными. Мы итерируем по нему, применяем
 * смещение к x/y/z и записываем новые нормали, затем вызываем push().
 *
 * Это "post-process" подход: чище и безопаснее чем прерывание записи.
 *
 * ОГРАНИЧЕНИЕ:
 * -----------
 * Нам нужен доступ к мировым координатам блока чтобы передать их в
 * ChunkDensityCache. Получаем через shadow-поля renderContext (текущий
 * BlockRenderContext хранится как поле BlockRenderer между вызовами).
 *
 * СОВМЕСТИМОСТЬ С ДРУГИМИ МОДАМИ:
 * --------------------------------
 * Этот Mixin добавляется в цепочку @Redirect — если другой мод тоже
 * делает @Redirect на тот же метод, возникнет конфликт. Решение — использовать
 * MixinExtras @WrapOperation вместо @Redirect (совместим с несколькими модами).
 * Код для @WrapOperation приведён в комментарии ниже.
 */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class BlockRendererMixin {

    // ─── Shadow-поля BlockRenderer (для доступа к контексту рендера) ─────────

    /**
     * BlockRenderer хранит текущий контекст в поде между
     * вызовами prepareBlockModel и renderQuad.
     * Точное имя поля нужно уточнить по исходникам:
     * обычно 'context' или 'activeContext' или 'ctx'.
     */
    // @Shadow private BlockRenderContext context; // раскомментировать после верификации

    // ─── ThreadLocal состояние ────────────────────────────────────────────────

    /** Переиспользуемый результат смещения вершины (без аллокаций). */
    @Unique
    private static final ThreadLocal<SmoothVertexDisplacer.VertexResult> VERTEX_RESULT =
        ThreadLocal.withInitial(SmoothVertexDisplacer.VertexResult::new);

    /**
     * Флаг: текущий блок является флорой (FloraCustomModel обрабатывает его
     * через FRAPI — наш DGVP не должен трогать эти вершины).
     *
     * ПОЧЕМУ НУЖЕН ЭТОТ ФЛАГ:
     * ------------------------
     * Для флора-блоков FloraCustomModel.isVanillaAdapter() = false.
     * Sodium/Indium направляет их в FRAPI-путь и ОБХОДИТ writeGeometry().
     * Но если Indium не установлен — флора всё же попадёт в стандартный
     * vanilla-путь и дойдёт до нашего @WrapOperation.
     * Флаг гарантирует: без Indium флора тоже не будет деформирована DGVP.
     */
    @Unique
    private static final ThreadLocal<Boolean> IS_FLORA_BLOCK =
        ThreadLocal.withInitial(() -> false);

    /** Синглтон DensityFunction (double-checked locking, volatile). */
    @Unique
    private static volatile DensityFunction DENSITY_FUNCTION = null;

    // ─────────────────────────────────────────────────────────────────────────
    //  INJECT: renderModel() HEAD — определяем тип блока
    // ─────────────────────────────────────────────────────────────────────────

    @Inject(method = "renderModel", at = @At("HEAD"))
    private void onRenderModelHead(BlockRenderContext ctx, ChunkBuildBuffers buffers, CallbackInfo ci) {
        // Проверяем: является ли блок флорой с кастомным рендером
        BlockState state = ctx.state();
        boolean isFlora = FloraModelProvider.isFloraBlock(state.getBlock());
        IS_FLORA_BLOCK.set(isFlora);

        if (isFlora) {
            // Флора-блоки обрабатываются FloraCustomModel через FRAPI.
            // Дополнительная информация для дебага:
            PhotorealTerrainMod.LOGGER.debug(
                "[PhotorealTerrain] Flora block at {}: DGVP disabled", ctx.pos()
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  @WrapOperation: ChunkMeshBufferBuilder.push() — пост-обработка вершин
    //
    //  Используем @WrapOperation (MixinExtras) вместо @Redirect:
    //  позволяет нескольким модам независимо оборачивать один и тот же вызов
    //  без конфликтов (в отличие от @Redirect, где возможен только один handler).
    //
    //  ПОРЯДОК ОПЕРАЦИЙ:
    //  1. Проверяем IS_FLORA_BLOCK — если флора, пропускаем DGVP
    //  2. Для рельефных блоков: применяем displacement + gradient normal
    //  3. Вызываем original.call() — фактическая запись в GPU-буфер
    // ─────────────────────────────────────────────────────────────────────────

    @WrapOperation(
        method = "writeGeometry",
        at = @At(
            value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/render/chunk/vertex/builder/" +
                     "ChunkMeshBufferBuilder;push(" +
                     "[Lme/jellysquid/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;" +
                     "Lme/jellysquid/mods/sodium/client/render/chunk/terrain/material/Material;" +
                     ")V",
            remap = false
        )
    )
    private void wrapPush(ChunkMeshBufferBuilder buffer,
                          ChunkVertexEncoder.Vertex[] vertices,
                          Material material,
                          Operation<Void> original) {

        // ── GUARD 1: флора — DGVP не применяем ───────────────────────────────
        // FloraCustomModel генерирует собственную геометрию через FRAPI.
        // Если попали сюда — либо Indium не установлен, либо другой edge-case.
        // В любом случае: флора не должна получать terrain displacement.
        if (IS_FLORA_BLOCK.get()) {
            original.call(buffer, vertices, material);
            return;
        }

        // ── GUARD 2: кэш готов ────────────────────────────────────────────────
        ChunkDensityCache cache = ChunkDensityCache.get();
        if (!cache.isInitialized()) {
            original.call(buffer, vertices, material);
            return;
        }

        // ── DGVP: смещаем все 4 вершины квада ────────────────────────────────
        SmoothVertexDisplacer.VertexResult result = VERTEX_RESULT.get();

        for (ChunkVertexEncoder.Vertex v : vertices) {
            // v.x/y/z — мировые координаты вершины (Sodium добавил offset секции)
            SmoothVertexDisplacer.processVertex(cache, v.x, v.y, v.z, result);

            v.x = result.x;
            v.y = result.y;
            v.z = result.z;

            // Gradient-нормаль: signed byte (-127..+127 = -1.0..+1.0)
            v.nx = NormalEncoder.encodeByte(result.nx);
            v.ny = NormalEncoder.encodeByte(result.ny);
            v.nz = NormalEncoder.encodeByte(result.nz);
        }

        original.call(buffer, vertices, material);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ─────────────────────────────────────────────────────────────────────────

    @Unique
    static DensityFunction getOrCreateDensityFunction() {
        if (DENSITY_FUNCTION == null) {
            synchronized (BlockRendererMixin.class) {
                if (DENSITY_FUNCTION == null) {
                    try {
                        DENSITY_FUNCTION = new SmoothDensityFunction(
                            0L,    // seed
                            80.0,  // terrainScale
                            256.0, // horizontalScale
                            64,    // seaLevel
                            6,     // octaves
                            0.35   // caveStrength
                        );
                        PhotorealTerrainMod.LOGGER.info(
                            "[PhotorealTerrain] DensityFunction инициализирована в BlockRenderer"
                        );
                    } catch (Exception e) {
                        PhotorealTerrainMod.LOGGER.error(
                            "[PhotorealTerrain] Ошибка инициализации: {}", e.getMessage()
                        );
                    }
                }
            }
        }
        return DENSITY_FUNCTION;
    }
}

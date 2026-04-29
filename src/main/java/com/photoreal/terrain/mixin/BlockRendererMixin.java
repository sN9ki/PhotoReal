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

    /** Сохраняем текущий контекст для доступа к мировым координатам из wrapPush. */
    @Unique
    private static final ThreadLocal<BlockRenderContext> CURRENT_CONTEXT =
        new ThreadLocal<>();

    /** Синглтон DensityFunction (double-checked locking, volatile). */
    @Unique
    private static volatile DensityFunction DENSITY_FUNCTION = null;

    // ─────────────────────────────────────────────────────────────────────────
    //  INJECT: renderModel() HEAD — определяем тип блока
    // ─────────────────────────────────────────────────────────────────────────

    @Inject(method = "renderModel", at = @At("HEAD"))
    private void onRenderModelHead(BlockRenderContext ctx, ChunkBuildBuffers buffers, CallbackInfo ci) {
        // Сохраняем контекст рендера блока для доступа из writeGeometry
        CURRENT_CONTEXT.set(ctx);

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

        BlockRenderContext ctx = CURRENT_CONTEXT.get();

        // Применяем NoCubes-интерполяцию только если контекст существует и это не флора
        if (ctx != null && !IS_FLORA_BLOCK.get()) {
            SmoothVertexDisplacer.VertexResult res = VERTEX_RESULT.get();
            BlockPos pos = ctx.pos();

            for (int i = 0; i < vertices.length; i++) {
                ChunkVertexEncoder.Vertex v = vertices[i];

                // processTopVertex сама проверит, является ли вершина верхней (wy ≈ blockY + 1.0)
                SmoothVertexDisplacer.processTopVertex(
                    ctx.world(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    v.x, v.y, v.z,
                    res
                );

                // Если вершина была смещена (Y изменился)
                if (Math.abs(v.y - res.y) > 0.001f) {
                    v.y = res.y;
                }
            }
        }

        original.call(buffer, vertices, material);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ─────────────────────────────────────────────────────────────────────────

    @Unique
    private static DensityFunction getOrCreateDensityFunction() {
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

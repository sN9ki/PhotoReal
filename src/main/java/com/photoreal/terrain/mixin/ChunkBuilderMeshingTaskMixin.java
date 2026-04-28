package com.photoreal.terrain.mixin;

import com.photoreal.terrain.PhotorealTerrainMod;
import com.photoreal.terrain.worldgen.ChunkDensityCache;
import com.photoreal.terrain.worldgen.SmoothDensityFunction;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.util.task.CancellationToken;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ============================================================
 *  ChunkBuilderMeshingTaskMixin
 * ============================================================
 *
 * ЗАЧЕМ НУЖЕН ВТОРОЙ MIXIN:
 * -------------------------
 * BlockRendererMixin обновляет кэш при смене секции (16³), проверяя
 * BlockPos текущего блока. Но первые несколько блоков новой секции
 * попадают в кэш от ПРЕДЫДУЩЕЙ секции, пока проверка не сработала.
 *
 * Этот Mixin перехватывает execute() — самый верхний уровень задачи
 * компиляции чанка — и явно инвалидирует кэш ПЕРЕД обходом блоков.
 *
 * ДОПОЛНИТЕЛЬНО:
 * -------------
 * Здесь же можно заполнить кэш ЗАРАНЕЕ для всей секции (prefetch),
 * вместо того чтобы делать это лениво в BlockRendererMixin.
 * Это гарантирует корректность для всех блоков секции, даже первого.
 *
 * THREAD SAFETY:
 * --------------
 * ChunkBuilderMeshingTask выполняется на рабочих потоках Sodium
 * (SodiumChunkMeshingJob pool). ChunkDensityCache.get() использует
 * ThreadLocal — каждый поток изолирован. Безопасно.
 */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class ChunkBuilderMeshingTaskMixin {

    @Shadow @Final
    private RenderSection render;

    /**
     * Инжект в начало метода execute() — до начала обхода блоков.
     *
     * Шаг 1: Инвалидируем старый кэш.
     * Шаг 2: Предзаполняем новый кэш для текущей секции.
     *         RenderSection содержит координаты секции через getOrigin().
     *
     * Это самый ранний момент, когда нам известна позиция секции.
     *
     * @param ctx  ChunkBuildContext — контекст сборки, включает
     *             WorldSlice (снимок мира для текущего чанка)
     * @param ci   CallbackInfoReturnable (execute() возвращает ChunkBuildOutput)
     */
    @Inject(
        method = "execute",
        at = @At("HEAD")
    )
    private void onExecuteHead(ChunkBuildContext ctx, CancellationToken cancellationToken, CallbackInfoReturnable<?> ci) {
        // Извлекаем начальные координаты секции из RenderSection
        // render.getOriginX() возвращает BlockPos начала 16×16×16 секции
        int originX = render.getOriginX();
        int originY = render.getOriginY();
        int originZ = render.getOriginZ();

        ChunkDensityCache cache = ChunkDensityCache.get();

        try {
            DensityFunction fn = getOrCreateDensityFunction();
            if (fn != null) {
                // Предзаполнение кэша для всей секции + 3-блочный padding
                cache.populate(originX, originY, originZ, fn);
                PhotorealTerrainMod.LOGGER.debug(
                    "[PhotorealTerrain] Кэш заполнен для секции [{},{},{}]",
                    originX >> 4, originY >> 4, originZ >> 4
                );
            }
        } catch (Exception e) {
            // В случае ошибки — инвалидируем кэш, блоки будут рендериться стандартно
            cache.invalidate();
            PhotorealTerrainMod.LOGGER.warn(
                "[PhotorealTerrain] Ошибка заполнения кэша для [{},{},{}]: {}",
                originX >> 4, originY >> 4, originZ >> 4, e.getMessage()
            );
        }
    }

    /**
     * Инжект в конец execute() — очищаем кэш чтобы не удерживать память.
     * (Опционально — ThreadLocal и так переиспользуется)
     */
    @Inject(
        method = "execute",
        at = @At("RETURN")
    )
    private void onExecuteReturn(ChunkBuildContext ctx, CancellationToken cancellationToken, CallbackInfoReturnable<?> ci) {
        // Не инвалидируем здесь — кэш может быть переиспользован следующей
        // секцией того же чанка (Y+1), что сэкономит повторное заполнение.
        // Инвалидация происходит в onExecuteHead при несовпадении секции.
    }

    @Unique
    private static volatile DensityFunction cachedDensityFn = null;

    @Unique
    private static DensityFunction getOrCreateDensityFunction() {
        if (cachedDensityFn == null) {
            synchronized (ChunkBuilderMeshingTaskMixin.class) {
                if (cachedDensityFn == null) {
                    try {
                        cachedDensityFn = new SmoothDensityFunction(
                            0L, 80.0, 256.0, 64, 6, 0.35
                        );
                    } catch (Exception e) {
                        PhotorealTerrainMod.LOGGER.error(
                            "[PhotorealTerrain] Не удалось инициализировать DensityFunction: {}", e.getMessage());
                    }
                }
            }
        }
        return cachedDensityFn;
    }
}

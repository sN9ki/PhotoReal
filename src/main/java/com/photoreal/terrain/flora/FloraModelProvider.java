package com.photoreal.terrain.flora;

import com.photoreal.terrain.PhotorealTerrainMod;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelResolver;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.model.UnbakedModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * ============================================================
 *  FloraModelProvider — перехват загрузки моделей для флоры
 * ============================================================
 *
 * Использует Fabric Model Loading API v1 (fabric-api 0.92.1+1.20.1)
 * для замены ванильных BakedModel на наши FloraCustomModel.
 *
 * РЕГИСТРАЦИЯ:
 * ------------
 * Вызывается из PhotorealTerrainClient.onInitializeClient() через
 * ModelLoadingPlugin.register().
 *
 * ПОРЯДОК ПРИОРИТЕТОВ MODEL RESOLVER:
 * ------------------------------------
 * Fabric вызывает resolveModel() для каждого RenderLayer запроса.
 * Если мы возвращаем non-null UnbakedModel — она используется ВМЕСТО ванильной.
 * Это чисто (не ломает другие моды), без Mixin!
 *
 * SPRITE LOADING:
 * ---------------
 * Текстуры запекаются на этапе atlas stitching. Мы регистрируем нужные
 * SpriteIdentifier в modifyModelAfterBake() и получаем готовые Sprite.
 */
public class FloraModelProvider implements ModelLoadingPlugin {

    // ─── Реестр: Block → FloraType ───────────────────────────────────────────
    private static final Map<Block, FloraCustomModel.FloraType> FLORA_TYPES = new HashMap<>();

    static {
        // Кросс-меш (трава, цветы)
        FLORA_TYPES.put(Blocks.SHORT_GRASS,    FloraCustomModel.FloraType.CROSS_PLANT);
        FLORA_TYPES.put(Blocks.TALL_GRASS,     FloraCustomModel.FloraType.CROSS_PLANT);
        FLORA_TYPES.put(Blocks.FERN,           FloraCustomModel.FloraType.CROSS_PLANT);
        FLORA_TYPES.put(Blocks.DANDELION,      FloraCustomModel.FloraType.CROSS_PLANT);
        FLORA_TYPES.put(Blocks.POPPY,          FloraCustomModel.FloraType.CROSS_PLANT);
        FLORA_TYPES.put(Blocks.BLUE_ORCHID,    FloraCustomModel.FloraType.CROSS_PLANT);

        // Стволы деревьев → октагональный цилиндр
        FLORA_TYPES.put(Blocks.OAK_LOG,        FloraCustomModel.FloraType.LOG_CYLINDER);
        FLORA_TYPES.put(Blocks.BIRCH_LOG,      FloraCustomModel.FloraType.LOG_CYLINDER);
        FLORA_TYPES.put(Blocks.SPRUCE_LOG,     FloraCustomModel.FloraType.LOG_CYLINDER);
        FLORA_TYPES.put(Blocks.JUNGLE_LOG,     FloraCustomModel.FloraType.LOG_CYLINDER);
        FLORA_TYPES.put(Blocks.ACACIA_LOG,     FloraCustomModel.FloraType.LOG_CYLINDER);
        FLORA_TYPES.put(Blocks.DARK_OAK_LOG,   FloraCustomModel.FloraType.LOG_CYLINDER);
        FLORA_TYPES.put(Blocks.MANGROVE_LOG,   FloraCustomModel.FloraType.LOG_CYLINDER);

        // Листва → случайный кластер квадов
        FLORA_TYPES.put(Blocks.OAK_LEAVES,     FloraCustomModel.FloraType.LEAF_CLUSTER);
        FLORA_TYPES.put(Blocks.BIRCH_LEAVES,   FloraCustomModel.FloraType.LEAF_CLUSTER);
        FLORA_TYPES.put(Blocks.SPRUCE_LEAVES,  FloraCustomModel.FloraType.LEAF_CLUSTER);
        FLORA_TYPES.put(Blocks.JUNGLE_LEAVES,  FloraCustomModel.FloraType.LEAF_CLUSTER);
        FLORA_TYPES.put(Blocks.ACACIA_LEAVES,  FloraCustomModel.FloraType.LEAF_CLUSTER);
    }

    /** Проверка: является ли блок флорой с кастомным рендерингом. */
    public static boolean isFloraBlock(Block block) {
        return FLORA_TYPES.containsKey(block);
    }

    public static FloraCustomModel.FloraType getType(Block block) {
        return FLORA_TYPES.get(block);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ModelLoadingPlugin — точка входа в Fabric Model Loading API
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void initialize(Context pluginContext) {
        // Регистрируем BakedModelFactory через modifyModelAfterBake
        // Этот callback вызывается ПОСЛЕ запекания ванильных моделей
        // — мы их заменяем нашими FloraCustomModel
        pluginContext.modifyModelAfterBake().register((model, ctx) -> {
            // ctx.id() — идентификатор модели (например minecraft:block/oak_log)
            Identifier id = ctx.id();

            // Находим Block по идентификатору модели
            Block block = findBlockByModelId(id);
            if (block == null || !isFloraBlock(block)) {
                return model; // не флора — возвращаем без изменений
            }

            FloraCustomModel.FloraType type = getType(block);

            // Извлекаем спрайты из ванильной (оригинальной) модели
            Sprite primary   = model.getParticleSprite();
            Sprite secondary = extractSecondarySprite(block, ctx);

            PhotorealTerrainMod.LOGGER.debug(
                "[PhotorealTerrain] Заменяем модель '{}' на {} FloraCustomModel",
                id, type
            );

            return new FloraCustomModel(type, primary, secondary, primary);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Находит Block по Identifier модели.
     * Пример: "minecraft:block/oak_log" → Blocks.OAK_LOG
     */
    private static Block findBlockByModelId(Identifier modelId) {
        String path = modelId.getPath();
        // Модели блоков имеют путь вида "block/<имя_блока>"
        if (!path.startsWith("block/")) return null;

        String blockName = path.substring("block/".length());
        // Убираем суффиксы вроде "_axis_x", "_stage_0" и т.п.
        // Простая эвристика — матч по полному имени или с суффиксом
        for (Map.Entry<Block, FloraCustomModel.FloraType> entry : FLORA_TYPES.entrySet()) {
            Block block = entry.getKey();
            String registryName = net.minecraft.registry.Registries.BLOCK
                .getId(block).getPath();
            if (blockName.equals(registryName) ||
                blockName.startsWith(registryName + "_")) {
                return block;
            }
        }
        return null;
    }

    /**
     * Извлекает вторичный спрайт (верхний срез бревна) для LOG_CYLINDER.
     * Для бревна: первичный = кора (oak_log), вторичный = срез (oak_log_top)
     */
    private static Sprite extractSecondarySprite(Block block,
                                                  ModelLoadingPlugin.Context.ModifyModelAfterBake.Context ctx) {
        if (FLORA_TYPES.get(block) != FloraCustomModel.FloraType.LOG_CYLINDER) return null;

        // Пытаемся получить текстуру спила из атласа
        String registryName = net.minecraft.registry.Registries.BLOCK.getId(block).getPath();
        SpriteIdentifier capId = new SpriteIdentifier(
            SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE,
            new Identifier("minecraft", "block/" + registryName + "_top")
        );

        try {
            return capId.getSprite();
        } catch (Exception e) {
            // Если нет текстуры _top — используем основную
            return null;
        }
    }
}

package com.photoreal.terrain.flora;

import com.photoreal.terrain.PhotorealTerrainMod;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

public class FloraModelProvider implements ModelLoadingPlugin {

    private static final Map<Block, FloraCustomModel.FloraType> FLORA_TYPES = new HashMap<>();

    static {
        // Растения (крест-формы)
        FLORA_TYPES.put(Blocks.GRASS,          FloraCustomModel.FloraType.CROSS_PLANT);
        FLORA_TYPES.put(Blocks.TALL_GRASS,     FloraCustomModel.FloraType.CROSS_PLANT);
        FLORA_TYPES.put(Blocks.FERN,           FloraCustomModel.FloraType.CROSS_PLANT);
        FLORA_TYPES.put(Blocks.DANDELION,      FloraCustomModel.FloraType.CROSS_PLANT);
        FLORA_TYPES.put(Blocks.POPPY,          FloraCustomModel.FloraType.CROSS_PLANT);
        FLORA_TYPES.put(Blocks.BLUE_ORCHID,    FloraCustomModel.FloraType.CROSS_PLANT);

        // Брёвна — процедурный октагональный цилиндр
        FLORA_TYPES.put(Blocks.OAK_LOG,        FloraCustomModel.FloraType.LOG_CYLINDER);
        FLORA_TYPES.put(Blocks.BIRCH_LOG,      FloraCustomModel.FloraType.LOG_CYLINDER);
        FLORA_TYPES.put(Blocks.SPRUCE_LOG,     FloraCustomModel.FloraType.LOG_CYLINDER);
        FLORA_TYPES.put(Blocks.JUNGLE_LOG,     FloraCustomModel.FloraType.LOG_CYLINDER);
        FLORA_TYPES.put(Blocks.ACACIA_LOG,     FloraCustomModel.FloraType.LOG_CYLINDER);
        FLORA_TYPES.put(Blocks.DARK_OAK_LOG,   FloraCustomModel.FloraType.LOG_CYLINDER);
        FLORA_TYPES.put(Blocks.MANGROVE_LOG,   FloraCustomModel.FloraType.LOG_CYLINDER);

        // Листья — кластер случайных квадов
        FLORA_TYPES.put(Blocks.OAK_LEAVES,     FloraCustomModel.FloraType.LEAF_CLUSTER);
        FLORA_TYPES.put(Blocks.BIRCH_LEAVES,   FloraCustomModel.FloraType.LEAF_CLUSTER);
        FLORA_TYPES.put(Blocks.SPRUCE_LEAVES,  FloraCustomModel.FloraType.LEAF_CLUSTER);
        FLORA_TYPES.put(Blocks.JUNGLE_LEAVES,  FloraCustomModel.FloraType.LEAF_CLUSTER);
        FLORA_TYPES.put(Blocks.ACACIA_LEAVES,  FloraCustomModel.FloraType.LEAF_CLUSTER);
    }

    public static boolean isFloraBlock(Block block) {
        return FLORA_TYPES.containsKey(block);
    }

    public static FloraCustomModel.FloraType getType(Block block) {
        return FLORA_TYPES.get(block);
    }

    @Override
    public void onInitializeModelLoader(Context pluginContext) {
        // ИСПРАВЛЕНИЕ: Использование явного анонимного класса вместо лямбды
        pluginContext.modifyModelAfterBake().register(new ModelModifier.AfterBake() {
            @Override
            public BakedModel modifyModelAfterBake(BakedModel model, Context context) {
                Identifier id = context.id();
                Block block = findBlockByModelId(id);
                
                if (block == null || !isFloraBlock(block)) {
                    return model; 
                }

                FloraCustomModel.FloraType type = getType(block);
                Sprite primary = model.getParticleSprite();
                Sprite secondary = extractSecondarySprite(block);

                return new FloraCustomModel(type, primary, secondary, primary);
            }
        });
    }

    private static Block findBlockByModelId(Identifier modelId) {
        String path = modelId.getPath();
        if (!path.startsWith("block/")) return null;

        String blockName = path.substring("block/".length());
        for (Map.Entry<Block, FloraCustomModel.FloraType> entry : FLORA_TYPES.entrySet()) {
            Block block = entry.getKey();
            String registryName = net.minecraft.registry.Registries.BLOCK.getId(block).getPath();
            if (blockName.equals(registryName) || blockName.startsWith(registryName + "_")) {
                return block;
            }
        }
        return null;
    }

    private static Sprite extractSecondarySprite(Block block) {
        if (FLORA_TYPES.get(block) != FloraCustomModel.FloraType.LOG_CYLINDER) return null;

        String registryName = net.minecraft.registry.Registries.BLOCK.getId(block).getPath();
        SpriteIdentifier capId = new SpriteIdentifier(
            SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE,
            new Identifier("minecraft", "block/" + registryName + "_top")
        );

        try {
            return capId.getSprite();
        } catch (Exception e) {
            return null;
        }
    }
}
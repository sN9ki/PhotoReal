package com.photoreal.terrain;

import com.photoreal.terrain.flora.FloraModelProvider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;

/**
 * ============================================================
 *  PhotorealTerrainClient — клиентский инициализатор
 * ============================================================
 *
 * Выполняется ТОЛЬКО на клиенте (не на выделенном сервере).
 * Регистрирует:
 *   1. FloraModelProvider — перехват загрузки моделей флоры
 *
 * ПОРЯДОК ИНИЦИАЛИЗАЦИИ FABRIC:
 * ------------------------------
 * 1. ModInitializer.onInitialize()        ← PhotorealTerrainMod (общая, сервер+клиент)
 * 2. ClientModInitializer.onInitializeClient() ← PhotorealTerrainClient (только клиент)
 * 3. ModelLoadingPlugin.initialize()      ← FloraModelProvider (при загрузке ресурсов)
 *
 * ModelLoadingPlugin вызывается позже — при каждой перезагрузке ресурсов (F3+T),
 * поэтому изменения текстур и моделей применяются без перезапуска игры.
 */
public class PhotorealTerrainClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        PhotorealTerrainMod.LOGGER.info(
            "[PhotorealTerrain] Инициализация клиента — регистрация Flora Model Provider..."
        );

        // Регистрируем FloraModelProvider как ModelLoadingPlugin.
        // Fabric Model Loading API v1 вызовет initialize() при каждой
        // перезагрузке ресурсов — наш провайдер заменит ванильные модели.
        ModelLoadingPlugin.register(new FloraModelProvider());

        PhotorealTerrainMod.LOGGER.info(
            "[PhotorealTerrain] FloraModelProvider зарегистрирован. " +
            "Поддерживаемые блоки: трава, бревна ({} типов), листва.",
            6 // количество типов бревён
        );
    }
}

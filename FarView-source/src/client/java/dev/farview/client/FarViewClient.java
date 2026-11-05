package dev.farview.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.farview.client.cache.TerrainCache;
import dev.farview.client.config.FarViewConfig;
import dev.farview.client.render.FarViewRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FarViewClient implements ClientModInitializer {
    public static final String MOD_ID = "farview";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(id("controls"));
    private static final KeyMapping CYCLE_DISTANCE = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.farview.cycle_distance", InputConstants.Type.KEYBOARD, InputConstants.KEY_F8, CATEGORY));
    private static final KeyMapping TOGGLE = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.farview.toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_F9, CATEGORY));

    private static FarViewConfig config;
    private static TerrainCache cache;
    private static FarViewRenderer renderer;
    private static Object activeLevel;

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static FarViewConfig config() {
        return config;
    }

    public static TerrainCache cache() {
        return cache;
    }

    @Override
    public void onInitializeClient() {
        config = FarViewConfig.load();
        renderer = new FarViewRenderer();

        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            ensureCache(Minecraft.getInstance(), level);
            if (cache != null) cache.capture(level, chunk);
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            ensureCache(Minecraft.getInstance(), level);
            if (cache != null) cache.capture(level, chunk);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (CYCLE_DISTANCE.consumeClick() && client.player != null) {
                config.cycleDistance();
                client.player.sendSystemMessage(Component.literal("FarView: " + config.maxDistanceChunks() + " chunk"));
            }
            if (TOGGLE.consumeClick() && client.player != null) {
                config.toggleEnabled();
                client.player.sendSystemMessage(Component.literal("FarView " + (config.enabled() ? "açık" : "kapalı")));
            }
            if (client.level == null || client.player == null) {
                closeCache();
                activeLevel = null;
                return;
            }
            ensureCache(client, client.level);
            if (cache != null) {
                cache.requestNearbyLoads(client.player.chunkPosition().x(), client.player.chunkPosition().z(),
                        config.maxDistanceChunks());
                renderer.requestRebuild(cache, client.player.chunkPosition().x(), client.player.chunkPosition().z(),
                        config.maxDistanceChunks(), client.options.getEffectiveRenderDistance(), isZoomed(client));
            }
        });

        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            if (config.enabled() && cache != null) renderer.render(context);
        });

        LOGGER.info("FarView initialized for Minecraft 26.3");
    }

    private static void ensureCache(Minecraft client, net.minecraft.client.multiplayer.ClientLevel level) {
        if (activeLevel == level && cache != null) return;
        closeCache();
        String identity;
        if (client.getCurrentServer() != null) {
            identity = "server:" + client.getCurrentServer().name + "@" + client.getCurrentServer().ip;
        } else if (client.getSingleplayerServer() != null) {
            identity = "world:" + client.getSingleplayerServer()
                    .getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        } else {
            identity = "world:unknown";
        }
        identity += "|dimension:" + level.dimension().identifier();
        identity += "|surface-samples:16x16";
        cache = new TerrainCache(FarViewConfig.cacheRoot(), identity, config.memoryBudgetMb());
        renderer.clear();
        activeLevel = level;
    }

    private static boolean isZoomed(Minecraft client) {
        float normalFov = client.options.fov().get();
        float cameraFov = client.gameRenderer.mainCamera().getFov();
        return cameraFov < normalFov * 0.75f;
    }

    private static void closeCache() {
        if (cache != null) {
            cache.close();
            cache = null;
        }
        if (renderer != null) renderer.clear();
    }
}

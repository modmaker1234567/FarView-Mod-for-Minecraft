package dev.farview.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.farview.client.FarViewClient;
import dev.farview.client.cache.SurfaceChunk;
import dev.farview.client.cache.TerrainCache;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

/** Draws atlas-textured, blocky LOD terrain from cached client chunks. */
public final class FarViewRenderer {
    private static final int MAX_DISTANCE = 1024;
    private static final int SAMPLE_GRID = SurfaceChunk.SAMPLE_GRID;
    private static final RenderPipeline FARVIEW_PIPELINE = registerPipeline(
            entityPipelineBuilder()
                    .withLocation(FarViewClient.id("pipeline/farview_terrain"))
                    .withFragmentShader(FarViewClient.id("core/farview"))
                    .withShaderDefine("ALPHA_CUTOUT", 0.1f)
                    .withShaderDefine("PER_FACE_LIGHTING")
                    .withCull(false)
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .build());
    private static final RenderType RENDER_TYPE = createRenderType("farview_textured_terrain",
            RenderSetup.builder(FARVIEW_PIPELINE)
                    .withTexture("Sampler0", TextureAtlas.LOCATION_BLOCKS)
                    .useLightmap()
                    .useOverlay()
                    .createRenderSetup());

    private static RenderPipeline registerPipeline(RenderPipeline pipeline) {
        try {
            Method register = RenderPipelines.class.getDeclaredMethod("register", RenderPipeline.class);
            register.setAccessible(true);
            return (RenderPipeline) register.invoke(null, pipeline);
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static RenderPipeline.Builder entityPipelineBuilder() {
        try {
            Field snippet = RenderPipelines.class.getDeclaredField("ENTITY_SNIPPET");
            snippet.setAccessible(true);
            return RenderPipeline.builder((RenderPipeline.Snippet) snippet.get(null));
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static RenderType createRenderType(String name, RenderSetup setup) {
        try {
            Method create = RenderType.class.getDeclaredMethod("create", String.class, RenderSetup.class);
            create.setAccessible(true);
            return (RenderType) create.invoke(null, name, setup);
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private final AtomicBoolean building = new AtomicBoolean();
    private volatile List<TerrainCell> cells = List.of();
    private volatile long builtRevision = -1;
    private volatile int builtCenterX = Integer.MIN_VALUE;
    private volatile int builtCenterZ = Integer.MIN_VALUE;
    private volatile int builtRadius = -1;
    private volatile int builtVanillaDistance = -1;
    private volatile boolean builtZoomed;
    private volatile float builtFacingX;
    private volatile float builtFacingZ;
    private volatile float builtFov = -1;
    private volatile long lastBuildRequestNanos;
    private volatile long generation;

    public void requestRebuild(TerrainCache cache, int centerX, int centerZ, int radius, int vanillaDistance,
                               boolean zoomed) {
        Minecraft client = Minecraft.getInstance();
        var camera = client.gameRenderer.mainCamera();
        var look = camera.forwardVector();
        double horizontalLength = Math.sqrt(look.x() * look.x() + look.z() * look.z());
        float facingX = horizontalLength < 0.001 ? 0f : (float) (look.x() / horizontalLength);
        float facingZ = horizontalLength < 0.001 ? 0f : (float) (look.z() / horizontalLength);
        float cameraFov = camera.getFov();
        long revision = cache.revision();
        boolean moved = Math.abs(centerX - builtCenterX) >= 4 || Math.abs(centerZ - builtCenterZ) >= 4;
        double facingDot = builtFacingX * facingX + builtFacingZ * facingZ;
        boolean hasHorizontalFacing = facingX * facingX + facingZ * facingZ > 0.5;
        boolean hadHorizontalFacing = builtFacingX * builtFacingX + builtFacingZ * builtFacingZ > 0.5;
        boolean directionChanged = hasHorizontalFacing != hadHorizontalFacing
                || (hasHorizontalFacing && facingDot < Math.cos(Math.toRadians(20.0)));
        boolean viewChanged = builtRevision < 0 || directionChanged
                || Math.abs(cameraFov - builtFov) >= 5f;
        boolean changed = revision != builtRevision || radius != builtRadius || vanillaDistance != builtVanillaDistance
                || zoomed != builtZoomed || viewChanged;
        long now = System.nanoTime();
        if ((!moved && !changed) || !building.compareAndSet(false, true)) return;
        if (now - lastBuildRequestNanos < 150_000_000L && !moved && builtRevision >= 0) {
            building.set(false);
            return;
        }
        lastBuildRequestNanos = now;
        long requestedGeneration = generation;
        List<SurfaceChunk> snapshot = cache.snapshot();
        cache.mesher().execute(() -> {
            try {
                List<TerrainCell> next = build(snapshot, centerX, centerZ, radius, vanillaDistance,
                        zoomed, facingX, facingZ, cameraFov);
                if (generation == requestedGeneration) {
                    cells = next;
                    builtRevision = revision;
                    builtCenterX = centerX;
                    builtCenterZ = centerZ;
                    builtRadius = radius;
                    builtVanillaDistance = vanillaDistance;
                    builtZoomed = zoomed;
                    builtFacingX = facingX;
                    builtFacingZ = facingZ;
                    builtFov = cameraFov;
                }
            } catch (Throwable error) {
                FarViewClient.LOGGER.warn("Could not build FarView terrain mesh", error);
            } finally {
                building.set(false);
            }
        });
    }

    public void render(LevelRenderContext context) {
        List<TerrainCell> snapshot = cells;
        if (snapshot.isEmpty()) return;
        Minecraft client = Minecraft.getInstance();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        var look = client.gameRenderer.mainCamera().forwardVector();
        double lookLength = Math.sqrt(look.x() * look.x() + look.y() * look.y() + look.z() * look.z());
        double lookX = lookLength < 0.001 ? 0 : look.x() / lookLength;
        double lookY = lookLength < 0.001 ? 0 : look.y() / lookLength;
        double lookZ = lookLength < 0.001 ? 0 : look.z() / lookLength;
        double halfFovWithMargin = Math.toRadians(client.gameRenderer.mainCamera().getFov() * 0.5 + 12.0);
        double minimumFacing = Math.cos(halfFovWithMargin);
        List<TerrainCell> visible = new ArrayList<>();
        for (TerrainCell cell : snapshot) {
            double dx = cell.x - camera.x;
            double dy = cell.y - camera.y;
            double dz = cell.z - camera.z;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared < 1.0) continue;
            if (distanceSquared > (double) MAX_DISTANCE * 16.0 * MAX_DISTANCE * 16.0) continue;
            if (lookLength >= 0.001
                    && (dx * lookX + dy * lookY + dz * lookZ) / Math.sqrt(distanceSquared) < minimumFacing) continue;
            visible.add(cell);
        }
        if (visible.isEmpty()) return;

        Map<String, TextureAtlasSprite> sprites = new HashMap<>();
        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        try {
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            context.submitNodeCollector().submitCustomGeometry(poseStack, RENDER_TYPE, (pose, builder) -> {
                Matrix4fc matrix = pose.pose();
                for (TerrainCell cell : visible) {
                    TextureAtlasSprite top = sprite(client, sprites, cell.topTexture);
                    TextureAtlasSprite side = sprite(client, sprites, cell.sideTexture);
                    emitCell(builder, matrix, cell, top, side);
                }
            });
        } finally {
            poseStack.popPose();
        }
    }

    private static TextureAtlasSprite sprite(Minecraft client, Map<String, TextureAtlasSprite> cache, String id) {
        if (id == null || id.isBlank()) id = SurfaceChunk.FALLBACK_TEXTURE_ID;
        String textureId = id;
        return cache.computeIfAbsent(textureId, key -> client.getAtlasManager().get(
                new SpriteId(TextureAtlas.LOCATION_BLOCKS, Identifier.parse(key))));
    }

    private static void emitCell(VertexConsumer builder, Matrix4fc matrix, TerrainCell cell,
                                 TextureAtlasSprite top, TextureAtlasSprite side) {
        float half = cell.size * 0.5f;
        float minX = (float) (cell.x - half), maxX = (float) (cell.x + half);
        float minZ = (float) (cell.z - half), maxZ = (float) (cell.z + half);
        float u0 = 0f, u1 = 16f, v0 = 0f, v1 = 16f;

        addQuad(builder, matrix, top, cell.color,
                minX, cell.y, minZ, u0, v0, 0, 1, 0,
                minX, cell.y, maxZ, u0, v1, 0, 1, 0,
                maxX, cell.y, maxZ, u1, v1, 0, 1, 0,
                maxX, cell.y, minZ, u1, v0, 0, 1, 0);
        if (cell.westY < cell.y) {
            addQuad(builder, matrix, side, shade(cell.color, 0.68f),
                    minX, cell.y, maxZ, u1, v0, -1, 0, 0,
                    minX, cell.westY, maxZ, u1, v1, -1, 0, 0,
                    minX, cell.westY, minZ, u0, v1, -1, 0, 0,
                    minX, cell.y, minZ, u0, v0, -1, 0, 0);
        }
        if (cell.eastY < cell.y) {
            addQuad(builder, matrix, side, shade(cell.color, 0.68f),
                    maxX, cell.y, minZ, u0, v0, 1, 0, 0,
                    maxX, cell.eastY, minZ, u0, v1, 1, 0, 0,
                    maxX, cell.eastY, maxZ, u1, v1, 1, 0, 0,
                    maxX, cell.y, maxZ, u1, v0, 1, 0, 0);
        }
        if (cell.northY < cell.y) {
            addQuad(builder, matrix, side, shade(cell.color, 0.76f),
                    minX, cell.y, minZ, u0, v0, 0, 0, -1,
                    minX, cell.northY, minZ, u0, v1, 0, 0, -1,
                    maxX, cell.northY, minZ, u1, v1, 0, 0, -1,
                    maxX, cell.y, minZ, u1, v0, 0, 0, -1);
        }
        if (cell.southY < cell.y) {
            addQuad(builder, matrix, side, shade(cell.color, 0.76f),
                    maxX, cell.y, maxZ, u1, v0, 0, 0, 1,
                    maxX, cell.southY, maxZ, u1, v1, 0, 0, 1,
                    minX, cell.southY, maxZ, u0, v1, 0, 0, 1,
                    minX, cell.y, maxZ, u0, v0, 0, 0, 1);
        }
    }

    private static void addQuad(VertexConsumer builder, Matrix4fc matrix, TextureAtlasSprite sprite, int color,
                                float x0, float y0, float z0, float u0, float v0, float nx, float ny, float nz,
                                float x1, float y1, float z1, float u1, float v1, float nx1, float ny1, float nz1,
                                float x2, float y2, float z2, float u2, float v2, float nx2, float ny2, float nz2,
                                float x3, float y3, float z3, float u3, float v3, float nx3, float ny3, float nz3) {
        vertex(builder, matrix, sprite, color, x0, y0, z0, u0, v0, nx, ny, nz);
        vertex(builder, matrix, sprite, color, x1, y1, z1, u1, v1, nx1, ny1, nz1);
        vertex(builder, matrix, sprite, color, x2, y2, z2, u2, v2, nx2, ny2, nz2);
        vertex(builder, matrix, sprite, color, x3, y3, z3, u3, v3, nx3, ny3, nz3);
    }

    private static void vertex(VertexConsumer builder, Matrix4fc matrix, TextureAtlasSprite sprite, int color,
                               float x, float y, float z, float u, float v, float nx, float ny, float nz) {
        builder.addVertex(matrix, x, y + 0.025f, z)
                .setColor(red(color), green(color), blue(color), 1f)
                .setUv(sprite.getU(u), sprite.getV(v))
                .setOverlay(0)
                .setLight(0x00F000F0)
                .setNormal(nx, ny, nz);
    }

    private static float red(int color) { return ((color >>> 16) & 255) / 255f; }
    private static float green(int color) { return ((color >>> 8) & 255) / 255f; }
    private static float blue(int color) { return (color & 255) / 255f; }
    private static int shade(int color, float scale) {
        int r = Math.min(255, Math.round(((color >>> 16) & 255) * scale));
        int g = Math.min(255, Math.round(((color >>> 8) & 255) * scale));
        int b = Math.min(255, Math.round((color & 255) * scale));
        return r << 16 | g << 8 | b;
    }

    private static List<TerrainCell> build(List<SurfaceChunk> chunks, int centerX, int centerZ,
                                           int radius, int vanillaDistance, boolean zoomed,
                                           float facingX, float facingZ, float cameraFov) {
        List<TerrainCell> result = new ArrayList<>();
        Map<Long, CellAccumulator> grouped = new HashMap<>();
        Map<Long, SurfaceChunk> chunksByPosition = new HashMap<>(chunks.size() * 2);
        for (SurfaceChunk chunk : chunks) chunksByPosition.put(pack(chunk.chunkX(), chunk.chunkZ()), chunk);
        int actualRadius = Math.min(MAX_DISTANCE, radius);
        // The first FarView tile touches the outside edge of the last vanilla chunk.
        int nearCutoff = Math.max(1, vanillaDistance);
        int detailRadius = Math.min(64, Math.max(nearCutoff, vanillaDistance + 8));
        int zoomFineRadius = Math.min(detailRadius, Math.max(nearCutoff, vanillaDistance + 4));
        double minimumFacing = Math.cos(Math.toRadians(cameraFov * 0.5 + 30.0));
        for (SurfaceChunk chunk : chunks) {
            int cx = chunk.chunkX(), cz = chunk.chunkZ();
            double dx = cx - centerX, dz = cz - centerZ;
            double distance = Math.hypot(dx, dz);
            if (distance <= nearCutoff || distance > actualRadius) continue;
            if (facingX * facingX + facingZ * facingZ > 0.5
                    && (dx * facingX + dz * facingZ) / distance < minimumFacing) continue;
            if (zoomed && distance <= zoomFineRadius) {
                addCells(result, chunk, chunksByPosition, 16);
                continue;
            }
            if (zoomed && distance <= detailRadius) {
                addCells(result, chunk, chunksByPosition, 8);
                continue;
            }
            if (zoomed && distance <= 64) {
                addCells(result, chunk, chunksByPosition, 4);
                continue;
            }
            if (!zoomed && distance <= detailRadius) {
                addCells(result, chunk, chunksByPosition, 8);
                continue;
            }
            if (!zoomed && distance <= 64) {
                addCells(result, chunk, chunksByPosition, 2);
                continue;
            }
            int groupChunks;
            if (zoomed) groupChunks = distance <= 256 ? 2 : distance <= 512 ? 4 : 16;
            else groupChunks = distance <= 256 ? 4 : 16;
            int gx = Math.floorDiv(cx, groupChunks), gz = Math.floorDiv(cz, groupChunks);
            long key = pack(gx, gz);
            grouped.computeIfAbsent(key, ignored -> new CellAccumulator(gx, gz, groupChunks))
                    .add(chunk.averageHeight(), chunk.averageColor(), chunk.topTexture(8, 8), chunk.sideTexture(8, 8));
        }
        for (CellAccumulator cell : grouped.values()) result.add(cell.finish());
        return List.copyOf(result);
    }

    private static void addCells(List<TerrainCell> output, SurfaceChunk chunk,
                                 Map<Long, SurfaceChunk> chunksByPosition, int gridSize) {
        float size = 16f / gridSize;
        for (int z = 0; z < gridSize; z++) {
            for (int x = 0; x < gridSize; x++) {
                float y = gridHeight(chunk, x, z, gridSize);
                double worldX = (chunk.chunkX() << 4) + (x + 0.5) * size;
                double worldZ = (chunk.chunkZ() << 4) + (z + 0.5) * size;
                float drop = Math.max(16f, size * 2f);
                float west = neighborHeight(chunksByPosition, chunk.chunkX(), chunk.chunkZ(), gridSize,
                        x - 1, z, y - drop);
                float east = neighborHeight(chunksByPosition, chunk.chunkX(), chunk.chunkZ(), gridSize,
                        x + 1, z, y - drop);
                float north = neighborHeight(chunksByPosition, chunk.chunkX(), chunk.chunkZ(), gridSize,
                        x, z - 1, y - drop);
                float south = neighborHeight(chunksByPosition, chunk.chunkX(), chunk.chunkZ(), gridSize,
                        x, z + 1, y - drop);
                int color = gridColor(chunk, x, z, gridSize);
                String topTexture = gridTexture(chunk, x, z, gridSize, true);
                String sideTexture = gridTexture(chunk, x, z, gridSize, false);
                output.add(new TerrainCell(worldX, worldZ, size, y,
                        west, east, north, south, color, topTexture, sideTexture));
            }
        }
    }

    private static float neighborHeight(Map<Long, SurfaceChunk> chunksByPosition, int chunkX, int chunkZ,
                                        int gridSize, int cellX, int cellZ, float fallback) {
        int chunkOffsetX = Math.floorDiv(cellX, gridSize);
        int chunkOffsetZ = Math.floorDiv(cellZ, gridSize);
        SurfaceChunk neighbor = chunksByPosition.get(pack(chunkX + chunkOffsetX, chunkZ + chunkOffsetZ));
        if (neighbor == null) return fallback;
        return gridHeight(neighbor, Math.floorMod(cellX, gridSize), Math.floorMod(cellZ, gridSize), gridSize);
    }

    private static float gridHeight(SurfaceChunk chunk, int cellX, int cellZ, int gridSize) {
        int step = SAMPLE_GRID / gridSize;
        long sum = 0;
        int count = 0;
        for (int z = 0; z < step; z++) {
            for (int x = 0; x < step; x++) {
                int sx = cellX * step + x;
                int sz = cellZ * step + z;
                sum += chunk.height(sx, sz);
                count++;
            }
        }
        return Math.floorDiv((int) (sum / count), 2) * 2f;
    }

    private static int gridColor(SurfaceChunk chunk, int cellX, int cellZ, int gridSize) {
        int step = SAMPLE_GRID / gridSize;
        long red = 0, green = 0, blue = 0;
        int count = 0;
        for (int z = 0; z < step; z++) {
            for (int x = 0; x < step; x++) {
                int sx = cellX * step + x;
                int sz = cellZ * step + z;
                int color = chunk.color(sx, sz);
                red += (color >>> 16) & 255;
                green += (color >>> 8) & 255;
                blue += color & 255;
                count++;
            }
        }
        return (int) ((red / count << 16) | (green / count << 8) | (blue / count));
    }

    private static String gridTexture(SurfaceChunk chunk, int cellX, int cellZ, int gridSize, boolean top) {
        int step = SAMPLE_GRID / gridSize;
        int startX = cellX * step;
        int startZ = cellZ * step;
        if (step == 1) return textureAt(chunk, startX, startZ, top);
        Map<String, Integer> counts = new HashMap<>();
        String mostCommon = SurfaceChunk.FALLBACK_TEXTURE_ID;
        int highestCount = 0;
        for (int z = 0; z < step; z++) {
            for (int x = 0; x < step; x++) {
                String texture = textureAt(chunk, startX + x, startZ + z, top);
                int count = counts.merge(texture, 1, Integer::sum);
                if (count > highestCount) {
                    highestCount = count;
                    mostCommon = texture;
                }
            }
        }
        return mostCommon;
    }

    private static String textureAt(SurfaceChunk chunk, int x, int z, boolean top) {
        String texture = top ? chunk.topTexture(x, z) : chunk.sideTexture(x, z);
        return texture == null ? SurfaceChunk.FALLBACK_TEXTURE_ID : texture;
    }

    public void clear() {
        generation++;
        cells = List.of();
        builtRevision = -1;
        builtCenterX = Integer.MIN_VALUE;
        builtCenterZ = Integer.MIN_VALUE;
        builtRadius = -1;
        builtVanillaDistance = -1;
        builtZoomed = false;
        builtFacingX = 0;
        builtFacingZ = 0;
        builtFov = -1;
    }

    private static long pack(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    private record TerrainCell(double x, double z, float size, float y,
                               float westY, float eastY, float northY, float southY,
                               int color, String topTexture, String sideTexture) {
        private static TerrainCell flat(double x, double z, float size, float y, int color,
                                        String topTexture, String sideTexture) {
            return new TerrainCell(x, z, size, y, y, y, y, y, color, topTexture, sideTexture);
        }
    }

    private static final class CellAccumulator {
        private final int gridX, gridZ, groupChunks;
        private final Map<String, Integer> topCounts = new HashMap<>();
        private final Map<String, Integer> sideCounts = new HashMap<>();
        private long heightSum, redSum, greenSum, blueSum;
        private int count;
        private CellAccumulator(int gridX, int gridZ, int groupChunks) {
            this.gridX = gridX; this.gridZ = gridZ; this.groupChunks = groupChunks;
        }
        private void add(int height, int color, String top, String side) {
            heightSum += height; redSum += (color >>> 16) & 255; greenSum += (color >>> 8) & 255;
            blueSum += color & 255; count++;
            topCounts.merge(top, 1, Integer::sum);
            sideCounts.merge(side, 1, Integer::sum);
        }
        private TerrainCell finish() {
            int color = (int) ((redSum / count << 16) | (greenSum / count << 8) | (blueSum / count));
            double x = ((long) gridX * groupChunks * 16L) + groupChunks * 8.0;
            double z = ((long) gridZ * groupChunks * 16L) + groupChunks * 8.0;
            return TerrainCell.flat(x, z, groupChunks * 16f, heightSum / (float) count, color,
                    mostCommon(topCounts), mostCommon(sideCounts));
        }
        private static String mostCommon(Map<String, Integer> counts) {
            String selected = SurfaceChunk.FALLBACK_TEXTURE_ID;
            int highest = 0;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > highest) {
                    highest = entry.getValue();
                    selected = entry.getKey();
                }
            }
            return selected;
        }
    }
}

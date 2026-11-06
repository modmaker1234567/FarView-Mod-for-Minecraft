package dev.farview.client.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/** Small, atlas-backed surface summary for a previously received client chunk. */
public final class SurfaceChunk {
    public static final int SAMPLE_COUNT = 16 * 16;
    public static final int SAMPLE_GRID = 16;
    public static final String FALLBACK_TEXTURE_ID = "minecraft:block/stone";
    private static final int BLOCKS_PER_SAMPLE = 16 / SAMPLE_GRID;
    private static final ConcurrentMap<BlockState, TextureInfo> TEXTURE_CACHE = new ConcurrentHashMap<>();

    private final int chunkX;
    private final int chunkZ;
    private final short[] heights;
    private final int[] colors;
    private final String[] topTextures;
    private final String[] sideTextures;

    public SurfaceChunk(int chunkX, int chunkZ, short[] heights, int[] colors,
                        String[] topTextures, String[] sideTextures) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.heights = heights;
        this.colors = colors;
        this.topTextures = topTextures;
        this.sideTextures = sideTextures;
    }

    public static SurfaceChunk capture(ClientLevel level, LevelChunk chunk) {
        int cx = chunk.getPos().x();
        int cz = chunk.getPos().z();
        short[] heights = new short[SAMPLE_COUNT];
        int[] colors = new int[SAMPLE_COUNT];
        String[] topTextures = new String[SAMPLE_COUNT];
        String[] sideTextures = new String[SAMPLE_COUNT];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Minecraft client = Minecraft.getInstance();

        for (int cellZ = 0; cellZ < SAMPLE_GRID; cellZ++) {
            for (int cellX = 0; cellX < SAMPLE_GRID; cellX++) {
                int x = cellX * BLOCKS_PER_SAMPLE + BLOCKS_PER_SAMPLE / 2;
                int z = cellZ * BLOCKS_PER_SAMPLE + BLOCKS_PER_SAMPLE / 2;
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                int surfaceY = Math.max(level.getMinY(), Math.min(level.getMaxY() - 1, y - 1));
                pos.set((cx << 4) + x, surfaceY, (cz << 4) + z);
                BlockState state = chunk.getBlockState(pos);
                TextureInfo texture = TEXTURE_CACHE.computeIfAbsent(state, SurfaceChunk::textureInfo);
                int color = texture.tintIndex < 0 ? 0xFFFFFF
                        : client.getBlockColors().getTintSource(state, texture.tintIndex)
                                .colorInWorld(state, level, pos);

                for (int dz = 0; dz < BLOCKS_PER_SAMPLE; dz++) {
                    for (int dx = 0; dx < BLOCKS_PER_SAMPLE; dx++) {
                        int index = (cellZ * BLOCKS_PER_SAMPLE + dz) * 16
                                + cellX * BLOCKS_PER_SAMPLE + dx;
                        heights[index] = (short) surfaceY;
                        colors[index] = color;
                        topTextures[index] = texture.top;
                        sideTextures[index] = texture.side;
                    }
                }
            }
        }
        return new SurfaceChunk(cx, cz, heights, colors, topTextures, sideTextures);
    }

    private static TextureInfo textureInfo(BlockState state) {
        BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>(2);
        model.collectParts(RandomSource.create(0L), parts);
        String top = null;
        String side = null;
        int tintIndex = -1;
        for (BlockStateModelPart part : parts) {
            if (top == null) {
                BakedQuad quad = firstQuad(part, Direction.UP);
                if (quad != null) {
                    top = quad.materialInfo().sprite().contents().name().toString();
                    if (quad.materialInfo().isTinted()) tintIndex = quad.materialInfo().tintIndex();
                }
            }
            if (side == null) {
                BakedQuad quad = firstQuad(part, Direction.NORTH);
                if (quad != null) side = quad.materialInfo().sprite().contents().name().toString();
            }
            if (top != null && side != null) break;
        }
        String particle = model.particleMaterial().sprite().contents().name().toString();
        if (top == null) top = particle;
        if (side == null) side = top;
        return new TextureInfo(top, side, tintIndex);
    }

    private static BakedQuad firstQuad(BlockStateModelPart part, Direction direction) {
        List<BakedQuad> quads = part.getQuads(direction);
        return quads.isEmpty() ? null : quads.get(0);
    }

    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public int height(int x, int z) { return heights[z * 16 + x]; }
    public int color(int x, int z) { return colors[z * 16 + x]; }
    public String topTexture(int x, int z) { return topTextures[z * 16 + x]; }
    public String sideTexture(int x, int z) { return sideTextures[z * 16 + x]; }
    public String topTexture(int sampleIndex) { return topTextures[sampleIndex]; }
    public String sideTexture(int sampleIndex) { return sideTextures[sampleIndex]; }

    public int averageHeight() {
        long sum = 0;
        for (short height : heights) sum += height;
        return (int) (sum / heights.length);
    }

    public int averageColor() {
        long r = 0, g = 0, b = 0;
        for (int color : colors) {
            r += (color >>> 16) & 255;
            g += (color >>> 8) & 255;
            b += color & 255;
        }
        return (int) (((r / colors.length) << 16) | ((g / colors.length) << 8) | (b / colors.length));
    }

    private record TextureInfo(String top, String side, int tintIndex) { }
}

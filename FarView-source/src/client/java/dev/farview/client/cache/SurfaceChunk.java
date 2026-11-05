package dev.farview.client.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.client.multiplayer.ClientLevel;

public final class SurfaceChunk {
    public static final int SAMPLE_COUNT = 16 * 16;
    public static final int SAMPLE_GRID = 16;
    private static final int BLOCKS_PER_SAMPLE = 16 / SAMPLE_GRID;
    private final int chunkX;
    private final int chunkZ;
    private final short[] heights;
    private final int[] colors;

    public SurfaceChunk(int chunkX, int chunkZ, short[] heights, int[] colors) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.heights = heights;
        this.colors = colors;
    }

    public static SurfaceChunk capture(ClientLevel level, LevelChunk chunk) {
        int cx = chunk.getPos().x();
        int cz = chunk.getPos().z();
        short[] heights = new short[SAMPLE_COUNT];
        int[] colors = new int[SAMPLE_COUNT];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cellZ = 0; cellZ < SAMPLE_GRID; cellZ++) {
            for (int cellX = 0; cellX < SAMPLE_GRID; cellX++) {
                int x = cellX * BLOCKS_PER_SAMPLE + BLOCKS_PER_SAMPLE / 2;
                int z = cellZ * BLOCKS_PER_SAMPLE + BLOCKS_PER_SAMPLE / 2;
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                int surfaceY = Math.max(level.getMinY(), Math.min(level.getMaxY() - 1, y - 1));
                pos.set((cx << 4) + x, surfaceY, (cz << 4) + z);
                BlockState state = chunk.getBlockState(pos);
                int color = colorFor(state);
                for (int dz = 0; dz < BLOCKS_PER_SAMPLE; dz++) {
                    for (int dx = 0; dx < BLOCKS_PER_SAMPLE; dx++) {
                        int index = (cellZ * BLOCKS_PER_SAMPLE + dz) * 16
                                + cellX * BLOCKS_PER_SAMPLE + dx;
                        heights[index] = (short) surfaceY;
                        colors[index] = color;
                    }
                }
            }
        }
        return new SurfaceChunk(cx, cz, heights, colors);
    }

    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public int height(int x, int z) { return heights[z * 16 + x]; }
    public int color(int x, int z) { return colors[z * 16 + x]; }

    public int averageHeight() {
        long sum = 0;
        for (short height : heights) sum += height;
        return (int) (sum / heights.length);
    }

    public int averageColor() {
        long r = 0, g = 0, b = 0;
        for (int color : colors) { r += (color >>> 16) & 255; g += (color >>> 8) & 255; b += color & 255; }
        return (int) (((r / colors.length) << 16) | ((g / colors.length) << 8) | (b / colors.length));
    }

    private static int colorFor(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (id.contains("water")) return 0x386B9B;
        if (id.contains("lava")) return 0xE76620;
        if (id.contains("snow") || id.contains("ice")) return 0xDCEAF1;
        if (id.contains("sand") || id.contains("sandstone")) return 0xD7C47D;
        if (id.contains("grass") || id.contains("fern") || id.contains("moss") || id.contains("leaves")) return 0x659748;
        if (id.contains("dirt") || id.contains("mud") || id.contains("podzol")) return 0x79583E;
        if (id.contains("deepslate")) return 0x51555C;
        if (id.contains("stone") || id.contains("andesite") || id.contains("diorite") || id.contains("granite")) return 0x898783;
        if (id.contains("netherrack") || id.contains("crimson") || id.contains("nether")) return 0x8B403A;
        if (id.contains("end_stone")) return 0xC5B96F;
        if (id.contains("log") || id.contains("wood") || id.contains("planks")) return 0x80613D;
        return 0x8A9186;
    }
}

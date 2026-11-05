package dev.farview.client.cache;

import dev.farview.client.FarViewClient;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Region-based, bounded-memory terrain cache. Chunk IO never runs on the render thread. */
public final class TerrainCache implements AutoCloseable {
    private static final int REGION_MAGIC = 0x46565231;
    private static final int RECORD_MAGIC = 0x46564331;
    private static final int REGION_SIZE = 32;
    private static final int BITMAP_BYTES = REGION_SIZE * REGION_SIZE / 8;
    private static final int HEADER_BYTES = 8 + BITMAP_BYTES;
    private static final int RECORD_BYTES = 12 + SurfaceChunk.SAMPLE_COUNT * 6;
    private static final int MAX_LOAD_REQUESTS_PER_TICK = 256;

    private final Path directory;
    private final long memoryLimitBytes;
    private final LinkedHashMap<Long, SurfaceChunk> memory = new LinkedHashMap<>(256, 0.75f, true);
    private final Map<Long, Set<Long>> knownByRegion = new ConcurrentHashMap<>();
    private final Set<Long> known = ConcurrentHashMap.newKeySet();
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();
    private final ArrayBlockingQueue<Long> loads = new ArrayBlockingQueue<>(8192);
    private final ExecutorService io;
    private final ExecutorService loader;
    private final ExecutorService mesher;
    private final CompletableFuture<Void> indexReady = new CompletableFuture<>();
    private volatile boolean closed;
    private volatile long revision;
    private long memoryBytes;
    private int loadTick;

    public TerrainCache(Path root, String identity, int memoryBudgetMb) {
        this.directory = root.resolve(hash(identity));
        this.memoryLimitBytes = (long) memoryBudgetMb * 1024L * 1024L;
        this.io = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8192), daemonFactory("FarView-CacheIO"),
                new ThreadPoolExecutor.DiscardOldestPolicy());
        this.loader = Executors.newSingleThreadExecutor(daemonFactory("FarView-CacheLoad"));
        this.mesher = Executors.newSingleThreadExecutor(daemonFactory("FarView-MeshBuild"));
        io.execute(() -> { readRegionIndex(); indexReady.complete(null); });
        loader.execute(this::loadLoop);
    }

    public void capture(net.minecraft.client.multiplayer.ClientLevel level,
                        net.minecraft.world.level.chunk.LevelChunk chunk) {
        if (closed) return;
        try {
            SurfaceChunk tile = SurfaceChunk.capture(level, chunk);
            put(tile);
            long chunkKey = key(tile.chunkX(), tile.chunkZ());
            if (known.add(chunkKey)) addKnown(chunkKey);
            revision++;
            io.execute(() -> writeTile(tile));
        } catch (Throwable error) {
            FarViewClient.LOGGER.debug("Skipping unreadable FarView chunk", error);
        }
    }

    public List<SurfaceChunk> snapshot() {
        synchronized (memory) { return new ArrayList<>(memory.values()); }
    }

    public long revision() { return revision; }
    public ExecutorService mesher() { return mesher; }

    public void requestNearbyLoads(int centerX, int centerZ, int radius) {
        if (closed || ++loadTick % 5 != 0) return;
        int regionRadius = (radius + REGION_SIZE - 1) / REGION_SIZE + 1;
        int centerRegionX = Math.floorDiv(centerX, REGION_SIZE);
        int centerRegionZ = Math.floorDiv(centerZ, REGION_SIZE);
        int requested = 0;
        for (int ring = 0; ring <= regionRadius && requested < MAX_LOAD_REQUESTS_PER_TICK; ring++) {
            for (int dz = -ring; dz <= ring && requested < MAX_LOAD_REQUESTS_PER_TICK; dz++) {
                for (int dx = -ring; dx <= ring && requested < MAX_LOAD_REQUESTS_PER_TICK; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    Set<Long> region = knownByRegion.get(key(centerRegionX + dx, centerRegionZ + dz));
                    if (region == null) continue;
                    for (long chunkKey : region) {
                        int cx = x(chunkKey), cz = z(chunkKey);
                        if (Math.max(Math.abs(cx - centerX), Math.abs(cz - centerZ)) > radius) continue;
                        if (contains(cx, cz) || !queued.add(chunkKey)) continue;
                        if (loads.offer(chunkKey)) requested++;
                        else queued.remove(chunkKey);
                        if (requested >= MAX_LOAD_REQUESTS_PER_TICK) break;
                    }
                }
            }
        }
    }

    private void readRegionIndex() {
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".fvr")).forEach(this::readRegionHeader);
        } catch (IOException error) {
            FarViewClient.LOGGER.warn("Could not scan FarView region cache", error);
        }
    }

    private void readRegionHeader(Path file) {
        String name = file.getFileName().toString();
        try {
            String[] coords = name.substring(1, name.length() - 4).split("_");
            int regionX = Integer.parseInt(coords[0]);
            int regionZ = Integer.parseInt(coords[1]);
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                if (in.readInt() != REGION_MAGIC) return;
                in.readInt();
                byte[] bitmap = in.readNBytes(BITMAP_BYTES);
                if (bitmap.length != BITMAP_BYTES) return;
                for (int i = 0; i < REGION_SIZE * REGION_SIZE; i++) {
                    if ((bitmap[i >>> 3] & (1 << (i & 7))) == 0) continue;
                    int cx = regionX * REGION_SIZE + (i % REGION_SIZE);
                    int cz = regionZ * REGION_SIZE + (i / REGION_SIZE);
                    long chunkKey = key(cx, cz);
                    if (known.add(chunkKey)) addKnown(chunkKey);
                }
            }
        } catch (Throwable error) {
            FarViewClient.LOGGER.debug("Ignoring damaged FarView region header", error);
        }
    }

    private void addKnown(long chunkKey) {
        int cx = x(chunkKey), cz = z(chunkKey);
        long regionKey = key(Math.floorDiv(cx, REGION_SIZE), Math.floorDiv(cz, REGION_SIZE));
        knownByRegion.computeIfAbsent(regionKey, ignored -> ConcurrentHashMap.newKeySet()).add(chunkKey);
    }

    private void loadLoop() {
        indexReady.join();
        while (!closed || !loads.isEmpty()) {
            Long request = null;
            try {
                request = loads.poll(250, TimeUnit.MILLISECONDS);
                if (request == null) continue;
                SurfaceChunk tile = readTile(x(request), z(request));
                if (tile != null) { put(tile); revision++; }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable error) {
                FarViewClient.LOGGER.debug("Could not load FarView chunk", error);
            } finally {
                if (request != null) queued.remove(request);
            }
        }
    }

    private void put(SurfaceChunk tile) {
        long chunkKey = key(tile.chunkX(), tile.chunkZ());
        synchronized (memory) {
            SurfaceChunk previous = memory.put(chunkKey, tile);
            if (previous == null) memoryBytes += estimatedBytes();
            while (memoryBytes > memoryLimitBytes && !memory.isEmpty()) {
                var eldest = memory.entrySet().iterator();
                eldest.next(); eldest.remove(); memoryBytes -= estimatedBytes();
            }
        }
    }

    private void writeTile(SurfaceChunk tile) {
        int cx = tile.chunkX(), cz = tile.chunkZ();
        int regionX = Math.floorDiv(cx, REGION_SIZE), regionZ = Math.floorDiv(cz, REGION_SIZE);
        int localX = Math.floorMod(cx, REGION_SIZE), localZ = Math.floorMod(cz, REGION_SIZE);
        int bitIndex = localZ * REGION_SIZE + localX;
        try {
            Files.createDirectories(directory);
            try (RandomAccessFile file = new RandomAccessFile(regionPath(regionX, regionZ).toFile(), "rw")) {
                boolean validHeader = false;
                if (file.length() >= HEADER_BYTES) {
                    file.seek(0);
                    validHeader = file.readInt() == REGION_MAGIC;
                }
                if (!validHeader) {
                    file.setLength(0);
                    file.writeInt(REGION_MAGIC);
                    file.writeInt(1);
                    file.write(new byte[BITMAP_BYTES]);
                }
                long offset = HEADER_BYTES + (long) bitIndex * RECORD_BYTES;
                file.seek(offset);
                file.writeInt(RECORD_MAGIC); file.writeInt(cx); file.writeInt(cz);
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    file.writeShort(tile.height(x, z)); file.writeInt(tile.color(x, z));
                }
                long bitmapOffset = 8L + (bitIndex >>> 3);
                file.seek(bitmapOffset);
                int current = file.readUnsignedByte();
                file.seek(bitmapOffset);
                file.writeByte(current | (1 << (bitIndex & 7)));
            }
        } catch (IOException error) {
            FarViewClient.LOGGER.debug("Could not save FarView terrain region", error);
        }
    }

    private SurfaceChunk readTile(int cx, int cz) {
        int regionX = Math.floorDiv(cx, REGION_SIZE), regionZ = Math.floorDiv(cz, REGION_SIZE);
        int localX = Math.floorMod(cx, REGION_SIZE), localZ = Math.floorMod(cz, REGION_SIZE);
        int bitIndex = localZ * REGION_SIZE + localX;
        Path path = regionPath(regionX, regionZ);
        if (!Files.exists(path)) return null;
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            if (file.length() < HEADER_BYTES || file.readInt() != REGION_MAGIC) return null;
            file.seek(HEADER_BYTES + (long) bitIndex * RECORD_BYTES);
            if (file.readInt() != RECORD_MAGIC || file.readInt() != cx || file.readInt() != cz) return null;
            short[] heights = new short[SurfaceChunk.SAMPLE_COUNT];
            int[] colors = new int[SurfaceChunk.SAMPLE_COUNT];
            for (int i = 0; i < heights.length; i++) { heights[i] = file.readShort(); colors[i] = file.readInt(); }
            return new SurfaceChunk(cx, cz, heights, colors);
        } catch (IOException error) {
            return null;
        }
    }

    private Path regionPath(int regionX, int regionZ) { return directory.resolve("r" + regionX + "_" + regionZ + ".fvr"); }
    private boolean contains(int cx, int cz) { synchronized (memory) { return memory.containsKey(key(cx, cz)); } }
    private static long estimatedBytes() { return 16L * 16L * 6L + 160L; }
    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    private static int x(long key) { return (int) (key >> 32); }
    private static int z(long key) { return (int) key; }

    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12; i++) out.append(String.format("%02x", bytes[i]));
            return out.toString();
        } catch (Exception impossible) { return Integer.toHexString(value.hashCode()); }
    }

    private static ThreadFactory daemonFactory(String name) {
        return task -> { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; };
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        io.shutdown(); loader.shutdown(); mesher.shutdown();
        try {
            io.awaitTermination(3, TimeUnit.SECONDS);
            loader.awaitTermination(3, TimeUnit.SECONDS);
            mesher.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

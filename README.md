# FarView (Fabric 26.3)

FarView is a prototype client-side mod that generates 3D, blocky distant terrain from chunks received by the client, without increasing Minecraft's stock render distance. At the highest LOD (Level of Detail), every block is sampled; samples are merged at further distances. Cells render top and side faces at elevation differences.

## Initial Release Behavior

* **Vanilla chunk rendering** is used for the immediate surroundings.
* **Surface height and approximate map color** are extracted from the chunks received by the client.
* **16×16 surface samples are stored per chunk.** Close-range LOD cells create a stepped terrain with top faces and darkened side faces.
* **Chunks are merged during normal view.** When the camera FOV narrows due to zooming, 16×16 geometry is used nearby, 8×8 in the next ring, and 4×4 further out.
* **Rendering and mesh preparation** are limited to a wide cone based on the view direction; hidden cells are not sent to the render queue.
* **Terrain summaries are saved to disk**, allowing records near the player to load in the background during subsequent sessions.
* **Chunks are grouped at long distances** to reduce geometry rendering overhead.
* **F8 cycles the render distance** between 128, 256, 512, and 1024 chunks (defaults to 1024 on first launch).
* **F9 toggles distant terrain rendering** on and off.
* **Memory cache is limited.** Disk and task queues do not block the main game thread.

In this initial prototype, foliage, structures, caves, block textures, shadows, and lightmaps are not included in the LOD. Surfaces are rendered as colored terrain meshes. Approaching the quality of mods like Voxy or Distant Horizons will require texture atlas support, biome tinting, chunk intersection/resource processing, mesh batching, and actual in-game visual testing in the next phase.

For distant chunks to be visible, the chunk data must have been previously explored or received from the server. The client cannot retrieve chunk data that has never been sent by a vanilla multiplayer server.

## Building

**Requirements:** Java 25, Fabric Loader 0.20.1, and Fabric API 0.165.0+26.3.

```powershell
.\gradlew.bat build
```

The resulting mod file will be located at `build/libs/farview-0.1.0.jar`. Place this JAR file into your `mods` folder within a Fabric Loader and Fabric API 26.3 installation.

## Settings

The `config/farview.properties` file is generated when the game is launched for the first time. F8 changes the distance, while F9 toggles distant terrain rendering.

* `maxDistanceChunks`: 128, 256, 512, or 1024
* `memoryBudgetMb`: The upper limit for the surface cache stored in memory (default is 512 MB, ranges from 128 MB to 16 GB)
* `enabled`: Toggles distant terrain rendering on/off

The cache is written under `config/farview/cache/` as separate region files based on the single-player world's save folder and dimension. When switching worlds, the RAM-resident mesh and chunk list are cleared; each save uses its own disk cache.

Zoom detection relies on the camera FOV narrowing relative to its normal value; if a zoom mod does not modify the FOV, the higher resolution may not be triggered. This is still a simplified heightmap approach; structures, foliage, caves, biome tints, and actual block textures are not included. In-game visual and performance benchmarking will be conducted during user testing.

## Crash Fix

In Minecraft 26.3, distant terrain rendering no longer opens a new GPU pass inside an active render pass; instead, the geometry is injected directly into the game's standard render queue. Surface sampling is lighter at 64 samples/chunk compared to the previous 256-sample capture method. In-game performance and visual tests will be carried out by the user.

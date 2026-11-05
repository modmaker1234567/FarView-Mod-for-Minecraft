
FarView (Fabric 26.3)

FarView is a prototype client-side mod that renders previously loaded chunk surfaces as low-detail terrain at long distances, without increasing Minecraft's stock render distance. As distant terrain comes closer, its detail dynamically increases.

Initial Release Behavior

• Vanilla chunk rendering is used for the immediate surroundings.
• Surface height and approximate map color are extracted from the chunks received by the client.
• Terrain summaries are saved to disk, allowing records near the player to load in the background during subsequent sessions.
• Chunks are grouped at long distances to reduce geometry rendering overhead.
• F8 cycles the render distance between 128, 256, 512, and 1024 chunks (defaults to 1024 on first launch).
• F9 toggles distant terrain rendering on and off.
• Memory cache is limited. Disk and task queues do not block the main game thread.
In this initial prototype, foliage, structures, caves, block textures, shadows, and lightmaps are not included in the LOD. Surfaces are rendered as colored terrain meshes. Approaching the quality of mods like Voxy or Distant Horizons will require texture atlas support, biome tinting, chunk intersection/resource processing, mesh batching, and actual in-game visual testing in the next phase.
For distant chunks to be visible, the chunk data must have been previously explored or received from the server. The client cannot retrieve chunk data that has never been sent by a vanilla multiplayer server.

Building

Requirements: Java 25, Fabric Loader 0.20.1, and Fabric API 0.165.0+26.3.

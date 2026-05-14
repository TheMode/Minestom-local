package net.minestom.web.internal.codec;

import com.google.gson.JsonObject;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.web.PlayerState;
import net.minestom.web.PlayerWorld;
import net.minestom.web.internal.renderer.MinimapRasterizer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/// Wire encoding for minimap v2: pre-rasterized 16×16 RGBA tiles (base64) plus unified pose
/// and entity markers on the same topic / HTTP snapshot.
public final class MinimapCodec {
    public static final int VERSION = 2;
    private static final Base64.Encoder BASE64 = Base64.getEncoder();

    private MinimapCodec() {
    }

    public static JsonObject snapshotJson(PlayerState player) {
        final List<Tile> tiles = new ArrayList<>();
        for (PlayerWorld.Chunk chunk : player.world.chunks.values()) {
            if (chunk.heights == null) continue;
            tiles.add(Tile.from(chunk));
        }
        return WebJson.encodeAsObject(Snapshot.CODEC,
                new Snapshot(Pose.from(player), PatchValue.visibleEntities(player), tiles));
    }

    /// Live frame: always carries pose + entity markers; terrain arrays only when dirty.
    public static JsonObject frameJson(PlayerState player) {
        final PlayerWorld world = player.world;
        List<Tile> loaded = null;
        List<Coord> unloaded = null;
        if (!world.dirtyChunks.isEmpty()) {
            loaded = new ArrayList<>(world.dirtyChunks.size());
            for (Long key : world.dirtyChunks) {
                final PlayerWorld.Chunk chunk = world.chunks.get(key);
                if (chunk != null && chunk.heights != null) loaded.add(Tile.from(chunk));
            }
            world.dirtyChunks.clear();
        }
        if (!world.unloadedChunks.isEmpty()) {
            unloaded = new ArrayList<>(world.unloadedChunks.size());
            for (Long key : world.unloadedChunks) {
                unloaded.add(new Coord(CoordConversion.chunkIndexGetX(key), CoordConversion.chunkIndexGetZ(key)));
            }
            world.unloadedChunks.clear();
        }
        return WebJson.encodeAsObject(Frame.CODEC,
                new Frame(Pose.from(player), PatchValue.visibleEntities(player), loaded, unloaded));
    }

    private record Pose(int v, double posX, double posY, double posZ, float yaw) {
        static Pose from(PlayerState p) {
            return new Pose(VERSION, p.posX, p.posY, p.posZ, p.yaw);
        }

        static final StructCodec<Pose> CODEC = StructCodec.struct(
                "v", Codec.INT, Pose::v,
                "posX", Codec.DOUBLE, Pose::posX,
                "posY", Codec.DOUBLE, Pose::posY,
                "posZ", Codec.DOUBLE, Pose::posZ,
                "yaw", Codec.FLOAT, Pose::yaw,
                Pose::new);
    }

    private record Tile(int x, int z, String tile) {
        static Tile from(PlayerWorld.Chunk chunk) {
            // Owner-thread only + rasterizer is read-only → no defensive array copy.
            return new Tile(chunk.chunkX, chunk.chunkZ,
                    BASE64.encodeToString(MinimapRasterizer.rasterize(chunk.heights, chunk.columnColors)));
        }

        static final StructCodec<Tile> CODEC = StructCodec.struct(
                "x", Codec.INT, Tile::x,
                "z", Codec.INT, Tile::z,
                "tile", Codec.STRING, Tile::tile,
                Tile::new);
    }

    private record Coord(int x, int z) {
        static final StructCodec<Coord> CODEC = StructCodec.struct(
                "x", Codec.INT, Coord::x,
                "z", Codec.INT, Coord::z,
                Coord::new);
    }

    private record Snapshot(Pose pose, List<PlayerState.VisibleEntityShort> entities, List<Tile> chunks) {
        static final StructCodec<Snapshot> CODEC = StructCodec.struct(
                StructCodec.INLINE, Pose.CODEC, Snapshot::pose,
                "entities", WebCodecs.VISIBLE_ENTITY_SHORT.list(), Snapshot::entities,
                "chunks", Tile.CODEC.list(), Snapshot::chunks,
                Snapshot::new);
    }

    private record Frame(Pose pose, List<PlayerState.VisibleEntityShort> entities,
                         @Nullable List<Tile> loaded, @Nullable List<Coord> unloaded) {
        static final StructCodec<Frame> CODEC = StructCodec.struct(
                StructCodec.INLINE, Pose.CODEC, Frame::pose,
                "entities", WebCodecs.VISIBLE_ENTITY_SHORT.list(), Frame::entities,
                "loaded", Tile.CODEC.list().optional(), Frame::loaded,
                "unloaded", Coord.CODEC.list().optional(), Frame::unloaded,
                Frame::new);
    }
}

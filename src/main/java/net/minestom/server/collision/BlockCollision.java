package net.minestom.server.collision;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import org.jetbrains.annotations.NotNull;

final class BlockCollision {
    /**
     * Moves an entity with physics applied (ie checking against blocks)
     */
    static PhysicsResult handlePhysics(@NotNull BoundingBox boundingBox,
                                       @NotNull Vec velocity, @NotNull Pos entityPosition,
                                       @NotNull Block.Getter getter,
                                       boolean singleCollision) {
        if (velocity.isZero()) {
            return new PhysicsResult(entityPosition, Vec.ZERO, false, false, false, false,
                    velocity, new Point[3], new Shape[3], new Point[3], false, SweepResult.NO_COLLISION);
        }

        // Process movement step by step to handle sliding
        Vec remainingVelocity = velocity;
        Pos currentPosition = entityPosition;

        boolean collisionX = false, collisionY = false, collisionZ = false;
        Point[] collisionPoints = new Point[3];
        Shape[] collisionShapes = new Shape[3];
        Point[] collisionShapePositions = new Point[3];
        boolean hasCollision = false;
        SweepResult finalResult = new SweepResult(1.0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0);

        // Iterate until no more movement is possible
        for (int iteration = 0; iteration < 3 && !remainingVelocity.isZero(); iteration++) {
            SweepResult stepResult = new SweepResult(1.0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0);

            // Test collision for this step
            collectAndTestBlocks(boundingBox, remainingVelocity, currentPosition, getter, stepResult);

            if (stepResult.collidedShape == null) {
                // No collision, move fully
                currentPosition = currentPosition.add(remainingVelocity);
                break;
            }

            // Move to collision point
            double deltaX = stepResult.res * remainingVelocity.x();
            double deltaY = stepResult.res * remainingVelocity.y();
            double deltaZ = stepResult.res * remainingVelocity.z();

            if (Math.abs(deltaX) < Vec.EPSILON) deltaX = 0;
            if (Math.abs(deltaY) < Vec.EPSILON) deltaY = 0;
            if (Math.abs(deltaZ) < Vec.EPSILON) deltaZ = 0;

            currentPosition = currentPosition.add(deltaX, deltaY, deltaZ);

            // Determine collision axes and remove velocity from those axes
            boolean stepCollisionX = stepResult.normalX != 0;
            boolean stepCollisionY = stepResult.normalY != 0;
            boolean stepCollisionZ = stepResult.normalZ != 0;

            if (stepCollisionX) {
                collisionX = true;
                collisionPoints[0] = new Vec(stepResult.collidedPositionX, stepResult.collidedPositionY, stepResult.collidedPositionZ);
                collisionShapes[0] = stepResult.collidedShape;
                collisionShapePositions[0] = new Vec(stepResult.collidedShapeX, stepResult.collidedShapeY, stepResult.collidedShapeZ);
            }
            if (stepCollisionY) {
                collisionY = true;
                collisionPoints[1] = new Vec(stepResult.collidedPositionX, stepResult.collidedPositionY, stepResult.collidedPositionZ);
                collisionShapes[1] = stepResult.collidedShape;
                collisionShapePositions[1] = new Vec(stepResult.collidedShapeX, stepResult.collidedShapeY, stepResult.collidedShapeZ);
            }
            if (stepCollisionZ) {
                collisionZ = true;
                collisionPoints[2] = new Vec(stepResult.collidedPositionX, stepResult.collidedPositionY, stepResult.collidedPositionZ);
                collisionShapes[2] = stepResult.collidedShape;
                collisionShapePositions[2] = new Vec(stepResult.collidedShapeX, stepResult.collidedShapeY, stepResult.collidedShapeZ);
            }

            hasCollision = true;
            finalResult = stepResult;

            if (singleCollision) break;

            // Calculate remaining velocity (remove movement on collision axes)
            double remainingX = stepCollisionX ? 0 : remainingVelocity.x() - deltaX;
            double remainingY = stepCollisionY ? 0 : remainingVelocity.y() - deltaY;
            double remainingZ = stepCollisionZ ? 0 : remainingVelocity.z() - deltaZ;

            remainingVelocity = new Vec(remainingX, remainingY, remainingZ);

            // Exit early if all axes are blocked or no remaining movement
            if ((stepCollisionX && stepCollisionY && stepCollisionZ) || remainingVelocity.isZero()) {
                break;
            }
        }

        // Calculate final velocity (remaining velocity on non-collision axes)
        Vec newVelocity = new Vec(collisionX ? 0 : velocity.x(),
                collisionY ? 0 : velocity.y(),
                collisionZ ? 0 : velocity.z());

        boolean isOnGround = collisionY && velocity.y() < 0;

        return new PhysicsResult(currentPosition, newVelocity, isOnGround,
                collisionX, collisionY, collisionZ, velocity,
                collisionPoints, collisionShapes, collisionShapePositions,
                hasCollision, finalResult, false);
    }

    /**
     * Collects all blocks that could intersect with the movement and tests them for collisions.
     */
    private static void collectAndTestBlocks(@NotNull BoundingBox boundingBox, @NotNull Vec velocity,
                                             @NotNull Pos entityPosition, @NotNull Block.Getter getter,
                                             @NotNull SweepResult finalResult) {
        // Calculate the expanded bounding box that encompasses the entire movement
        double minX = Math.min(boundingBox.minX() + entityPosition.x(),
                boundingBox.minX() + entityPosition.x() + velocity.x());
        double maxX = Math.max(boundingBox.maxX() + entityPosition.x(),
                boundingBox.maxX() + entityPosition.x() + velocity.x());
        double minY = Math.min(boundingBox.minY() + entityPosition.y(),
                boundingBox.minY() + entityPosition.y() + velocity.y());
        double maxY = Math.max(boundingBox.maxY() + entityPosition.y(),
                boundingBox.maxY() + entityPosition.y() + velocity.y());
        double minZ = Math.min(boundingBox.minZ() + entityPosition.z(),
                boundingBox.minZ() + entityPosition.z() + velocity.z());
        double maxZ = Math.max(boundingBox.maxZ() + entityPosition.z(),
                boundingBox.maxZ() + entityPosition.z() + velocity.z());

        // Convert to block coordinates
        int blockMinX = (int) Math.floor(minX);
        int blockMaxX = (int) Math.floor(maxX);
        int blockMinY = (int) Math.floor(minY);
        int blockMaxY = (int) Math.floor(maxY);
        int blockMinZ = (int) Math.floor(minZ);
        int blockMaxZ = (int) Math.floor(maxZ);

        // Test all blocks in the range
        for (int x = blockMinX; x <= blockMaxX; x++) {
            for (int y = blockMinY; y <= blockMaxY; y++) {
                for (int z = blockMinZ; z <= blockMaxZ; z++) {
                    checkBoundingBox(x, y, z, velocity, entityPosition, boundingBox, getter, finalResult);
                }
            }
        }
    }

    static Entity canPlaceBlockAt(Instance instance, Point blockPos, Block b) {
        for (Entity entity : instance.getNearbyEntities(blockPos, 3)) {
            if (!entity.preventBlockPlacement())
                continue;

            final boolean intersects;
            if (entity instanceof Player) {
                // Need to move player slightly away from block we're placing.
                // If player is at block 40 we cannot place a block at block 39 with side length 1 because the block will be in [39, 40]
                // For this reason we subtract a small amount from the player position
                Point playerPos = entity.getPosition().add(entity.getPosition().sub(blockPos).mul(0.0000001));
                intersects = b.registry().collisionShape().intersectBox(playerPos.sub(blockPos), entity.getBoundingBox());
            } else {
                intersects = b.registry().collisionShape().intersectBox(entity.getPosition().sub(blockPos), entity.getBoundingBox());
            }
            if (intersects) return entity;
        }
        return null;
    }

    /**
     * Check if a moving entity will collide with a block. Updates finalResult
     *
     * @param blockX         block x position
     * @param blockY         block y position
     * @param blockZ         block z position
     * @param entityVelocity entity movement vector
     * @param entityPosition entity position
     * @param boundingBox    entity bounding box
     * @param getter         block getter
     * @param finalResult    place to store final result of collision
     * @return true if entity finds collision, other false
     */
    static boolean checkBoundingBox(int blockX, int blockY, int blockZ,
                                    Vec entityVelocity, Pos entityPosition, BoundingBox boundingBox,
                                    Block.Getter getter, SweepResult finalResult) {
        // Don't step if chunk isn't loaded yet
        final Block currentBlock = getter.getBlock(blockX, blockY, blockZ, Block.Getter.Condition.TYPE);
        final Shape currentShape = currentBlock.registry().collisionShape();

        final boolean currentCollidable = !currentShape.relativeEnd().isZero();
        final boolean currentShort = currentShape.relativeEnd().y() < 0.5;

        // only consider the block below if our current shape is sufficiently short
        if (currentShort && shouldCheckLower(entityVelocity, entityPosition, blockX, blockY, blockZ)) {
            // we need to check below for a tall block (fence, wall, ...)
            final Vec belowPos = new Vec(blockX, blockY - 1, blockZ);
            final Block belowBlock = getter.getBlock(belowPos, Block.Getter.Condition.TYPE);
            final Shape belowShape = belowBlock.registry().collisionShape();

            final Vec currentPos = new Vec(blockX, blockY, blockZ);
            // don't fall out of if statement, we could end up redundantly grabbing a block, and we only need to
            // collision check against the current shape since the below shape isn't tall
            if (belowShape.relativeEnd().y() > 1) {
                // we should always check both shapes, so no short-circuit here, to handle properties where the bounding box
                // hits the current solid but misses the tall solid
                return belowShape.intersectBoxSwept(entityPosition, entityVelocity, belowPos, boundingBox, finalResult) |
                        (currentCollidable && currentShape.intersectBoxSwept(entityPosition, entityVelocity, currentPos, boundingBox, finalResult));
            } else {
                return currentCollidable && currentShape.intersectBoxSwept(entityPosition, entityVelocity, currentPos, boundingBox, finalResult);
            }
        }

        if (currentCollidable && currentShape.intersectBoxSwept(entityPosition, entityVelocity,
                new Vec(blockX, blockY, blockZ), boundingBox, finalResult)) {
            // if the current collision is sufficiently short, we might need to collide against the block below too
            if (currentShort) {
                final Vec belowPos = new Vec(blockX, blockY - 1, blockZ);
                final Block belowBlock = getter.getBlock(belowPos, Block.Getter.Condition.TYPE);
                final Shape belowShape = belowBlock.registry().collisionShape();
                // only do sweep if the below block is big enough to possibly hit
                if (belowShape.relativeEnd().y() > 1)
                    belowShape.intersectBoxSwept(entityPosition, entityVelocity, belowPos, boundingBox, finalResult);
            }
            return true;
        }
        return false;
    }

    private static boolean shouldCheckLower(Vec entityVelocity, Pos entityPosition, int blockX, int blockY, int blockZ) {
        final double yVelocity = entityVelocity.y();
        // if moving horizontally, just check if the floor of the entity's position is the same as the blockY
        if (yVelocity == 0) return Math.floor(entityPosition.y()) == blockY;
        final double xVelocity = entityVelocity.x();
        final double zVelocity = entityVelocity.z();
        // if moving straight up, don't bother checking for tall solids beneath anything
        // if moving straight down, only check for a tall solid underneath the last block
        if (xVelocity == 0 && zVelocity == 0)
            return yVelocity < 0 && blockY == Math.floor(entityPosition.y() + yVelocity);
        // default to true: if no x velocity, only consider YZ line, and vice-versa
        final boolean underYX = xVelocity != 0 && computeHeight(yVelocity, xVelocity, entityPosition.y(), entityPosition.x(), blockX) >= blockY;
        final boolean underYZ = zVelocity != 0 && computeHeight(yVelocity, zVelocity, entityPosition.y(), entityPosition.z(), blockZ) >= blockY;
        // true if the block is at or below the same height as a line drawn from the entity's position to its final
        // destination
        return underYX && underYZ;
    }

    /*
    computes the height of the entity at the given block position along a projection of the line it's travelling along
    (YX or YZ). the returned value will be greater than or equal to the block height if the block is along the lower
    layer of intersections with this line.
     */
    private static double computeHeight(double yVelocity, double velocity, double entityY, double pos, int blockPos) {
        final double m = yVelocity / velocity;
        /*
        offsetting by 1 is necessary with a positive slope, because we can clip the bottom-right corner of blocks
        without clipping the "bottom-left" (the smallest corner of the block on the YZ or YX plane). without the offset
        these would not be considered to be on the lowest layer, since our block position represents the bottom-left
        corner
         */
        return m * (blockPos - pos + (m > 0 ? 1 : 0)) + entityY;
    }
}

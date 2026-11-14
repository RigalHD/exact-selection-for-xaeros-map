package dev.exactselection;

/**
 * Minecraft's world border. A block coordinate is at least {@code -MAX} and below {@code MAX}; a block corner, such
 * as a polygon vertex, may also lie on {@code MAX} itself.
 */
final class WorldLimits {
    static final long MAX = 30_000_000L;

    private WorldLimits() {}
}

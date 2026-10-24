package dev.exactselection;

import xaero.lib.common.config.option.ConfigOption;
import xaero.map.config.primary.option.WorldMapPrimaryClientConfigOptions;

public final class AreaExportConfigOverride {
    public record Values(boolean multipleImages, int scaleDownSquare, boolean highlights) {}
    private static final ThreadLocal<Values> ACTIVE = new ThreadLocal<>();

    private AreaExportConfigOverride() {}

    public static Scope begin(Values values) {
        Values previous = ACTIVE.get();
        ACTIVE.set(values);
        return () -> {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        };
    }

    public static Object override(ConfigOption<?> option, Object original) {
        Values values = ACTIVE.get();
        if (values == null) return original;
        if (option == WorldMapPrimaryClientConfigOptions.EXPORT_MULTIPLE_IMAGES) return values.multipleImages();
        if (option == WorldMapPrimaryClientConfigOptions.EXPORT_SCALE_DOWN_SQUARE) return values.scaleDownSquare();
        if (option == WorldMapPrimaryClientConfigOptions.EXPORT_HIGHLIGHTS) return values.highlights();
        return original;
    }

    @FunctionalInterface public interface Scope extends AutoCloseable {
        @Override void close();
    }
}

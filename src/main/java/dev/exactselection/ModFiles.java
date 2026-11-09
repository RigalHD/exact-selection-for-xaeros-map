package dev.exactselection;

import java.nio.file.Path;

/** Where the mod keeps its files. The folder keeps its first name so existing installations find their areas. */
final class ModFiles {
    private ModFiles() {}

    /** {@code <config>/xaero-exact-selection}: settings, area files of every world and the exchange folder. */
    static Path directory(Path configRoot) { return configRoot.resolve("xaero-exact-selection"); }
}

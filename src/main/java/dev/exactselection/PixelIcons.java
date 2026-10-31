package dev.exactselection;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;

/** The panel's small pixel icons, drawn with fills so they stay crisp at any GUI scale. */
final class PixelIcons {
    static final String[] COPY = {
            "####...",
            "#..#...",
            "#.#####",
            "#.#...#",
            "###...#",
            "..#...#",
            "..#####",
    };

    /** Double chevrons: down to expand the panel, up to make it compact. */
    static final String[] EXPAND = {
            "##.....##",
            ".##...##.",
            "..##.##..",
            "...###...",
            "....#....",
            "##.....##",
            ".##...##.",
            "..##.##..",
            "...###...",
            "....#....",
    };

    static final String[] COLLAPSE = {
            "....#....",
            "...###...",
            "..##.##..",
            ".##...##.",
            "##.....##",
            "....#....",
            "...###...",
            "..##.##..",
            ".##...##.",
            "##.....##",
    };

    static final String[] RULER = {
            "#########",
            "#.#.#.#.#",
            "#.#...#.#",
            "#.......#",
            "#########"
    };
    static final String[] FOLDER = {
            "###......",
            "#########",
            "#.......#",
            "#.......#",
            "#.......#",
            "#.......#",
            "#########",
    };

    static final String[] GEAR = {
            "....#....",
            ".#.###.#.",
            "..#####..",
            ".###.###.",
            "###...###",
            ".###.###.",
            "..#####..",
            ".#.###.#.",
            "....#....",
    };

    /** A target: shows an area on the map. */
    static final String[] TARGET = {
            "....#....",
            "..#####..",
            ".#..#..#.",
            ".#..#..#.",
            "#########",
            ".#..#..#.",
            ".#..#..#.",
            "..#####..",
            "....#....",
    };

    private PixelIcons() {}

    /** A pixel icon centred on an empty button, with a one-pixel shadow like the button labels. */
    static void renderIcon(GuiGraphics g, String[] icon, Button button, int color) {
        renderIcon(g, icon, button.getX() + (button.getWidth() - icon[0].length()) / 2,
                button.getY() + (button.getHeight() - icon.length) / 2, color);
    }

    static void renderIcon(GuiGraphics g, String[] icon, int x, int y, int color) {
        int shadow = 0xFF000000 | (color & 0xFCFCFC) >> 2;
        for (int pass = 0; pass < 2; pass++) {
            int offset = pass == 0 ? 1 : 0;
            for (int row = 0; row < icon.length; row++)
                for (int column = 0; column < icon[row].length(); column++)
                    if (icon[row].charAt(column) == '#')
                        g.fill(x + column + offset, y + row + offset, x + column + offset + 1, y + row + offset + 1,
                                pass == 0 ? shadow : color);
        }
    }
}

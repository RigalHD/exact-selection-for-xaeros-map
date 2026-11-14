package dev.exactselection;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;

/**
 * The panel's small pixel icons, drawn with fills so they stay crisp at any GUI scale: '#' is the icon's color with a
 * shadow, '+' the same color at 40%, 'o' the dark centre of a handle.
 */
final class PixelIcons {
    /** The tool's toggle: a selection with its corner handles, as the map draws it; hollow while the tool is off. */
    static final String[] TOOL = {
        "###.......###",
        "#o#########o#",
        "###.......###",
        ".#.........#.",
        ".#.........#.",
        ".#.........#.",
        ".#.........#.",
        ".#.........#.",
        "###.......###",
        "#o#########o#",
        "###.......###",
    };

    /** The toggle while the tool is on: the selection filled, as on the map. */
    static final String[] TOOL_ON = {
        "###.......###",
        "#o#########o#",
        "###+++++++###",
        ".#+++++++++#.",
        ".#+++++++++#.",
        ".#+++++++++#.",
        ".#+++++++++#.",
        ".#+++++++++#.",
        "###+++++++###",
        "#o#########o#",
        "###.......###",
    };

    static final String[] COPY = {
        "####...", "#..#...", "#.#####", "#.#...#", "###...#", "..#...#", "..#####",
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

    static final String[] RULER = {"#########", "#.#.#.#.#", "#.#...#.#", "#.......#", "#########"};
    static final String[] FOLDER = {
        "###......", "#########", "#.......#", "#.......#", "#.......#", "#.......#", "#########",
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
        renderIcon(
                g,
                icon,
                button.getX() + (button.getWidth() - icon[0].length()) / 2,
                button.getY() + (button.getHeight() - icon.length) / 2,
                color);
    }

    static void renderIcon(GuiGraphics g, String[] icon, int x, int y, int color) {
        int shadow = 0xFF000000 | (color & 0xFCFCFC) >> 2;
        int fill = 0x66000000 | (color & 0xFFFFFF);
        for (int row = 0; row < icon.length; row++)
            for (int column = 0; column < icon[row].length(); column++)
                if (icon[row].charAt(column) == '#')
                    g.fill(x + column + 1, y + row + 1, x + column + 2, y + row + 2, shadow);
        for (int row = 0; row < icon.length; row++)
            for (int column = 0; column < icon[row].length(); column++) {
                int pixel = switch (icon[row].charAt(column)) {
                    case '#' -> color;
                    case '+' -> fill;
                    case 'o' -> Theme.MARKER;
                    default -> 0;
                };
                if (pixel != 0) g.fill(x + column, y + row, x + column + 1, y + row + 1, pixel);
            }
    }
}

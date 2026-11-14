package dev.exactselection;

/** The tool's colors, as ARGB: teal accents on dark blue-grey panels, like Xaero's map behind them. */
final class Theme {
    /** Titles, the selection, its handles and the checked state. */
    static final int ACCENT = 0xFF59E3CE;
    /** Hints, captions and secondary values. */
    static final int TEXT_MUTED = 0xFF9BAAB7;
    /** Messages that ask for attention, the edited area's name and framed waypoints. */
    static final int WARNING = 0xFFFFCF62;
    /** A color or value that cannot be used. */
    static final int INVALID = 0xFFFF5555;
    /** Window and panel frames and dividers. */
    static final int BORDER = 0xFF42636D;

    static final int PANEL_BACKGROUND = 0xEE101922;
    /** The well behind a list of rows. */
    static final int LIST_BACKGROUND = 0xFF0C141D;

    static final int ROW_CHECKED = 0x8842636D;
    /** An unchecked box or a hidden group. */
    static final int INACTIVE = 0xFF64717A;
    /** Pixel icons on buttons, and on buttons that cannot be pressed. */
    static final int ICON = 0xFFE0E0E0, ICON_DISABLED = 0xFF8A8A8A;
    /** The dark square inside a handle, vertex or ruler point, on the map and on the tool's toggle. */
    static final int MARKER = 0xFF101922;

    private Theme() {}
}

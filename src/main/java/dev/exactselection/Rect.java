package dev.exactselection;

/** A rectangle on screen in GUI pixels: its top-left corner and its size. */
record Rect(int x, int y, int width, int height) {
    int right() {
        return x + width;
    }

    int bottom() {
        return y + height;
    }

    boolean contains(double px, double py) {
        return px >= x && px < right() && py >= y && py < bottom();
    }
}

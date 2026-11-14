package dev.exactselection;

/**
 * One job of the map tool's interface. {@link ExactSelectionController} owns what the jobs share and calls each one.
 */
abstract class UiPart {
    final ExactSelectionController ui;

    UiPart(ExactSelectionController ui) {
        this.ui = ui;
    }
}

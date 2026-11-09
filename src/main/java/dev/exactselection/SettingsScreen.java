package dev.exactselection;

import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** The settings from the Areas window's gear, opened from Mod Menu: autosave and copy rounding. */
final class SettingsScreen extends Screen {
    private final Screen parent;
    private final AutosaveSettings settings =
            AutosaveSettings.load(FabricLoader.getInstance().getConfigDir());

    SettingsScreen(Screen parent) {
        super(Component.translatable("exactselection.settings_title"));
        this.parent = parent;
    }

    static Component roundingLabel(AutosaveSettings.Rounding rounding) {
        return Component.translatable(
                "exactselection.rounding",
                Component.translatable(
                        "exactselection.rounding_" + rounding.name().toLowerCase(Locale.ROOT)));
    }

    @Override
    protected void init() {
        int x = width / 2 - 100, y = height / 6 + 12;
        Button[] fields = new Button[AutosaveSettings.Field.values().length];
        addRenderableWidget(Button.builder(autosaveLabel(), b -> {
                    settings.setEnabled(!settings.enabled());
                    b.setMessage(autosaveLabel());
                    for (Button field : fields) field.active = settings.enabled();
                })
                .bounds(x, y, 200, 20)
                .tooltip(Tooltip.create(Component.translatable("exactselection.autosave_hint")))
                .build());
        for (AutosaveSettings.Field field : AutosaveSettings.Field.values()) {
            y += 24;
            Button button = addRenderableWidget(Button.builder(fieldLabel(field), b -> {
                        settings.toggle(field);
                        b.setMessage(fieldLabel(field));
                    })
                    .bounds(x + 20, y, 180, 20)
                    .build());
            button.active = settings.enabled();
            fields[field.ordinal()] = button;
        }
        y += 32;
        addRenderableWidget(Button.builder(roundingLabel(settings.rounding()), b -> {
                    settings.cycleRounding();
                    b.setMessage(roundingLabel(settings.rounding()));
                })
                .bounds(x, y, 200, 20)
                .tooltip(Tooltip.create(Component.translatable("exactselection.rounding_tooltip")))
                .build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(x, height - 28, 200, 20)
                .build());
    }

    private Component autosaveLabel() {
        return Component.translatable(
                settings.enabled() ? "exactselection.autosave_on" : "exactselection.autosave_off");
    }

    private Component fieldLabel(AutosaveSettings.Field field) {
        return Component.translatable(
                "exactselection.autosave_field",
                Component.translatable(
                        "exactselection.autosave_field_" + field.name().toLowerCase(Locale.ROOT)),
                Component.translatable(
                        settings.includes(field) ? "exactselection.state_on" : "exactselection.state_off"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 15, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}

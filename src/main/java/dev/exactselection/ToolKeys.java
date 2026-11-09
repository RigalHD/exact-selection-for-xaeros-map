package dev.exactselection;

import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** The tool's keys in the game's Controls screen; they act while the tool is on with the map open. */
public final class ToolKeys implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("xaero_exact_selection", "tool"));
    static final KeyMapping POLYGON = key("polygon", GLFW.GLFW_KEY_O);
    static final KeyMapping RULER = key("ruler", GLFW.GLFW_KEY_R);
    static final KeyMapping AREAS = key("areas", GLFW.GLFW_KEY_J);
    static final KeyMapping UNITS = key("units", GLFW.GLFW_KEY_N);

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("exactselection.key_" + name, code, CATEGORY);
    }

    @Override
    public void onInitializeClient() {
        for (KeyMapping key : List.of(POLYGON, RULER, AREAS, UNITS)) KeyBindingHelper.registerKeyBinding(key);
    }
}

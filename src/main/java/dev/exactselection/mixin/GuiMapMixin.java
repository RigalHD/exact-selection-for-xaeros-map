package dev.exactselection.mixin;

import dev.exactselection.ExactSelectionController;
import dev.exactselection.MapBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.MapProcessor;
import xaero.map.animation.SlowingAnimation;
import xaero.map.gui.GuiMap;
import xaero.map.gui.MapMouseButtonPress;
import xaero.map.gui.MapTileSelection;

@Mixin(value = GuiMap.class, remap = false)
public abstract class GuiMapMixin extends Screen implements MapBridge {
    @Shadow private double cameraX;
    @Shadow private double cameraZ;
    @Shadow private double scale;
    @Shadow private MapProcessor mapProcessor;
    @Shadow private ResourceKey<Level> lastViewedDimensionId;
    @Shadow private String lastViewedMultiworldId;
    @Shadow private MapMouseButtonPress leftMouseButton;
    @Shadow private MapMouseButtonPress rightMouseButton;
    @Shadow private MapTileSelection mapTileSelection;
    @Shadow private SlowingAnimation cameraDestinationAnimX;
    @Shadow private SlowingAnimation cameraDestinationAnimZ;
    @Shadow private int[] cameraDestination;
    @Shadow private int mouseDownPosX;
    @Shadow private int mouseDownPosY;
    @Unique private ExactSelectionController exactselection$controller;
    @Unique private boolean exactselection$zoomAnchorActive;
    @Unique private double exactselection$zoomAnchorX;
    @Unique private double exactselection$zoomAnchorZ;
    @Unique private double exactselection$previousZoomScale;
    @Unique private int exactselection$steadyZoomFrames;

    protected GuiMapMixin(Component title) { super(title); }

    // Both selectors are intentional: Xaero's methods are named in dev and intermediary in-game.
    @Inject(method = {"init", "method_25426"}, at = @At("TAIL"))
    private void exactselection$init(CallbackInfo ci) {
        if (exactselection$controller == null)
            exactselection$controller = new ExactSelectionController((GuiMap) (Object) this, this);
        exactselection$controller.init();
    }

    @Inject(method = {"render", "method_25394"}, at = @At("HEAD"))
    private void exactselection$beforeRender(GuiGraphics g, int x, int y, float delta, CallbackInfo ci) {
        if (exactselection$controller != null) exactselection$controller.beforeRender(x, y);
    }

    @Inject(method = {"render", "method_25394"}, at = {
            @At(value = "INVOKE", target = "Lxaero/lib/client/gui/ScreenBase;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"),
            @At(value = "INVOKE", target = "Lxaero/lib/client/gui/ScreenBase;method_25394(Lnet/minecraft/class_332;IIF)V")
    }, require = 1, allow = 1)
    private void exactselection$mapOverlays(GuiGraphics g, int x, int y, float delta, CallbackInfo ci) {
        if (exactselection$controller != null) exactselection$controller.renderMapOverlays(g, x, y);
    }

    @Inject(method = {"render", "method_25394"}, at = @At("TAIL"))
    private void exactselection$render(GuiGraphics g, int x, int y, float delta, CallbackInfo ci) {
        if (exactselection$controller != null) exactselection$controller.render(g, x, y, delta);
    }

    @Inject(method = {"mouseClicked", "method_25402"}, at = @At("HEAD"), cancellable = true)
    private void exactselection$click(MouseButtonEvent event, boolean twice, CallbackInfoReturnable<Boolean> cir) {
        if (exactselection$controller != null && exactselection$controller.mouseClicked(event, twice))
            cir.setReturnValue(true);
    }

    @Inject(method = {"mouseReleased", "method_25406"}, at = @At("HEAD"), cancellable = true)
    private void exactselection$release(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (exactselection$controller != null && exactselection$controller.mouseReleased(event))
            cir.setReturnValue(true);
    }

    @Inject(method = {"mouseScrolled", "method_25401"}, at = @At("HEAD"), cancellable = true)
    private void exactselection$scroll(double x, double y, double horizontal, double vertical,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (exactselection$controller != null && exactselection$controller.mouseScrolled(x, y, vertical))
            cir.setReturnValue(true);
    }

    @Inject(method = {"keyPressed", "method_25404"}, at = @At("HEAD"), cancellable = true)
    private void exactselection$key(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (exactselection$controller != null && exactselection$controller.keyPressed(event))
            cir.setReturnValue(true);
    }

    @Inject(method = {"keyReleased", "method_16803"}, at = @At("HEAD"))
    private void exactselection$keyReleased(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (exactselection$controller != null) exactselection$controller.keyReleased(event);
    }

    @Inject(method = {"charTyped", "method_25400"}, at = @At("HEAD"), cancellable = true)
    private void exactselection$character(CharacterEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (exactselection$controller != null && exactselection$controller.charTyped(event))
            cir.setReturnValue(true);
    }

    @Inject(method = {"onInputPress", "onInputRelease"}, at = @At("HEAD"), cancellable = true)
    private void exactselection$nativeShortcut(CallbackInfoReturnable<Boolean> cir) {
        if (exactselection$controller != null && exactselection$controller.enabled()) cir.setReturnValue(true);
    }

    @Inject(method = "changeZoom", at = @At("HEAD"), cancellable = true)
    private void exactselection$zoom(double factor, int zoomMethod, CallbackInfo ci) {
        if (exactselection$controller != null && exactselection$controller.confirmationOpen()) {
            exactselection$zoomAnchorActive = false;
            ci.cancel();
            return;
        }
        if (exactselection$controller != null && zoomMethod == 1
                && exactselection$controller.coordinateFieldFocused()) {
            exactselection$zoomAnchorActive = false;
            ci.cancel();
            return;
        }
        if (exactselection$controller == null || !exactselection$controller.enabled() || zoomMethod == 2 || scale <= 0) {
            exactselection$zoomAnchorActive = false;
            return;
        }
        double mouseOffsetX = exactselection$mouseOffsetX();
        double mouseOffsetZ = exactselection$mouseOffsetZ();
        exactselection$zoomAnchorX = cameraX + mouseOffsetX / scale;
        exactselection$zoomAnchorZ = cameraZ + mouseOffsetZ / scale;
        exactselection$previousZoomScale = scale;
        exactselection$steadyZoomFrames = 0;
        exactselection$zoomAnchorActive = true;
    }

    @Inject(method = {"render", "method_25394"}, at = @At(value = "FIELD",
            target = "Lxaero/map/file/MapSaveLoad;mainTextureLevel:I", opcode = Opcodes.PUTFIELD))
    private void exactselection$keepZoomCursorAnchor(GuiGraphics g, int x, int y, float delta, CallbackInfo ci) {
        if (!exactselection$zoomAnchorActive) return;
        if (exactselection$controller == null || !exactselection$controller.enabled() || scale <= 0) {
            exactselection$zoomAnchorActive = false;
            return;
        }
        double mouseOffsetX = exactselection$mouseOffsetX();
        double mouseOffsetZ = exactselection$mouseOffsetZ();
        cameraX = exactselection$zoomAnchorX - mouseOffsetX / scale;
        cameraZ = exactselection$zoomAnchorZ - mouseOffsetZ / scale;
        if (Math.abs(scale - exactselection$previousZoomScale) < 1.0E-9) exactselection$steadyZoomFrames++;
        else exactselection$steadyZoomFrames = 0;
        exactselection$previousZoomScale = scale;
        if (exactselection$steadyZoomFrames >= 2) exactselection$zoomAnchorActive = false;
    }

    // Same integer pixel math as Xaero's cursor projection, so odd window sizes do not drift by half a pixel.
    @Unique private static int exactselection$mouseOffsetX() {
        Minecraft mc = Minecraft.getInstance();
        return (int) mc.mouseHandler.xpos() - mc.getWindow().getWidth() / 2;
    }

    @Unique private static int exactselection$mouseOffsetZ() {
        Minecraft mc = Minecraft.getInstance();
        return (int) mc.mouseHandler.ypos() - mc.getWindow().getHeight() / 2;
    }

    @Inject(method = "mapClicked", at = @At("HEAD"), cancellable = true)
    private void exactselection$nativeClick(CallbackInfo ci) {
        if (exactselection$controller != null && exactselection$controller.enabled()) ci.cancel();
    }

    @Inject(method = {"removed", "method_25432"}, at = @At("HEAD"))
    private void exactselection$removed(CallbackInfo ci) {
        exactselection$zoomAnchorActive = false;
        if (exactselection$controller != null) exactselection$controller.disable();
    }

    @Override public Frame exactselection$frame() {
        var world = mapProcessor.getMapWorld();
        // Xaero leaves the current dimension unset while a world or dimension is still switching.
        var dimension = world == null ? null : world.getCurrentDimension();
        if (dimension == null || !mapProcessor.isMapWorldUsable() || mapProcessor.isRenderingPaused()
                || lastViewedDimensionId == null || scale <= 0
                || !lastViewedDimensionId.equals(world.getFutureDimensionId())
                || !java.util.Objects.equals(lastViewedMultiworldId, dimension.getCurrentMultiworld()))
            return null;
        var window = Minecraft.getInstance().getWindow();
        return new Frame(new Context(world, mapProcessor.getCurrentWorldId(), lastViewedDimensionId,
                lastViewedMultiworldId), cameraX, cameraZ, scale, window.getGuiScale(),
                window.getWidth(), window.getHeight());
    }

    @Override public MapProcessor exactselection$processor() { return mapProcessor; }

    @Override public void exactselection$pan(double dx, double dy) {
        Frame frame = exactselection$frame();
        if (frame == null) return;
        cameraDestination = null;
        cameraDestinationAnimX = cameraDestinationAnimZ = null;
        cameraX -= dx * frame.guiScale() / scale;
        cameraZ -= dy * frame.guiScale() / scale;
    }

    @Override public void exactselection$clearNativeInput() {
        leftMouseButton.isDown = rightMouseButton.isDown = false;
        leftMouseButton.clicked = rightMouseButton.clicked = false;
        mouseDownPosX = mouseDownPosY = -1;
        mapTileSelection = null;
        cameraDestination = null;
        cameraDestinationAnimX = cameraDestinationAnimZ = null;
        GuiMap map = (GuiMap) (Object) this;
        map.closeRightClick();
        map.waypointMenu = false;
        map.playersMenu = false;
    }
}

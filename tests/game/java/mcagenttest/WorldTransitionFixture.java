package mcagenttest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Prepare disposable saves and observe native controls without invoking their callbacks. */
final class WorldTransitionFixture {
    static void apply(Minecraft mc, String command, String value) {
        var screen = mc.gui.screen();
        String result = switch (command) {
            case "toggle-sneak" -> {
                if (value.equals("enable")) mc.options.toggleCrouch().set(true);
                yield Boolean.toString(mc.options.keyShift.isDown());
            }
            case "button" -> screen.children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast)
                .filter(widget -> widget.visible && widget.isActive()
                    && widget.getMessage().getContents() instanceof TranslatableContents text
                    && text.getKey().equals(value))
                .findFirst()
                .map(widget -> point(mc, widget.getX() + widget.getWidth() / 2.0,
                    widget.getY() + widget.getHeight() / 2.0))
                .orElse("missing");
            case "row" -> screen.children().stream().filter(WorldSelectionList.class::isInstance)
                .map(WorldSelectionList.class::cast).findFirst().map(list -> {
                    for (int i = 0; i < list.children().size(); i++) {
                        if (list.children().get(i) instanceof WorldSelectionList.WorldListEntry row
                                && row.getLevelName().equals(value))
                            return point(mc, list.getRowLeft() + 50, list.getRowTop(i) + 9);
                    }
                    return "missing";
                }).orElse("missing");
            case "flat" -> {
                var state = ((CreateWorldScreen) screen).getUiState();
                state.setWorldType(state.getNormalPresetList().stream()
                    .filter(entry -> entry.preset().is(WorldPresets.FLAT)).findFirst().orElseThrow());
                state.setSeed("123");
                state.setGenerateStructures(false);
                yield state.getName();
            }
            case "block-create" -> {
                var directory = ((CreateWorldScreen) screen).getUiState().getTargetFolder();
                try {
                    Files.createFile(Path.of("saves").resolve(directory));
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
                yield directory;
            }
            case "require-backup" -> {
                editSaveVersion(value);
                yield "ready";
            }
            default -> throw new IllegalArgumentException("unknown world transition fixture " + command);
        };
        try {
            Files.writeString(Path.of("world-transition-state"), result);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static String point(Minecraft mc, double x, double y) {
        var target = mc.gameRenderer.mainRenderTarget();
        var window = mc.getWindow();
        return x * target.width / window.getGuiScaledWidth() + " " + y * target.height / window.getGuiScaledHeight();
    }

    private static void editSaveVersion(String save) {
        // Change only the save's summary version. Its real data format remains unchanged.
        var path = Path.of("saves").resolve(save).resolve("level.dat");
        try {
            var tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            var version = tag.getCompound("Data").orElseThrow().getCompound("Version").orElseThrow();
            version.putInt("Id", SharedConstants.getCurrentVersion().dataVersion().version() + 1);
            NbtIo.writeCompressed(tag, path);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}

package com.mahdi.hotbartochest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side automation for applying enchanted books through a real Anvil GUI.
 * The server remains authoritative: all inventory changes happen through normal
 * ScreenHandler slot clicks.
 */
public class AutoAnvilClient implements ClientModInitializer {
    private static final String MOD_ID = "auto_anvil";
    private static final int ACTION_DELAY_TICKS = 6; // 300 ms
    private static final int OPEN_DELAY_TICKS = 8;
    private static final int MESSAGE_TICKS = 50;

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of(MOD_ID, "controls"));

    private static KeyBinding toggleKey;
    private static KeyBinding targetMenuKey;

    private static boolean enabled = true;
    private static Identifier targetItemId;
    private static int delay;
    private static boolean waitingForInputResult;
    private static boolean waitingForOutput;
    private static boolean waitingForClear;
    private static ItemStack currentTarget = ItemStack.EMPTY;
    private static ItemStack currentBook = ItemStack.EMPTY;
    private static AnvilScreenHandler lastHandler;
    private static int statusTicks;

    private static final Path CONFIG_FILE = Path.of("config", "auto_anvil.properties");

    @Override
    public void onInitializeClient() {
        loadConfig();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.auto_anvil.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                CATEGORY
        ));

        targetMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.auto_anvil.target_menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(AutoAnvilClient::tick);
    }

    private static void tick(MinecraftClient client) {
        if (statusTicks > 0) {
            statusTicks--;
            if (statusTicks == 0 && client.player != null) {
                client.player.sendMessage(Text.empty(), true);
            }
        }

        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            resetOperation();
            saveConfig();
            message(client, enabled ? "Auto Anvil: ON" : "Auto Anvil: OFF", enabled ? 0x55FF55 : 0xFF5555);
        }

        while (targetMenuKey.wasPressed()) {
            if (client.player != null && !(client.currentScreen instanceof TargetSelectionScreen)) {
                client.setScreen(new TargetSelectionScreen(client.currentScreen));
            }
        }

        if (!enabled || client.player == null || client.interactionManager == null) {
            return;
        }

        if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.AnvilScreen screen)) {
            resetOperation();
            return;
        }

        AnvilScreenHandler handler = screen.getScreenHandler();
        if (handler != lastHandler) {
            lastHandler = handler;
            delay = OPEN_DELAY_TICKS;
            if (!waitingForInputResult && !waitingForOutput) {
                currentTarget = ItemStack.EMPTY;
                currentBook = ItemStack.EMPTY;
            }
        }

        if (delay > 0) {
            delay--;
            return;
        }

        if (waitingForOutput) {
            takeOutput(client, handler);
            return;
        }

        if (waitingForClear) {
            if (!handler.getSlot(AnvilScreenHandler.INPUT_1_ID).getStack().isEmpty()
                    || !handler.getSlot(AnvilScreenHandler.INPUT_2_ID).getStack().isEmpty()
                    || !handler.getSlot(AnvilScreenHandler.OUTPUT_ID).getStack().isEmpty()) {
                // Give the server time to acknowledge the output click.
                delay = 1;
                return;
            }
            waitingForClear = false;
            delay = ACTION_DELAY_TICKS;
            return;
        }

        if (waitingForInputResult) {
            prepareOrCheckResult(client, handler);
            return;
        }

        startNextOperation(client, handler);
    }

    private static void startNextOperation(MinecraftClient client, AnvilScreenHandler handler) {
        if (targetItemId == null) {
            message(client, "Auto Anvil: No target item selected", 0xFF55FF);
            return;
        }

        if (!handler.getSlot(AnvilScreenHandler.INPUT_1_ID).getStack().isEmpty()
                || !handler.getSlot(AnvilScreenHandler.INPUT_2_ID).getStack().isEmpty()) {
            stopWithError(client, "Auto Anvil: Anvil inputs are not empty");
            return;
        }

        Item targetItem = Registries.ITEM.get(targetItemId);
        if (targetItem == null || targetItem == Items.AIR) {
            stopWithError(client, "Auto Anvil: Target item no longer exists");
            return;
        }

        PlayerInventory inventory = client.player.getInventory();
        int targetInvSlot = findTargetSlot(inventory, targetItem);
        if (targetInvSlot < 0) {
            message(client, "Auto Anvil: No target items left", 0x55FF55);
            return;
        }

        ItemStack target = inventory.getStack(targetInvSlot);
        int bookInvSlot = findUsefulBook(inventory, target);
        if (bookInvSlot < 0) {
            message(client, "Auto Anvil: No useful enchanted book for this item", 0xFFAA00);
            return;
        }

        currentTarget = target.copy();
        currentBook = inventory.getStack(bookInvSlot).copy();

        // Shift-click the target into Anvil input 1.
        client.interactionManager.clickSlot(
                handler.syncId,
                playerInventoryToHandlerSlot(targetInvSlot),
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );

        waitingForInputResult = true;
        delay = ACTION_DELAY_TICKS;
    }

    private static void prepareOrCheckResult(MinecraftClient client, AnvilScreenHandler handler) {
        ItemStack input1 = handler.getSlot(AnvilScreenHandler.INPUT_1_ID).getStack();
        if (input1.isEmpty()) {
            // Server has not synced the first click yet.
            delay = 1;
            return;
        }

        PlayerInventory inventory = client.player.getInventory();
        int bookSlot = findExactBook(inventory, currentBook);
        if (bookSlot < 0) {
            stopWithError(client, "Auto Anvil: Enchanted book disappeared");
            return;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                playerInventoryToHandlerSlot(bookSlot),
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );

        waitingForInputResult = false;
        waitingForOutput = true;
        delay = ACTION_DELAY_TICKS;
    }

    private static void takeOutput(MinecraftClient client, AnvilScreenHandler handler) {
        ItemStack output = handler.getSlot(AnvilScreenHandler.OUTPUT_ID).getStack();
        if (output.isEmpty()) {
            int cost = handler.getLevelCost();
            if (cost > 39) {
                stopWithError(client, "Auto Anvil: Too Expensive!");
            } else if (cost > client.player.experienceLevel) {
                stopWithError(client, "Auto Anvil: Not Enough XP");
            } else {
                delay = 1;
            }
            return;
        }

        int cost = handler.getLevelCost();
        if (cost > 39) {
            stopWithError(client, "Auto Anvil: Too Expensive!");
            return;
        }
        if (cost > client.player.experienceLevel) {
            stopWithError(client, "Auto Anvil: Not Enough XP");
            return;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                AnvilScreenHandler.OUTPUT_ID,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );

        delay = ACTION_DELAY_TICKS;
        waitingForOutput = false;
        waitingForClear = true;

        // Wait for the server to clear the anvil before starting the next item.
        message(client, "Auto Anvil: Enchanted", 0x55FF55);
    }

    private static int findTargetSlot(PlayerInventory inventory, Item item) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    private static int findExactBook(PlayerInventory inventory, ItemStack wanted) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.getItem() == Items.ENCHANTED_BOOK
                    && ItemStack.areItemsAndComponentsEqual(stack, wanted)) {
                return i;
            }
        }
        return -1;
    }

    private static int findUsefulBook(PlayerInventory inventory, ItemStack target) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack book = inventory.getStack(i);
            if (book.isEmpty() || book.getItem() != Items.ENCHANTED_BOOK) {
                continue;
            }

            if (hasUsefulEnchantment(target, book)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasUsefulEnchantment(ItemStack target, ItemStack book) {
        var bookEnchants = EnchantmentHelper.getEnchantments(book);
        var targetEnchants = EnchantmentHelper.getEnchantments(target);

        for (RegistryEntry<Enchantment> candidate : bookEnchants.getEnchantments()) {
            int bookLevel = bookEnchants.getLevel(candidate);
            int existingLevel = targetEnchants.getLevel(candidate);

            if (bookLevel <= existingLevel) {
                continue;
            }

            Enchantment enchantment = candidate.value();
            if (!enchantment.isSupportedItem(target)) {
                continue;
            }

            boolean compatible = true;
            for (RegistryEntry<Enchantment> existing : targetEnchants.getEnchantments()) {
                if (!existing.equals(candidate) && !Enchantment.canBeCombined(candidate, existing)) {
                    compatible = false;
                    break;
                }
            }

            if (compatible) {
                return true;
            }
        }
        return false;
    }

    private static int playerInventoryToHandlerSlot(int inventorySlot) {
        // Forging handlers use 3..38 for PlayerInventory's 36 slots.
        return 3 + inventorySlot;
    }

    private static void stopWithError(MinecraftClient client, String text) {
        enabled = false;
        resetOperation();
        saveConfig();
        message(client, text, 0xFF5555);
    }

    private static void resetOperation() {
        delay = 0;
        waitingForInputResult = false;
        waitingForOutput = false;
        waitingForClear = false;
        currentTarget = ItemStack.EMPTY;
        currentBook = ItemStack.EMPTY;
        lastHandler = null;
    }

    private static void message(MinecraftClient client, String text, int color) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(text).styled(style -> style.withColor(color)), true);
            statusTicks = MESSAGE_TICKS;
        }
    }

    private static void loadConfig() {
        try {
            if (!Files.exists(CONFIG_FILE)) {
                return;
            }
            List<String> lines = Files.readAllLines(CONFIG_FILE);
            for (String line : lines) {
                if (line.startsWith("enabled=")) {
                    enabled = Boolean.parseBoolean(line.substring("enabled=".length()));
                } else if (line.startsWith("target=")) {
                    Identifier id = Identifier.tryParse(line.substring("target=".length()));
                    if (id != null) {
                        targetItemId = id;
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void saveConfig() {
        try {
            Path parent = CONFIG_FILE.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<String> lines = new ArrayList<>();
            lines.add("enabled=" + enabled);
            lines.add("target=" + (targetItemId == null ? "" : targetItemId));
            Files.write(CONFIG_FILE, lines);
        } catch (IOException ignored) {
        }
    }

    private static void selectTarget(MinecraftClient client, Item item) {
        targetItemId = Registries.ITEM.getId(item);
        resetOperation();
        saveConfig();
        message(client, "Auto Anvil target: " + item.getName(item.getDefaultStack()).getString(), 0xFF55FF);
    }

    private static class TargetSelectionScreen extends Screen {
        private final Screen parent;
        private final List<Item> items = new ArrayList<>();
        private final Map<Item, ItemStack> examples = new LinkedHashMap<>();

        protected TargetSelectionScreen(Screen parent) {
            super(Text.literal("Auto Anvil - Select Target Item"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            super.init();
            items.clear();
            examples.clear();

            if (client == null || client.player == null) {
                return;
            }

            for (int i = 0; i < client.player.getInventory().size(); i++) {
                ItemStack stack = client.player.getInventory().getStack(i);
                if (!stack.isEmpty() && stack.getItem() != Items.AIR && !examples.containsKey(stack.getItem())) {
                    examples.put(stack.getItem(), stack.copy());
                    items.add(stack.getItem());
                }
            }

            int columns = 4;
            int buttonWidth = 150;
            int buttonHeight = 28;
            int gap = 8;
            int totalWidth = columns * buttonWidth + (columns - 1) * gap;
            int startX = (width - totalWidth) / 2;
            int startY = 48;

            for (int i = 0; i < items.size(); i++) {
                Item item = items.get(i);
                int col = i % columns;
                int row = i / columns;
                int x = startX + col * (buttonWidth + gap);
                int y = startY + row * (buttonHeight + gap);

                addDrawableChild(ButtonWidget.builder(Text.literal(item.getName(item.getDefaultStack()).getString()),
                                button -> {
                                    selectTarget(client, item);
                                    client.setScreen(parent);
                                })
                        .dimensions(x, y, buttonWidth, buttonHeight)
                        .build());
            }

            addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> client.setScreen(parent))
                    .dimensions((width - 150) / 2, height - 36, 150, 24)
                    .build());
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            renderBackground(context, mouseX, mouseY, delta);
            super.render(context, mouseX, mouseY, delta);

            context.drawCenteredTextWithShadow(textRenderer,
                    "Select the item type to automate",
                    width / 2, 18, 0xFFFFFF);

            context.drawCenteredTextWithShadow(textRenderer,
                    "Only this item type will be processed. Enchanted books are chosen automatically.",
                    width / 2, 32, 0xAAAAAA);
        }

        @Override
        public boolean shouldPause() {
            return false;
        }
    }
}

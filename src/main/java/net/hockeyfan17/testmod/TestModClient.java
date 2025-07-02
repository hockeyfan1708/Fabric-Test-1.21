package net.hockeyfan17.testmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import java.util.HashMap;
import java.util.Map;

public class TestModClient implements ClientModInitializer {

    private static final Map<BlockPos, BlockState> ghostBlocks = new HashMap<>();
    private static boolean placingGhostBlocks = false;
    private static boolean editModeEnabled = false;
    private long lastLeftClickTime = 0;
    private long lastRightClickTime = 0;
    private static final long CLICK_DELAY_MS = 200; // 200ms between clicks
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("TestMod");


    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register(this::registerCommands);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!placingGhostBlocks || client.player == null || client.world == null) return;

            if (editModeEnabled) {
                long currentTime = System.currentTimeMillis();

                if (client.mouse.wasLeftButtonClicked() && (currentTime - lastLeftClickTime > CLICK_DELAY_MS)) {
                    lastLeftClickTime = currentTime;
                    handleLeftClick(client);
                }

                if (client.mouse.wasRightButtonClicked() && (currentTime - lastRightClickTime > CLICK_DELAY_MS)) {
                    lastRightClickTime = currentTime;
                    handleRightClick(client);
                }
            }

            for (Map.Entry<BlockPos, BlockState> entry : ghostBlocks.entrySet()) {
                if (placingGhostBlocks) {
                    client.world.setBlockState(entry.getKey(), entry.getValue(), 3);
                }
            }

        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            loadGhostBlocks(client);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            saveGhostBlocks(client);
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (editModeEnabled && placingGhostBlocks && world.isClient) {
                return ActionResult.FAIL; // Cancel right-click interaction
            }
            return ActionResult.PASS;
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (editModeEnabled && placingGhostBlocks && world.isClient) {
                return ActionResult.FAIL; // Cancel left-click block breaking
            }
            return ActionResult.PASS;
        });
    }

    private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(ClientCommandManager.literal("toggleghostblocks")
                .executes(context -> {
                    placingGhostBlocks = !placingGhostBlocks;

                    var client = MinecraftClient.getInstance();
                    var player = client.player;
                    var world = client.world;

                    if (!placingGhostBlocks && world != null && player != null) {
                        int restored = 0;



                        for (Map.Entry<BlockPos, BlockState> entry : ghostBlocks.entrySet()) {
                            BlockPos pos = entry.getKey();
                            BlockState ghostState = entry.getValue();
                            BlockState actualState = world.getBlockState(pos);

                            // Force a refresh by triggering two changes (client will re-request the real state from server)
                            world.setBlockState(pos, Blocks.BARRIER.getDefaultState(), 3); // temporary to force a visual update
                            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);     // fake change to nudge a sync
                            restored++;
                        }

                        player.sendMessage(Text.literal("Restored " + restored + " ghost block(s) to server state.")
                                .formatted(Formatting.YELLOW), false);
                    }


                    if (player != null) {
                        player.sendMessage(Text.literal("Ghost block placement " + (placingGhostBlocks ? "enabled" : "disabled") + ".")
                                .formatted(placingGhostBlocks ? Formatting.GREEN : Formatting.RED), false);
                    }

                    return 1;
                }));

        dispatcher.register(ClientCommandManager.literal("addghostblock")
                .then(ClientCommandManager.argument("block", StringArgumentType.word())
                        .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                        .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                                .executes(this::addGhostBlockCommand))))));

        dispatcher.register(ClientCommandManager.literal("editmode")
                .executes(context -> {
                    editModeEnabled = !editModeEnabled;
                    var player = MinecraftClient.getInstance().player;
                    if (player != null) {
                        player.sendMessage(
                                Text.literal("Edit mode " + (editModeEnabled ? "enabled" : "disabled")).formatted(editModeEnabled ? Formatting.GREEN : Formatting.RED),
                                false);
                    }
                    return 1;
                }));
        dispatcher.register(ClientCommandManager.literal("ghostblocksaves")
                .executes(context -> {
                    saveGhostBlocks(MinecraftClient.getInstance());
                    context.getSource().sendFeedback(Text.literal("Ghost blocks saved.").formatted(Formatting.YELLOW));
                    return 1;
                }));

        dispatcher.register(ClientCommandManager.literal("ghostblockloads")
                .executes(context -> {
                    loadGhostBlocks(MinecraftClient.getInstance());
                    context.getSource().sendFeedback(Text.literal("Ghost blocks loaded.").formatted(Formatting.YELLOW));
                    return 1;
                }));
    }

    private int addGhostBlockCommand(CommandContext<FabricClientCommandSource> context) {
        String blockName = StringArgumentType.getString(context, "block");
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");

        BlockPos pos = new BlockPos(x, y, z);

        Identifier blockId = Identifier.tryParse(blockName.contains(":") ? blockName : "minecraft:" + blockName);
        if (blockId == null || !Registries.BLOCK.containsId(blockId)) {
            context.getSource().sendFeedback(Text.literal("Invalid block ID: " + blockName).formatted(Formatting.RED));
            return 0;
        }

        Block block = Registries.BLOCK.get(blockId);
        BlockState state = block.getDefaultState();
        ghostBlocks.put(pos, state);

        context.getSource().sendFeedback(Text.literal("Added ghost block: " + blockName + " at " + pos.toShortString()));
        return 1;
    }


    private void handleLeftClick(MinecraftClient client) {
        var player = client.player;
        var world = client.world;
        if (player == null || world == null) return;
        var hitResult = player.raycast(10.0, 0.0f, false);
        if (hitResult == null || hitResult.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK) {
            player.sendMessage(Text.literal("No block in sight within 10 blocks.").formatted(Formatting.RED), false);
            return;
        }

        var blockHit = (net.minecraft.util.hit.BlockHitResult) hitResult;
        BlockPos targetPos = blockHit.getBlockPos();

        ghostBlocks.put(targetPos, Blocks.AIR.getDefaultState());
        world.setBlockState(targetPos, Blocks.AIR.getDefaultState(), 3);
    }

    private void handleRightClick(MinecraftClient client) {
        var player = client.player;
        var world = client.world;
        if (player == null || world == null) return;

        var hitResult = player.raycast(10.0, 0.0f, false);
        if (hitResult == null || hitResult.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK) {
            player.sendMessage(Text.literal("No block in sight within 10 blocks.").formatted(Formatting.RED), false);
            return;
        }

        var blockHit = (net.minecraft.util.hit.BlockHitResult) hitResult;
        BlockPos targetPos = blockHit.getBlockPos();
        BlockPos placePos = targetPos.offset(blockHit.getSide());

        var stack = player.getMainHandStack();

        // If hand is empty, check for ghost air and restore original block
        if (stack.isEmpty()) {
            if (ghostBlocks.containsKey(placePos) && ghostBlocks.get(placePos).isAir()) {
                ghostBlocks.remove(placePos);
                world.setBlockState(placePos, world.getBlockState(placePos), 3); // force refresh from server
            } else return;
        }

        // If holding a block, place it as a ghost block
        if (stack.getItem() instanceof net.minecraft.item.BlockItem blockItem) {
            Block block = blockItem.getBlock();
            BlockState state = block.getDefaultState();

            ghostBlocks.put(placePos, state);
            world.setBlockState(placePos, state, 3);
        } else {
            player.sendMessage(Text.literal("Hold a block in your main hand to place ghost blocks.").formatted(Formatting.RED), false);
        }
    }

    // ================================================================

    private Path getWorldConfigPath(MinecraftClient client) {
        String worldName = client.getServer() != null ? client.getServer().getSaveProperties().getLevelName() : "default";
        return CONFIG_DIR.resolve(worldName).resolve("ghostblocks.json");
    }

    private void saveGhostBlocks(MinecraftClient client) {
        try {
            Path path = getWorldConfigPath(client);
            Files.createDirectories(path.getParent());

            Map<String, String> serialized = new HashMap<>();
            for (Map.Entry<BlockPos, BlockState> entry : ghostBlocks.entrySet()) {
                String key = serializeBlockPos(entry.getKey());
                String value = Registries.BLOCK.getId(entry.getValue().getBlock()).toString();

                // Save full blockstate with properties
                value += "#" + entry.getValue().getProperties().toString();

                serialized.put(key, value);
            }

            Files.write(path, GSON.toJson(serialized).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadGhostBlocks(MinecraftClient client) {
        try {
            Path path = getWorldConfigPath(client);
            if (!Files.exists(path)) return;

            String json = Files.readString(path);
            Map<String, String> serialized = GSON.fromJson(json, new TypeToken<Map<String, String>>() {
            }.getType());

            ghostBlocks.clear();
            for (Map.Entry<String, String> entry : serialized.entrySet()) {
                BlockPos pos = deserializeBlockPos(entry.getKey());

                String[] split = entry.getValue().split("#", 2);
                Identifier id = Identifier.tryParse(split[0]);
                if (!Registries.BLOCK.containsId(id)) continue;

                Block block = Registries.BLOCK.get(id);
                BlockState state = block.getDefaultState();

                // Optionally parse state string into actual state (if needed)
                // Placeholder: Apply full state parsing here if desired

                ghostBlocks.put(pos, state);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String serializeBlockPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private BlockPos deserializeBlockPos(String str) {
        String[] parts = str.split(",");
        return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }
}
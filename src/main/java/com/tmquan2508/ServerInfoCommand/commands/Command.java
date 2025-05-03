package com.tmquan2508.ServerInfoCommand.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.tmquan2508.ServerInfoCommand.ServerInfoCommandMod;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.world.Difficulty;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class Command {

    private static final Set<String> ANTICHEAT_LIST = Set.of(
            "nocheatplus", "negativity", "warden", "horizon", "illegalstack",
            "coreprotect", "exploitsx", "vulcan", "abc", "spartan", "kauri",
            "anticheatreloaded", "witherac", "godseye", "matrix", "wraith",
            "antixrayheuristics", "grimac"
    );
    private static final Set<String> VERSION_ALIASES = Set.of(
            "version", "ver", "about", "bukkit:version", "bukkit:ver", "bukkit:about"
    );

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<FabricClientCommandSource> serverCommand = ClientCommandManager.literal("server")
            .executes(ctx -> runServerInfo(ctx.getSource()));

        serverCommand.then(ClientCommandManager.literal("info")
            .executes(ctx -> runServerInfo(ctx.getSource())));

        serverCommand.then(ClientCommandManager.literal("tps")
            .executes(ctx -> runTpsInfo(ctx.getSource())));

        serverCommand.then(ClientCommandManager.literal("plugins")
            .executes(ctx -> runPluginInfo(ctx.getSource())));

        dispatcher.register(serverCommand);
    }

    private static int runServerInfo(FabricClientCommandSource source) {
        MinecraftClient mc = source.getClient();
        ClientPlayNetworkHandler networkHandler = mc.getNetworkHandler();

        if (mc.isIntegratedServerRunning()) {
            printSinglePlayerInfo(source, mc);
        } else if (networkHandler != null) {
            printMultiPlayerInfo(source, mc, networkHandler);
        } else {
            sendFeedback(source, Text.literal("Not connected to a server.").formatted(Formatting.RED));
            return 0;
        }
        return 1;
    }

    private static int runTpsInfo(FabricClientCommandSource source) {
        double tps = ServerInfoCommandMod.getTps();
        Formatting color;

        if (tps >= 19.5) color = Formatting.GREEN;
        else if (tps > 16.0) color = Formatting.YELLOW;
        else if (tps > 12.0) color = Formatting.GOLD;
        else color = Formatting.RED;

        sendFeedback(source, Text.literal("Server TPS: ").formatted(Formatting.GRAY)
            .append(Text.literal(String.format("%.2f", tps)).formatted(color))
            .append(Text.literal(" (Client-side estimate)").formatted(Formatting.DARK_GRAY)));
        return 1;
    }

    private static int runPluginInfo(FabricClientCommandSource source) {
        ClientPlayNetworkHandler networkHandler = source.getClient().getNetworkHandler();
        if (networkHandler == null) {
            sendFeedback(source, Text.literal("Not connected to a server.").formatted(Formatting.RED));
            return 0;
        }

        Consumer<Text> feedbackSender = msg -> sendFeedback(source, msg);

        CompletableFuture<List<String>> futureCompletions = ServerInfoCommandMod.requestPluginsFromServer(networkHandler);

        futureCompletions.whenComplete((result, throwable) -> {
            source.getClient().execute(() -> {
                if (throwable != null) {
                    handlePluginRequestError(throwable, feedbackSender);
                }
                printDetectedPluginInfo(feedbackSender);
            });
        });

        if (!futureCompletions.isDone() && !futureCompletions.isCompletedExceptionally()) {
            sendFeedback(source, Text.literal("Requesting plugin info from server...").formatted(Formatting.YELLOW));
        }

        return 1;
    }

    private static void printSinglePlayerInfo(FabricClientCommandSource source, MinecraftClient mc) {
        IntegratedServer integratedServer = mc.getServer();
        sendFeedback(source, Text.literal("Server Type: ").formatted(Formatting.GRAY).append(Text.literal("Singleplayer").formatted(Formatting.AQUA)));
        if (integratedServer != null) {
             sendFeedback(source, Text.literal("Version: ").formatted(Formatting.GRAY).append(Text.literal(integratedServer.getVersion()).formatted(Formatting.YELLOW)));
        }
         sendFeedback(source, Text.literal("Difficulty: ").formatted(Formatting.GRAY).append(formatDifficulty(source)));
         sendFeedback(source, Text.literal("Day: ").formatted(Formatting.GRAY).append(Text.literal(String.valueOf(getDay(source))).formatted(Formatting.YELLOW)));
         sendFeedback(source, Text.literal("Permission Level: ").formatted(Formatting.GRAY).append(formatPermissions(source)));
    }

    private static void printMultiPlayerInfo(FabricClientCommandSource source, MinecraftClient mc, ClientPlayNetworkHandler networkHandler) {
        ServerInfo serverInfo = mc.getCurrentServerEntry();
        if (serverInfo == null) {
            ServerAddress serverAddress = ServerAddress.parse(networkHandler.getConnection().getAddress().toString());
            String displayAddress = serverAddress.getAddress() + ":" + serverAddress.getPort();
            String resolvedIp = resolveAddress(serverAddress.getAddress());
            MutableText ipText = createClickableIpText(displayAddress, resolvedIp, serverAddress.getPort());

            sendFeedback(source, Text.literal("IP: ").formatted(Formatting.GRAY).append(ipText));
        } else {
            String displayAddress = serverInfo.address;
            ServerAddress parsed = ServerAddress.parse(displayAddress);
            String resolvedIp = resolveAddress(parsed.getAddress());
            MutableText ipText = createClickableIpText(displayAddress, resolvedIp, parsed.getPort());
            sendFeedback(source, Text.literal("IP: ").formatted(Formatting.GRAY).append(ipText));
            Text motdText = Objects.requireNonNullElse(serverInfo.label, Text.literal("N/A").formatted(Formatting.YELLOW));
            sendFeedback(source, Text.literal("MOTD: ").formatted(Formatting.GRAY).append(motdText));
            // Sửa lỗi định dạng tại đây: chỉ định dạng phần version màu vàng
            sendFeedback(source, Text.literal("Version: ").formatted(Formatting.GRAY).append(serverInfo.version.copy().formatted(Formatting.YELLOW)));
            sendFeedback(source, Text.literal("Protocol: ").formatted(Formatting.GRAY).append(Text.literal(String.valueOf(serverInfo.protocolVersion)).formatted(Formatting.YELLOW)));
        }

        String brand = networkHandler.getBrand();
        sendFeedback(source, Text.literal("Type: ").formatted(Formatting.GRAY).append(Text.literal(Objects.requireNonNullElse(brand, "unknown")).formatted(brand == null ? Formatting.YELLOW : Formatting.AQUA)));

        sendFeedback(source, Text.literal("Difficulty: ").formatted(Formatting.GRAY).append(formatDifficulty(source)));
        sendFeedback(source, Text.literal("Day: ").formatted(Formatting.GRAY).append(Text.literal(String.valueOf(getDay(source))).formatted(Formatting.YELLOW)));
        sendFeedback(source, Text.literal("Permission Level: ").formatted(Formatting.GRAY).append(formatPermissions(source)));
    }

    public static void printDetectedPluginInfo(Consumer<Text> feedbackSender) {
        Set<String> treePlugins = ServerInfoCommandMod.getCommandTreePlugins();
        Set<String> completionPlugins = ServerInfoCommandMod.getCompletionPlugins();

        Set<String> combinedPlugins = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        combinedPlugins.addAll(treePlugins);
        combinedPlugins.addAll(completionPlugins);

        if (combinedPlugins.isEmpty()) {
            feedbackSender.accept(Text.literal("No plugins detected via commands/tab-completion.").formatted(Formatting.YELLOW)
                    .append(Text.literal(" (Server might not expose them, or request timed out)").formatted(Formatting.DARK_GRAY)));
        } else {
            List<String> sortedPlugins = new ArrayList<>(combinedPlugins);

            MutableText pluginsText = Text.empty();
            for (int i = 0; i < sortedPlugins.size(); i++) {
                String pluginName = sortedPlugins.get(i);
                pluginsText.append(formatPluginName(pluginName));
                if (i < sortedPlugins.size() - 1) {
                    pluginsText.append(Text.literal(", ").formatted(Formatting.GRAY));
                }
            }

            feedbackSender.accept(Text.literal(String.format("Detected Plugins (%d): ", sortedPlugins.size())).formatted(Formatting.GRAY)
                .append(pluginsText));
        }
     }

    private static void handlePluginRequestError(Throwable throwable, Consumer<Text> feedbackSender) {
        String errorMessage;
        Formatting format = Formatting.RED;

        if (throwable instanceof IllegalStateException) {
            if (throwable.getMessage() != null && throwable.getMessage().contains("already in progress")) {
                return;
            } else if (throwable.getMessage() != null && throwable.getMessage().contains("Network handler is null")) {
                errorMessage = "Error: Not connected to a server?";
            } else {
                errorMessage = "Error requesting plugins: " + (throwable.getMessage() != null ? throwable.getMessage() : "Unknown State Error");
            }
        } else if (throwable instanceof java.util.concurrent.TimeoutException) {
             errorMessage = "Plugin request timed out.";
             format = Formatting.YELLOW;
        } else {
            errorMessage = "Error processing plugin request: " + (throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName());
        }

        feedbackSender.accept(Text.literal(errorMessage).formatted(format));

        if (format == Formatting.RED) {
            ServerInfoCommandMod.LOGGER.error("Plugin request future failed:", throwable);
        }
    }

    private static String resolveAddress(String address) {
        if ("localhost".equalsIgnoreCase(address) || "127.0.0.1".equals(address) || "0.0.0.0".equals(address)) {
            return address;
        }
        try {
            return InetAddress.getByName(address).getHostAddress();
        } catch (UnknownHostException e) {
             ServerInfoCommandMod.LOGGER.warn("Could not resolve hostname: {}", address);
             return address;
        } catch (SecurityException e) {
            ServerInfoCommandMod.LOGGER.warn("Security manager prevented resolving hostname: {}", address);
            return address;
        }
    }

    private static MutableText createClickableIpText(String displayAddress, String resolvedIp, int port) {
        String addressOnly = displayAddress;
        if (displayAddress.contains(":")) {
            addressOnly = displayAddress.substring(0, displayAddress.lastIndexOf(':'));
        }
        boolean showResolved = !resolvedIp.equals(addressOnly) && !resolvedIp.equals("127.0.0.1");

        String fullDisplayAddressWithPort = addressOnly + ":" + port;
        String fullResolvedIpWithPort = resolvedIp + ":" + port;

        MutableText ipText = Text.literal(addressOnly).formatted(Formatting.AQUA)
            .append(Text.literal(":" + port).formatted(Formatting.AQUA))
            .setStyle(Style.EMPTY
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, fullDisplayAddressWithPort))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to copy").formatted(Formatting.WHITE))));

        if (showResolved) {
            ipText.append(
                Text.literal(" (").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(resolvedIp).formatted(Formatting.AQUA))
                .append(Text.literal(":" + port).formatted(Formatting.AQUA))
                .append(Text.literal(")").formatted(Formatting.DARK_GRAY))
                .setStyle(Style.EMPTY
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, fullResolvedIpWithPort))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to copy").formatted(Formatting.WHITE)))
                )
            );
        }
        return ipText;
    }

     private static MutableText formatDifficulty(FabricClientCommandSource source) {
         Difficulty difficulty = source.getWorld().getDifficulty();
         MutableText difficultyText = difficulty.getTranslatableName().copy().formatted(Formatting.YELLOW);

         try {
             if (source.getPlayer() != null) {
                 double localDifficulty = source.getWorld().getLocalDifficulty(source.getPlayer().getBlockPos()).getLocalDifficulty();
                 difficultyText.append(Text.literal(String.format(" (Local: %.2f)", localDifficulty)).formatted(Formatting.DARK_AQUA));
             }
         } catch (Exception e) {
             ServerInfoCommandMod.LOGGER.warn("Could not get local difficulty", e);
             difficultyText.append(Text.literal(" (Local: N/A)").formatted(Formatting.DARK_AQUA));
         }
         return difficultyText;
     }

     private static MutableText formatPermissions(FabricClientCommandSource source) {
        int level = 0;
        for (int i = 4; i >= 0; i--) {
             if (source.hasPermissionLevel(i)) {
                level = i;
                break;
            }
        }
        String desc = switch (level) {
            case 0 -> "0 (Player)";
            case 1 -> "1 (Moderator)";
            case 2 -> "2 (Game Master)";
            case 3 -> "3 (Admin)";
            case 4 -> "4 (Owner/OP)";
            default -> String.valueOf(level) + " (Unknown)";
        };
         return Text.literal(desc).formatted(Formatting.YELLOW);
    }

    private static long getDay(FabricClientCommandSource source) {
        long time = source.getWorld().getTimeOfDay();
        return Math.max(0, time / 24000L);
    }

    private static MutableText formatPluginName(String pluginName) {
        String lowerPluginName = pluginName.toLowerCase(Locale.ROOT);

        if (ANTICHEAT_LIST.contains(lowerPluginName)) {
            return Text.literal(pluginName).formatted(Formatting.RED)
                       .styled(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Anti-Cheat/Security Plugin").formatted(Formatting.RED))));
        } else if (VERSION_ALIASES.contains(lowerPluginName)) {
             return Text.literal(pluginName).formatted(Formatting.RED)
                        .styled(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Version Info Command").formatted(Formatting.YELLOW))));
        } else {
            return Text.literal(pluginName).formatted(Formatting.WHITE);
        }
    }

    private static void sendFeedback(FabricClientCommandSource source, Text message) {
        MutableText prefix = Text.literal("[Server] ").formatted(Formatting.GOLD);
        MutableText fullMessage = prefix.append(message);
        source.sendFeedback(fullMessage);
    }
}
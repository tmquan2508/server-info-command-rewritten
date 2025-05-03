package com.tmquan2508.ServerInfoCommand;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.tmquan2508.ServerInfoCommand.commands.Command;
import com.tmquan2508.ServerInfoCommand.event.ServerInfoEvents;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.command.CommandSource;
import net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket;
import net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ServerInfoCommandMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("ServerInfoCommand");
    public static final String MODID = "serverinfocommand";

    private static final float[] tickRates = new float[20];
    private static int nextTickRateIndex = 0;
    private static long lastTickTimeUpdateMillis = -1;
    private static long timeGameJoinedMillis = -1;

    private static final Set<String> commandTreePlugins = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> completionPlugins = Collections.synchronizedSet(new HashSet<>());
    private static String versionCommandAlias = null;
    private static final Set<String> VERSION_ALIASES = Set.of("version", "ver", "about", "bukkit:version", "bukkit:ver", "bukkit:about", "icanhasbukkit");
    private static final Set<String> ANTICHEAT_LIST = Set.of("nocheatplus", "negativity", "warden", "horizon", "illegalstack", "coreprotect", "exploitsx", "vulcan", "abc", "spartan", "kauri", "anticheatreloaded", "witherac", "godseye", "matrix", "wraith", "antixrayheuristics", "grimac");
    private static final Random RANDOM = new Random();
    private static int pendingPluginTransactionId = -1;
    private static boolean waitingForPluginSuggestions = false;
    private static int pluginRequestTimeoutTicks = 0;
    private static final int PLUGIN_REQUEST_TIMEOUT = 100;

    private static CompletableFuture<CommandSuggestionsS2CPacket> pluginSuggestionsFuture = null;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register(((dispatcher, registryAccess) -> Command.register(dispatcher)));
        registerConnectionEvents();
        registerCustomEventListeners();
        registerTickListeners();
        LOGGER.info("ServerInfoCommand Initialized (with Mixin features).");
    }

    private void registerConnectionEvents() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            resetTps();
            resetPlugins();
            timeGameJoinedMillis = -1;
            LOGGER.info("Disconnected from server. State reset.");
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
             resetTps();
             resetPlugins();
             timeGameJoinedMillis = System.currentTimeMillis();
             lastTickTimeUpdateMillis = timeGameJoinedMillis;
             LOGGER.info("Joined server. State reset.");
        });
    }

    private void registerCustomEventListeners() {
        ServerInfoEvents.WORLD_TIME_UPDATE_RECEIVED.register(this::handleWorldTimeUpdate);
        ServerInfoEvents.COMMAND_TREE_PROCESSED.register(this::handleCommandTree);
        ServerInfoEvents.COMMAND_SUGGESTIONS_RECEIVED.register(this::handleCommandSuggestions);
    }

    private void registerTickListeners() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (waitingForPluginSuggestions) {
                pluginRequestTimeoutTicks--;
                if (pluginRequestTimeoutTicks <= 0) {
                    waitingForPluginSuggestions = false;
                    int timedOutId = pendingPluginTransactionId; // Store before resetting
                    pendingPluginTransactionId = -1;
                    LOGGER.warn("Plugin suggestion request timed out (ID: {}).", timedOutId);
                    if (pluginSuggestionsFuture != null && !pluginSuggestionsFuture.isDone()) {
                        pluginSuggestionsFuture.complete(null);
                    }
                }
            }
        });
    }

	private void handleWorldTimeUpdate(long timeReceivedMillis) {
		if (timeGameJoinedMillis != -1 && lastTickTimeUpdateMillis != -1) {
			float timeElapsedSeconds = (float) (timeReceivedMillis - lastTickTimeUpdateMillis) / 1000.0F;

			if (timeElapsedSeconds > 0.0F) {
				float currentRate = MathHelper.clamp(20.0f / timeElapsedSeconds, 0.0f, 20.0f);
				synchronized (tickRates) {
					tickRates[nextTickRateIndex] = currentRate;
					nextTickRateIndex = (nextTickRateIndex + 1) % tickRates.length;
				}
			} else {
				 LOGGER.trace("Time elapsed since last time update is zero. Skipping rate calculation.");
			}
		}
		lastTickTimeUpdateMillis = timeReceivedMillis;
	}


    private void handleCommandTree(CommandDispatcher<CommandSource> dispatcher) {
        if (dispatcher == null) {
             LOGGER.warn("Received null command dispatcher in handleCommandTree event.");
             return;
        }

        commandTreePlugins.clear();
        versionCommandAlias = null;

        CommandNode<CommandSource> rootNode = dispatcher.getRoot();
        if (rootNode != null) {
            for (CommandNode<CommandSource> node : rootNode.getChildren()) {
                String name = node.getName();
                String[] parts = name.split(":", 2);
                 if (parts.length > 1 && !parts[0].equalsIgnoreCase("minecraft") && !parts[0].equalsIgnoreCase("fabric")) {
                    commandTreePlugins.add(parts[0].toLowerCase());
                }
                if (versionCommandAlias == null && VERSION_ALIASES.contains(name.toLowerCase())) {
                    versionCommandAlias = name;
                }
            }
            LOGGER.info("Processed command tree: Found {} potential plugins. Version alias: '{}'", commandTreePlugins.size(), versionCommandAlias);
        } else {
            LOGGER.warn("Command dispatcher root node was null during handleCommandTree event.");
        }
    }

	private void handleCommandSuggestions(CommandSuggestionsS2CPacket packet) {
		if (waitingForPluginSuggestions && pluginSuggestionsFuture != null && !pluginSuggestionsFuture.isDone()) {
			LOGGER.info("Received command suggestions, attempting to complete pending request (ID: {}).", pendingPluginTransactionId);
			pluginSuggestionsFuture.complete(packet);
		} else {
			LOGGER.trace("Received command suggestions but not waiting or future already completed/null.");
		}
	}

    private static void resetTps() {
         synchronized (tickRates) {
             Arrays.fill(tickRates, 0.0f);
        }
        nextTickRateIndex = 0;
        lastTickTimeUpdateMillis = -1;
        LOGGER.info("TPS tracker reset.");
    }

    private static void resetPlugins() {
        commandTreePlugins.clear();
        completionPlugins.clear();
        versionCommandAlias = null;
        waitingForPluginSuggestions = false;
        pendingPluginTransactionId = -1;
        pluginRequestTimeoutTicks = 0;
         if (pluginSuggestionsFuture != null && !pluginSuggestionsFuture.isDone()) {
            pluginSuggestionsFuture.cancel(false);
            pluginSuggestionsFuture = null;
         }
        LOGGER.info("Plugin lists and request state reset.");
    }

    public static double getTps() {
        if (timeGameJoinedMillis == -1 || System.currentTimeMillis() - timeGameJoinedMillis < 4000) {
            return 20.0;
        }

        int numTicks = 0;
        float sumTickRates = 0.0f;
        synchronized (tickRates) {
            for (float tickRate : tickRates) {
                if (tickRate > 0.0f) {
                    sumTickRates += tickRate;
                    numTicks++;
                }
            }
        }

        if (numTicks == 0) {
            return 20.0;
        }

        return (double) sumTickRates / numTicks;
    }

    public static Set<String> getCommandTreePlugins() {
        return Collections.unmodifiableSet(commandTreePlugins);
    }

    public static Set<String> getCompletionPlugins() {
         return Collections.unmodifiableSet(completionPlugins);
    }

	public static CompletableFuture<List<String>> requestPluginsFromServer(ClientPlayNetworkHandler networkHandler) {
		if (networkHandler == null) {
			LOGGER.error("requestPluginsFromServer called with null networkHandler.");
			return CompletableFuture.failedFuture(new IllegalStateException("Network handler is null."));
		}

		if (waitingForPluginSuggestions) {
			LOGGER.warn("Plugin request already in progress (ID: {}). Returning failed future.", pendingPluginTransactionId);
			return CompletableFuture.failedFuture(new IllegalStateException("Plugin request already in progress."));
		}

		if (versionCommandAlias == null) {
			LOGGER.info("No version command alias found, cannot request completion plugins.");
			return CompletableFuture.completedFuture(Collections.emptyList());
		}

		pendingPluginTransactionId = RANDOM.nextInt(Integer.MAX_VALUE - 1) + 1;
		waitingForPluginSuggestions = true;
		pluginRequestTimeoutTicks = PLUGIN_REQUEST_TIMEOUT;
		completionPlugins.clear();

		pluginSuggestionsFuture = new CompletableFuture<>();

		RequestCommandCompletionsC2SPacket packet = new RequestCommandCompletionsC2SPacket(pendingPluginTransactionId, versionCommandAlias + " ");
		networkHandler.sendPacket(packet);
		LOGGER.info("Sent plugin completion request with ID {} for command '{}'", pendingPluginTransactionId, versionCommandAlias);

		return pluginSuggestionsFuture.thenApply(suggestionsPacket -> {
			waitingForPluginSuggestions = false;
			pendingPluginTransactionId = -1;
			pluginRequestTimeoutTicks = 0;

			if (suggestionsPacket == null) {
				LOGGER.warn("Plugin suggestion request timed out.");
				return Collections.<String>emptyList();
			}

			List<String> foundPlugins = new ArrayList<>();
			suggestionsPacket.getSuggestions().getList().forEach(suggestion -> {
				String pluginName = suggestion.getText().toLowerCase();
				if (!commandTreePlugins.contains(pluginName)) {
					if (completionPlugins.add(pluginName)) {
						foundPlugins.add(pluginName);
					}
				}
			});
			LOGGER.info("Received and processed {} new plugin suggestions.", foundPlugins.size());
			return new ArrayList<>(completionPlugins);

		}).exceptionally(e -> {
			LOGGER.error("Error processing plugin suggestions future", e);
			waitingForPluginSuggestions = false;
			pendingPluginTransactionId = -1;
			pluginRequestTimeoutTicks = 0;
			return Collections.<String>emptyList();
		});
	}

    public static String formatPluginName(String name) {
        String lowerName = name.toLowerCase();
        if (ANTICHEAT_LIST.contains(lowerName) || StringUtils.containsIgnoreCase(name, "exploit") || StringUtils.containsIgnoreCase(name, "cheat") || StringUtils.containsIgnoreCase(name, "illegal")) {
            return Formatting.RED + name + Formatting.GRAY;
        }
        return Formatting.AQUA + name + Formatting.GRAY;
    }
}
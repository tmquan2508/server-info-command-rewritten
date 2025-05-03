package com.tmquan2508.ServerInfoCommand.event;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.command.CommandSource;
import net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket;

public class ServerInfoEvents {

    public static final Event<WorldTimeUpdateCallback> WORLD_TIME_UPDATE_RECEIVED = EventFactory.createArrayBacked(WorldTimeUpdateCallback.class,
        (listeners) -> (timeMillis) -> {
            for (WorldTimeUpdateCallback listener : listeners) {
                listener.onWorldTimeUpdate(timeMillis);
            }
        });

    public static final Event<CommandTreeCallback> COMMAND_TREE_PROCESSED = EventFactory.createArrayBacked(CommandTreeCallback.class,
        (listeners) -> (dispatcher) -> {
            for (CommandTreeCallback listener : listeners) {
                listener.onCommandTreeProcessed(dispatcher);
            }
        });

    public static final Event<CommandSuggestionsCallback> COMMAND_SUGGESTIONS_RECEIVED = EventFactory.createArrayBacked(CommandSuggestionsCallback.class,
        (listeners) -> (packet) -> {
            for (CommandSuggestionsCallback listener : listeners) {
                listener.onCommandSuggestions(packet);
            }
        });


    @FunctionalInterface
    public interface WorldTimeUpdateCallback {
        void onWorldTimeUpdate(long timeMillis);
    }

    @FunctionalInterface
    public interface CommandTreeCallback {
        void onCommandTreeProcessed(CommandDispatcher<CommandSource> dispatcher);
    }

     @FunctionalInterface
    public interface CommandSuggestionsCallback {
        void onCommandSuggestions(CommandSuggestionsS2CPacket packet);
    }
}

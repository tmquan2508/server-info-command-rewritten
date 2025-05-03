package com.tmquan2508.ServerInfoCommand.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.tmquan2508.ServerInfoCommand.event.ServerInfoEvents;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.command.CommandSource;
import net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket;
import net.minecraft.network.packet.s2c.play.CommandTreeS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    @Shadow private CommandDispatcher<CommandSource> commandDispatcher;

    @Inject(method = "onWorldTimeUpdate", at = @At("HEAD"))
    private void onWorldTimeUpdateMixin(WorldTimeUpdateS2CPacket packet, CallbackInfo ci) {
        ServerInfoEvents.WORLD_TIME_UPDATE_RECEIVED.invoker().onWorldTimeUpdate(System.currentTimeMillis());
    }

    @Inject(method = "onCommandTree", at = @At("TAIL"))
    private void onCommandTreeMixin(CommandTreeS2CPacket packet, CallbackInfo ci) {
        ServerInfoEvents.COMMAND_TREE_PROCESSED.invoker().onCommandTreeProcessed(this.commandDispatcher);
    }

    @Inject(method = "onCommandSuggestions", at = @At("HEAD"))
    private void onCommandSuggestionsMixin(CommandSuggestionsS2CPacket packet, CallbackInfo ci) {
        ServerInfoEvents.COMMAND_SUGGESTIONS_RECEIVED.invoker().onCommandSuggestions(packet);
    }
}
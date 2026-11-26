package com.deathmatch;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Stream;

public class DeathmatchMod implements ModInitializer {
	public static final String MOD_ID = "deathmatch";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) -> register(dispatcher));
		ServerTickEvents.END_SERVER_TICK.register(MatchManager::tick);
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> MatchManager.onDeath(entity));
		LOGGER.info("Deathmatch loaded");
	}

	private static void register(CommandDispatcher<CommandSourceStack> d) {
		MatchManager.setDispatcher(d);

		// /startvideo "title name" [color]
		d.register(Commands.literal("startvideo")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("title", StringArgumentType.string())
						.executes(ctx -> startVideo(ctx, "white"))
						.then(Commands.argument("color", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(MatchManager.COLORS.stream(), b))
								.executes(ctx -> startVideo(ctx, StringArgumentType.getString(ctx, "color"))))));

		// /deathmatch <arena> <minutes> <strength>
		// /deathmatch speedup | stop | setarena <custom_arena_N> [size]
		d.register(Commands.literal("deathmatch")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("speedup").executes(DeathmatchMod::speedup))
				.then(Commands.literal("stop").executes(DeathmatchMod::stop))
				.then(Commands.literal("setarena")
						.then(Commands.argument("arena", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(MatchManager.CUSTOM_ARENAS.stream(), b))
								.executes(ctx -> setArena(ctx, MatchManager.DEFAULT_SIZE))
								.then(Commands.argument("size", IntegerArgumentType.integer(20, 2000))
										.executes(ctx -> setArena(ctx, IntegerArgumentType.getInteger(ctx, "size"))))))
				.then(Commands.argument("arena", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(MatchManager.ARENAS.stream(), b))
						.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 120))
								.suggests((c, b) -> SharedSuggestionProvider.suggest(Stream.of("5", "10", "15", "20", "25", "30"), b))
								.then(Commands.argument("strength", IntegerArgumentType.integer(0, 5))
										.suggests((c, b) -> SharedSuggestionProvider.suggest(Stream.of("0", "1", "2", "3", "4", "5"), b))
										.executes(DeathmatchMod::startMatch))))));
	}

	private static int startVideo(CommandContext<CommandSourceStack> ctx, String color) {
		CommandSourceStack source = ctx.getSource();
		if (!MatchManager.COLORS.contains(color)) {
			source.sendFailure(Component.literal("Unknown color. Options: " + String.join(", ", MatchManager.COLORS)));
			return 0;
		}
		MatchManager.startVideo(source.getServer(), StringArgumentType.getString(ctx, "title"), color);
		return 1;
	}

	private static int startMatch(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		String arena = StringArgumentType.getString(ctx, "arena");
		int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
		int strength = IntegerArgumentType.getInteger(ctx, "strength");

		String error = MatchManager.start(source.getServer(), arena, minutes, strength);
		if (error != null) {
			source.sendFailure(Component.literal(error));
			return 0;
		}
		return 1;
	}

	private static int speedup(CommandContext<CommandSourceStack> ctx) {
		String error = MatchManager.speedup(ctx.getSource().getServer());
		if (error != null) {
			ctx.getSource().sendFailure(Component.literal(error));
			return 0;
		}
		return 1;
	}

	private static int stop(CommandContext<CommandSourceStack> ctx) {
		String error = MatchManager.stop(ctx.getSource().getServer());
		if (error != null) {
			ctx.getSource().sendFailure(Component.literal(error));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Deathmatch stopped."), true);
		return 1;
	}

	private static int setArena(CommandContext<CommandSourceStack> ctx, int size) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayerOrException();
		String arena = StringArgumentType.getString(ctx, "arena");
		if (!MatchManager.CUSTOM_ARENAS.contains(arena)) {
			source.sendFailure(Component.literal("Only custom arenas can be set: "
					+ String.join(", ", MatchManager.CUSTOM_ARENAS)));
			return 0;
		}
		MatchManager.setArena(source.getServer(), arena, player, size);
		source.sendSuccess(() -> Component.literal(arena + " saved here (border " + size + " blocks wide)."), false);
		return 1;
	}
}

package com.deathmatch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Runs the deathmatch: arenas, countdown, closing border, strength, eliminations. */
public final class MatchManager {
	public static final List<String> COLORS = List.of(
			"white", "gray", "dark_gray", "black", "red", "dark_red", "gold", "yellow",
			"green", "dark_green", "aqua", "dark_aqua", "blue", "dark_blue", "light_purple", "dark_purple");

	public static final List<String> CUSTOM_ARENAS = List.of("custom_arena_1", "custom_arena_2", "custom_arena_3");
	public static final List<String> ARENAS = List.of(
			"custom_arena_1", "custom_arena_2", "custom_arena_3", "end", "plains_biome", "woodland_mansion");

	public static final int DEFAULT_SIZE = 120;   // border width at the start, in blocks
	private static final int FINAL_SIZE = 10;     // border width when it has closed
	private static final int COUNTDOWN_TICKS = 100; // 5 seconds

	public record Arena(String dim, double x, double y, double z, int size) {}

	private enum Phase { IDLE, COUNTDOWN, RUNNING }

	private static CommandDispatcher<CommandSourceStack> dispatcher;
	private static Phase phase = Phase.IDLE;
	private static long ticks;
	private static long fightAt;
	private static long borderEndsAt;
	private static long roundSeconds;
	private static String dimId = "minecraft:overworld";
	private static double cx;
	private static double cy;
	private static double cz;
	private static boolean killDragon;

	private static final Set<UUID> PARTICIPANTS = new LinkedHashSet<>();
	private static final Set<UUID> DEAD = new HashSet<>();
	private static final Set<UUID> OUT = new HashSet<>();

	private static final Map<String, Arena> ARENA_MAP = new HashMap<>();
	private static MinecraftServer arenasFor;
	private static Path arenaFile;

	private MatchManager() {}

	public static void setDispatcher(CommandDispatcher<CommandSourceStack> d) {
		dispatcher = d;
	}

	// ---------------------------------------------------------------- small helpers

	private static String esc(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static String num(double v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}

	/** Runs a vanilla command as the server. Returns false if it failed. */
	private static boolean ok(MinecraftServer server, String command) {
		try {
			dispatcher.execute(command, server.createCommandSourceStack().withSuppressedOutput());
			return true;
		} catch (CommandSyntaxException e) {
			DeathmatchMod.LOGGER.debug("Command failed: {} ({})", command, e.getMessage());
			return false;
		}
	}

	private static String inDim() {
		return "execute in " + dimId + " run ";
	}

	private static void title(MinecraftServer server, String titleSnbt, String subtitleSnbt, int in, int stay, int out) {
		ok(server, "title @a times " + in + " " + stay + " " + out);
		if (subtitleSnbt != null) ok(server, "title @a subtitle " + subtitleSnbt);
		ok(server, "title @a title " + titleSnbt);
	}

	// ---------------------------------------------------------------- /startvideo

	public static void startVideo(MinecraftServer server, String name, String color) {
		title(server,
				"{text:\"GO!\",color:\"green\",bold:true}",
				"{text:\"" + esc(name) + "\",color:\"" + color + "\"}",
				5, 50, 20);
	}

	// ---------------------------------------------------------------- custom arenas

	private static Map<String, Arena> arenas(MinecraftServer server) {
		if (arenasFor != server) {
			arenasFor = server;
			ARENA_MAP.clear();
			arenaFile = server.getWorldPath(LevelResource.ROOT).resolve("deathmatch_arenas.json");
			if (Files.exists(arenaFile)) {
				try {
					JsonObject root = JsonParser.parseString(Files.readString(arenaFile)).getAsJsonObject();
					for (Map.Entry<String, JsonElement> e : root.entrySet()) {
						JsonObject o = e.getValue().getAsJsonObject();
						ARENA_MAP.put(e.getKey(), new Arena(
								o.get("dim").getAsString(),
								o.get("x").getAsDouble(),
								o.get("y").getAsDouble(),
								o.get("z").getAsDouble(),
								o.get("size").getAsInt()));
					}
				} catch (Exception e) {
					DeathmatchMod.LOGGER.error("Could not read {}", arenaFile, e);
				}
			}
		}
		return ARENA_MAP;
	}

	public static void setArena(MinecraftServer server, String name, ServerPlayer player, int size) {
		// the dimension's name looks like "ResourceKey[minecraft:dimension / minecraft:overworld]"
		String raw = player.level().dimension().toString();
		int slash = raw.indexOf("/ ");
		String dim = slash >= 0 ? raw.substring(slash + 2).replace("]", "").trim() : raw;

		arenas(server).put(name, new Arena(dim, player.getX(), player.getY(), player.getZ(), size));

		JsonObject root = new JsonObject();
		for (Map.Entry<String, Arena> e : ARENA_MAP.entrySet()) {
			Arena a = e.getValue();
			JsonObject o = new JsonObject();
			o.addProperty("dim", a.dim());
			o.addProperty("x", a.x());
			o.addProperty("y", a.y());
			o.addProperty("z", a.z());
			o.addProperty("size", a.size());
			root.add(e.getKey(), o);
		}
		try {
			Files.writeString(arenaFile, root.toString());
		} catch (IOException e) {
			DeathmatchMod.LOGGER.error("Could not save {}", arenaFile, e);
		}
	}

	/** Looks for a spot in the overworld with the given biome (checks random places, nothing gets loaded). */
	private static double[] findBiome(MinecraftServer server, String biome) {
		Random random = new Random();
		for (int i = 0; i < 500; i++) {
			int x = random.nextInt(12001) - 6000;
			int z = random.nextInt(12001) - 6000;
			if (ok(server, "execute in minecraft:overworld if biome " + x + " 70 " + z + " " + biome)) {
				return new double[] {x + 0.5, z + 0.5};
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- starting a round

	/** Returns an error message, or null when the round has started. */
	public static String start(MinecraftServer server, String arena, int minutes, int strengthLevel) {
		if (phase != Phase.IDLE) return "A deathmatch is already running. Use /deathmatch stop first.";
		if (server.getPlayerList().getPlayers().isEmpty()) return "There are no players online.";

		String dim;
		double x;
		double y;
		double z;
		int size = DEFAULT_SIZE;
		boolean custom = false;

		if (arena.equals("end")) {
			dim = "minecraft:the_end";
			x = 0;
			y = 90;
			z = 0;
		} else if (arena.equals("plains_biome") || arena.equals("woodland_mansion")) {
			boolean mansion = arena.equals("woodland_mansion");
			double[] spot = findBiome(server, mansion ? "minecraft:dark_forest" : "minecraft:plains");
			if (spot == null && mansion) spot = findBiome(server, "minecraft:plains");
			if (spot == null) return "Could not find a suitable biome nearby. Try again.";
			dim = "minecraft:overworld";
			x = spot[0];
			z = spot[1];
			y = 200;
			if (mansion && !ok(server, "execute in minecraft:overworld run place structure minecraft:mansion "
					+ (int) Math.floor(x) + " 70 " + (int) Math.floor(z))) {
				return "Could not generate a woodland mansion there. Try again.";
			}
		} else if (CUSTOM_ARENAS.contains(arena)) {
			Arena a = arenas(server).get(arena);
			if (a == null) {
				return arena + " is not set up yet. Stand in the arena and run /deathmatch setarena " + arena;
			}
			dim = a.dim();
			x = a.x();
			y = a.y();
			z = a.z();
			size = a.size();
			custom = true;
		} else {
			return "Unknown arena. Options: " + String.join(", ", ARENAS);
		}

		dimId = dim;
		cx = x;
		cy = y;
		cz = z;
		roundSeconds = minutes * 60L;
		killDragon = arena.equals("end");

		PARTICIPANTS.clear();
		DEAD.clear();
		OUT.clear();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			PARTICIPANTS.add(p.getUUID());
		}

		String in = inDim();

		// the border first, so players land inside it
		ok(server, in + "worldborder center " + num(x) + " " + num(z));
		ok(server, in + "worldborder set " + size);
		ok(server, in + "worldborder damage buffer 0");
		ok(server, in + "worldborder damage amount 1");
		ok(server, in + "worldborder warning distance 10");

		// everyone to the arena, spread out
		ok(server, "gamemode survival @a");
		ok(server, in + "tp @a " + num(x) + " " + num(y) + " " + num(z));
		ok(server, "effect give @a minecraft:slow_falling 20 0 true");
		ok(server, "effect give @a minecraft:resistance 10 4 true");
		if (custom) {
			ok(server, in + "spreadplayers " + num(x) + " " + num(z) + " 3 10 under "
					+ (int) Math.ceil(y + 4) + " false @a");
		} else {
			ok(server, in + "spreadplayers " + num(x) + " " + num(z) + " 6 24 false @a");
		}

		// full health and food, and the strength level
		ok(server, "effect give @a minecraft:instant_health 1 10 true");
		ok(server, "effect give @a minecraft:saturation 10 10 true");
		ok(server, "effect clear @a minecraft:strength");
		if (strengthLevel > 0) {
			ok(server, "effect give @a minecraft:strength infinite " + (strengthLevel - 1) + " true");
		}

		if (killDragon) ok(server, "kill @e[type=minecraft:ender_dragon]");

		ok(server, "tellraw @a {text:\"Deathmatch starts in 5 seconds!\",color:\"yellow\",bold:true}");

		phase = Phase.COUNTDOWN;
		fightAt = ticks + COUNTDOWN_TICKS;
		return null;
	}

	// ---------------------------------------------------------------- during the round

	public static void tick(MinecraftServer server) {
		ticks++;
		if (phase == Phase.IDLE) return;

		if (killDragon && ticks % 20 == 0) ok(server, "kill @e[type=minecraft:ender_dragon]");

		if (phase == Phase.COUNTDOWN && ticks >= fightAt) beginFight(server);
		if (phase == Phase.RUNNING && ticks % 10 == 0) checkPlayers(server);
	}

	private static void setBorder(MinecraftServer server, int size, long seconds) {
		// newer versions want a unit on the time ("300s"), older ones want a plain number of seconds
		String base = inDim() + "worldborder set " + size + " ";
		if (!ok(server, base + seconds + "s")) ok(server, base + seconds);
	}

	private static void beginFight(MinecraftServer server) {
		title(server, "{text:\"FIGHT!\",color:\"red\",bold:true}", null, 5, 40, 15);
		ok(server, "playsound minecraft:entity.ender_dragon.growl master @a");
		setBorder(server, FINAL_SIZE, roundSeconds);
		borderEndsAt = ticks + roundSeconds * 20L;
		phase = Phase.RUNNING;
	}

	public static void onDeath(LivingEntity entity) {
		if (phase == Phase.RUNNING && entity instanceof ServerPlayer sp && PARTICIPANTS.contains(sp.getUUID())) {
			DEAD.add(sp.getUUID());
		}
	}

	private static void checkPlayers(MinecraftServer server) {
		// players who died become spectators as soon as they respawn
		for (UUID id : new ArrayList<>(DEAD)) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null) {
				DEAD.remove(id);
				OUT.add(id);
			} else if (p.isAlive()) {
				DEAD.remove(id);
				OUT.add(id);
				ok(server, "gamemode spectator " + id);
				ok(server, inDim() + "tp " + id + " " + num(cx) + " " + num(cy) + " " + num(cz));
			}
		}

		// last player standing wins (needs at least two players at the start)
		if (PARTICIPANTS.size() >= 2) {
			List<UUID> alive = new ArrayList<>();
			for (UUID id : PARTICIPANTS) {
				if (DEAD.contains(id) || OUT.contains(id)) continue;
				if (server.getPlayerList().getPlayer(id) == null) continue; // left the game
				alive.add(id);
			}
			if (alive.size() <= 1) {
				finish(server, alive.isEmpty() ? null : alive.get(0));
			}
		}
	}

	private static void finish(MinecraftServer server, UUID winnerId) {
		String name = null;
		if (winnerId != null) {
			ServerPlayer winner = server.getPlayerList().getPlayer(winnerId);
			if (winner != null) name = winner.getName().getString();
		}
		if (name == null) {
			title(server, "{text:\"Nobody wins!\",color:\"gray\",bold:true}", null, 10, 70, 20);
		} else {
			title(server, "{text:\"" + esc(name) + " WINS!\",color:\"gold\",bold:true}",
					"{text:\"Deathmatch over\",color:\"yellow\"}", 10, 70, 20);
		}
		reset(server);
	}

	// ---------------------------------------------------------------- speedup and stop

	/** Halves the time the border has left. Returns an error message, or null. */
	public static String speedup(MinecraftServer server) {
		if (phase != Phase.RUNNING) return "There is no round in progress (the 5 second countdown must be over).";
		long remaining = borderEndsAt - ticks;
		if (remaining <= 100) return "The border is already almost closed.";

		long newSeconds = Math.max(5, remaining / 40); // half of the remaining time, in seconds
		setBorder(server, FINAL_SIZE, newSeconds);
		borderEndsAt = ticks + newSeconds * 20L;
		ok(server, "title @a actionbar {text:\"The border is closing faster!\",color:\"red\",bold:true}");
		return null;
	}

	public static String stop(MinecraftServer server) {
		if (phase == Phase.IDLE) return "No deathmatch is running.";
		reset(server);
		return null;
	}

	private static void reset(MinecraftServer server) {
		String in = inDim();
		ok(server, in + "worldborder set 59999968");
		ok(server, in + "worldborder center 0 0");
		ok(server, in + "worldborder damage buffer 5");
		ok(server, in + "worldborder damage amount 0.2");
		ok(server, in + "worldborder warning distance 5");
		ok(server, "effect clear @a minecraft:strength");
		for (UUID id : OUT) {
			ok(server, "gamemode survival " + id);
		}
		PARTICIPANTS.clear();
		DEAD.clear();
		OUT.clear();
		killDragon = false;
		phase = Phase.IDLE;
	}
}

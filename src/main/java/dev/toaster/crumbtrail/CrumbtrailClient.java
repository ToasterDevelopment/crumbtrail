package dev.toaster.crumbtrail;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Crumbtrail: save spots and get an on-screen arrow back to them.
 * Commands:
 *   /crumb                 list saved spots
 *   /crumb save <name>     save where you're standing
 *   /crumb go <name>       show the arrow
 *   /crumb stop            hide the arrow
 *   /crumb delete <name>   remove a spot
 */
public final class CrumbtrailClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("crumbtrail");
	private static final CoordStore STORE = new CoordStore();

	/** Whether the player was dead on the previous tick, so each death is recorded once. */
	private static boolean wasDead;

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			register(dispatcher, "crumb");
		});

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			checkDeath(mc);
			Navigator.tick(mc);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			Navigator.stop();
			wasDead = false;
		});
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("crumbtrail", "navigator"), Navigator::render);
	}

	private static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, String root) {
		SuggestionProvider<FabricClientCommandSource> names = (ctx, builder) -> {
			String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
			String key = worldKey();
			for (CoordStore.Entry e : STORE.get(key)) {
				String name = STORE.nameOf(key, e);
				if (name.toLowerCase(Locale.ROOT).startsWith(typed)) builder.suggest(name);
			}
			return builder.buildFuture();
		};
		dispatcher.register(literal(root)
				.executes(CrumbtrailClient::list)
				.then(literal("save")
						.then(argument("name", StringArgumentType.greedyString())
								.executes(ctx -> save(ctx, StringArgumentType.getString(ctx, "name")))))
				.then(literal("go")
						.then(argument("name", StringArgumentType.greedyString()).suggests(names)
								.executes(ctx -> go(ctx, StringArgumentType.getString(ctx, "name")))))
				.then(literal("stop").executes(CrumbtrailClient::stop))
				.then(literal("delete")
						.then(argument("name", StringArgumentType.greedyString()).suggests(names)
								.executes(ctx -> delete(ctx, StringArgumentType.getString(ctx, "name"))))));
	}

	// ---- death auto-save ----

	private static void checkDeath(Minecraft mc) {
		LocalPlayer player = mc.player;
		boolean dead = player != null && player.isDeadOrDying();
		if (dead && !wasDead) recordDeath(player);
		wasDead = dead;
	}

	private static void recordDeath(LocalPlayer player) {
		BlockPos pos = player.blockPosition();
		String dim = player.level().dimension().identifier().toString();
		long now = System.currentTimeMillis();
		String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(now));
		CoordStore.Entry entry = new CoordStore.Entry("Death (" + when + ")", pos.getX(), pos.getY(), pos.getZ(),
				dim, now, true);
		try {
			if (!STORE.addDeath(worldKey(), entry)) return;
		} catch (IOException e) {
			LOGGER.error("Failed to save death point", e);
			return;
		}
		player.sendSystemMessage(Component.literal("Death point saved at " + coords(entry) + ". ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal("[Show the way back]").withStyle(style -> style
						.withColor(ChatFormatting.YELLOW)
						.withClickEvent(new ClickEvent.RunCommand("/crumb go death-1"))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("/crumb go death-1"))))));
	}

	// ---- commands ----

	private static int save(CommandContext<FabricClientCommandSource> ctx, String rawName) {
		FabricClientCommandSource src = ctx.getSource();
		String name = rawName.trim();
		if (name.toLowerCase(Locale.ROOT).matches("death(-\\d+)?|\\d+")) {
			src.sendError(Component.literal("\"" + name + "\" is reserved. Pick another name."));
			return 0;
		}
		LocalPlayer player = src.getPlayer();
		BlockPos pos = player.blockPosition();
		String dim = player.level().dimension().identifier().toString();
		CoordStore.Entry entry = new CoordStore.Entry(name, pos.getX(), pos.getY(), pos.getZ(), dim,
				System.currentTimeMillis(), false);
		boolean replaced;
		try {
			replaced = STORE.put(worldKey(), entry);
		} catch (IOException e) {
			LOGGER.error("Failed to save spot", e);
			src.sendError(Component.literal("Couldn't save: " + e.getMessage()));
			return 0;
		}
		src.sendFeedback(Component.literal(replaced ? "Updated " : "Saved ").withStyle(ChatFormatting.GREEN)
				.append(Component.literal(name).withStyle(ChatFormatting.WHITE))
				.append(Component.literal(" at " + coords(entry) + " (" + dimName(dim) + ")").withStyle(ChatFormatting.GRAY)));
		return 1;
	}

	private static int list(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource src = ctx.getSource();
		String key = worldKey();
		List<CoordStore.Entry> entries = STORE.get(key);
		if (entries.isEmpty()) {
			src.sendFeedback(Component.literal("Nothing saved here yet. Try /crumb save <name>.")
					.withStyle(ChatFormatting.GRAY));
			return 1;
		}
		LocalPlayer player = src.getPlayer();
		String hereDim = player.level().dimension().identifier().toString();
		src.sendFeedback(Component.literal("Saved spots (click one to show the way):").withStyle(ChatFormatting.GOLD));
		// Oldest first so the newest lands at the bottom, nearest the chat box.
		for (int i = 0; i < entries.size(); i++) {
			CoordStore.Entry e = entries.get(i);
			String name = STORE.nameOf(key, e);
			MutableComponent line = Component.literal("#" + (i + 1) + " ").withStyle(ChatFormatting.DARK_GRAY)
					.append(Component.literal(name).withStyle(e.death() ? ChatFormatting.RED : ChatFormatting.WHITE))
					.append(Component.literal(": " + e.x() + " " + e.y() + " " + e.z()).withStyle(ChatFormatting.AQUA))
					.append(Component.literal(" · " + dimName(e.dimension())).withStyle(ChatFormatting.GRAY));
			if (e.dimension().equals(hereDim)) {
				double dx = e.x() + 0.5 - player.getX(), dz = e.z() + 0.5 - player.getZ();
				line.append(Component.literal(" · " + Math.round(Math.sqrt(dx * dx + dz * dz)) + " blocks")
						.withStyle(ChatFormatting.YELLOW));
			}
			line.withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand("/crumb go " + name)));
			line.append(Component.literal(" [copy]").withStyle(style -> style
					.withColor(ChatFormatting.DARK_AQUA)
					.withClickEvent(new ClickEvent.CopyToClipboard(e.x() + " " + e.y() + " " + e.z()))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy coords to clipboard")))));
			src.sendFeedback(line);
		}
		return entries.size();
	}

	private static int go(CommandContext<FabricClientCommandSource> ctx, String query) {
		FabricClientCommandSource src = ctx.getSource();
		String key = worldKey();
		CoordStore.Match m = STORE.find(key, query);
		if (m.entry() == null) {
			src.sendError(Component.literal(m.error()));
			return 0;
		}
		Navigator.start(m.entry());
		src.sendFeedback(Component.literal("Showing the way to ").withStyle(ChatFormatting.GREEN)
				.append(Component.literal(STORE.nameOf(key, m.entry())).withStyle(ChatFormatting.WHITE))
				.append(Component.literal(". /crumb stop to hide.").withStyle(ChatFormatting.GRAY)));
		return 1;
	}

	private static int stop(CommandContext<FabricClientCommandSource> ctx) {
		Navigator.stop();
		ctx.getSource().sendFeedback(Component.literal("Arrow hidden.").withStyle(ChatFormatting.GRAY));
		return 1;
	}

	private static int delete(CommandContext<FabricClientCommandSource> ctx, String query) {
		FabricClientCommandSource src = ctx.getSource();
		String key = worldKey();
		CoordStore.Match m = STORE.find(key, query);
		if (m.entry() == null) {
			src.sendError(Component.literal(m.error()));
			return 0;
		}
		String name = STORE.nameOf(key, m.entry());
		try {
			STORE.remove(key, m.entry());
		} catch (IOException e) {
			LOGGER.error("Failed to delete spot", e);
			src.sendError(Component.literal("Couldn't delete: " + e.getMessage()));
			return 0;
		}
		if (m.entry().equals(Navigator.target())) Navigator.stop();
		src.sendFeedback(Component.literal("Deleted " + name).withStyle(ChatFormatting.YELLOW));
		return 1;
	}

	// ---- helpers ----

	/** Singleplayer: "sp_" + save folder name. Multiplayer: "mp_" + server address. */
	private static String worldKey() {
		Minecraft mc = Minecraft.getInstance();
		MinecraftServer sp = mc.getSingleplayerServer();
		if (sp != null) {
			return "sp_" + sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
		}
		ServerData server = mc.getCurrentServer();
		return server != null ? "mp_" + server.ip : "unknown";
	}

	private static String coords(CoordStore.Entry e) {
		return e.x() + ", " + e.y() + ", " + e.z();
	}

	/** The name shown to the player: the spot's label, or death-1, death-2, ... for death points. */
	static String displayName(CoordStore.Entry e) {
		return STORE.nameOf(worldKey(), e);
	}

	static String dimName(String id) {
		return switch (id) {
			case "minecraft:overworld" -> "Overworld";
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "End";
			default -> id;
		};
	}
}

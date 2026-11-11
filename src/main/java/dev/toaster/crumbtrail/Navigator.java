package dev.toaster.crumbtrail;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Holds the coords you're heading to and draws an arrow + distance at the top of the screen. */
public final class Navigator {
	private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
	/** Horizontal distance (blocks) that counts as "arrived". */
	private static final double ARRIVE_DISTANCE = 4;

	private static CoordStore.Entry target;

	public static void start(CoordStore.Entry entry) {
		target = entry;
	}

	public static void stop() {
		target = null;
	}

	public static CoordStore.Entry target() {
		return target;
	}

	/** Called every client tick: announces arrival and clears the target. */
	public static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (target == null || player == null || player.isDeadOrDying()) return;
		if (!sameDimension(player)) return;
		if (horizontalDistance(player) <= ARRIVE_DISTANCE) {
			player.sendSystemMessage(Component.literal("You've arrived at ").withStyle(ChatFormatting.GREEN)
					.append(Component.literal(target.label()).withStyle(ChatFormatting.WHITE)));
			target = null;
		}
	}

	/** HUD element: one line centered near the top of the screen. */
	public static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (target == null || player == null) return;

		Component line;
		if (!sameDimension(player)) {
			line = Component.literal(target.label()).withStyle(ChatFormatting.WHITE)
					.append(Component.literal(" is in the " + CrumbtrailClient.dimName(target.dimension()))
							.withStyle(ChatFormatting.GRAY));
		} else {
			double dx = target.x() + 0.5 - player.getX();
			double dz = target.z() + 0.5 - player.getZ();
			// Minecraft yaw: 0 = south (+Z), 90 = west (-X), increasing clockwise when seen from above.
			double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
			double relative = Mth.wrapDegrees(targetYaw - player.getYRot());
			String arrow = ARROWS[Math.floorMod((int) Math.round(relative / 45.0), 8)];

			var built = Component.literal(arrow + " ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(target.label()).withStyle(ChatFormatting.WHITE))
					.append(Component.literal(" · " + Math.round(Math.sqrt(dx * dx + dz * dz)) + " blocks")
							.withStyle(ChatFormatting.AQUA));
			int dy = target.y() - Mth.floor(player.getY());
			if (Math.abs(dy) > 3) {
				built.append(Component.literal(" " + (dy > 0 ? "▲" : "▼") + Math.abs(dy)).withStyle(ChatFormatting.GRAY));
			}
			line = built;
		}

		int width = mc.font.width(line);
		int x = (graphics.guiWidth() - width) / 2;
		int y = 4;
		graphics.fill(x - 3, y - 2, x + width + 3, y + mc.font.lineHeight + 1, 0x80000000);
		graphics.text(mc.font, line, x, y, 0xFFFFFFFF, true);
	}

	private static boolean sameDimension(LocalPlayer player) {
		return player.level().dimension().identifier().toString().equals(target.dimension());
	}

	private static double horizontalDistance(LocalPlayer player) {
		double dx = target.x() + 0.5 - player.getX();
		double dz = target.z() + 0.5 - player.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	private Navigator() {}
}

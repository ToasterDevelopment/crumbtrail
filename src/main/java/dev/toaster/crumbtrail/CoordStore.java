package dev.toaster.crumbtrail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Saved spots for one world or server, stored as JSON in config/crumbtrail/<worldKey>.json. */
public final class CoordStore {
	/** {@code death} is true for auto-saved death points (missing in older files, so it reads as false). */
	public record Entry(String label, int x, int y, int z, String dimension, long savedAt, boolean death) {}

	/** How many auto-saved death points to keep per world; older ones are dropped. */
	public static final int MAX_DEATHS = 5;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("crumbtrail");

	private String loadedKey;
	private List<Entry> entries = new ArrayList<>();

	/** Entries for the given world, oldest first. */
	public List<Entry> get(String worldKey) {
		load(worldKey);
		return entries;
	}

	/** The most recent death point, or null. This is what "death" means in /crumb go death. */
	public Entry latestDeath(String worldKey) {
		load(worldKey);
		for (int i = entries.size() - 1; i >= 0; i--) {
			if (entries.get(i).death()) return entries.get(i);
		}
		return null;
	}

	/** Saves a spot. A spot with the same name (ignoring case) is replaced. Returns true if it replaced one. */
	public boolean put(String worldKey, Entry entry) throws IOException {
		load(worldKey);
		boolean replaced = entries.removeIf(e -> !e.death() && e.label().equalsIgnoreCase(entry.label()));
		entries.add(entry);
		save();
		return replaced;
	}

	/**
	 * Adds a death point, skipping exact repeats (e.g. rejoining while still on the death screen)
	 * and trimming the oldest death points beyond {@link #MAX_DEATHS}. The entry's label is used as a
	 * base name (e.g. "death-plains"); "-2", "-3", ... is added if that name is taken. The name never
	 * changes afterwards. Returns the stored entry, or null if skipped.
	 */
	public Entry addDeath(String worldKey, Entry entry) throws IOException {
		load(worldKey);
		Entry last = latestDeath(worldKey);
		if (last != null && last.x() == entry.x() && last.y() == entry.y() && last.z() == entry.z()
				&& last.dimension().equals(entry.dimension())) return null;
		String name = entry.label();
		for (int n = 2; isTaken(name); n++) name = entry.label() + "-" + n;
		entry = new Entry(name, entry.x(), entry.y(), entry.z(), entry.dimension(), entry.savedAt(), true);
		entries.add(entry);
		long deaths = entries.stream().filter(Entry::death).count();
		for (int i = 0; i < entries.size() && deaths > MAX_DEATHS; ) {
			if (entries.get(i).death()) {
				entries.remove(i);
				deaths--;
			} else {
				i++;
			}
		}
		save();
		return entry;
	}

	private boolean isTaken(String name) {
		return entries.stream().anyMatch(e -> e.label().equalsIgnoreCase(name));
	}

	public void remove(String worldKey, Entry entry) throws IOException {
		load(worldKey);
		entries.remove(entry);
		save();
	}

	/** Result of looking up what the player typed. Exactly one of {@code entry} / {@code error} is set. */
	public record Match(Entry entry, String error) {}

	/**
	 * Finds an entry by list number ("3"), "death" (the most recent death), exact name
	 * ("cows by the river", "death-plains"), or a unique partial name ("cow").
	 */
	public Match find(String worldKey, String query) {
		load(worldKey);
		String q = query.trim().toLowerCase(Locale.ROOT);
		if (entries.isEmpty()) return new Match(null, "Nothing saved here yet.");
		if (q.matches("\\d+")) {
			int n = Integer.parseInt(q);
			return n >= 1 && n <= entries.size() ? new Match(entries.get(n - 1), null)
					: new Match(null, "No spot #" + n + ".");
		}
		if (q.equals("death")) {
			Entry latest = latestDeath(worldKey);
			if (latest != null) return new Match(latest, null);
		}
		List<Entry> partial = new ArrayList<>();
		for (Entry e : entries) {
			String name = e.label().toLowerCase(Locale.ROOT);
			if (name.equals(q)) return new Match(e, null);
			if (name.contains(q)) partial.add(e);
		}
		if (partial.size() == 1) return new Match(partial.getFirst(), null);
		if (partial.isEmpty()) return new Match(null, "No spot named \"" + query.trim() + "\".");
		List<String> names = partial.stream().map(Entry::label).toList();
		return new Match(null, "\"" + query.trim() + "\" matches several: " + String.join(", ", names));
	}

	public Path fileFor(String worldKey) {
		return DIR.resolve(sanitize(worldKey) + ".json");
	}

	private void load(String worldKey) {
		if (worldKey.equals(loadedKey)) return;
		loadedKey = worldKey;
		entries = new ArrayList<>();
		Path file = fileFor(worldKey);
		if (!Files.exists(file)) return;
		try {
			List<Entry> read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
					new TypeToken<List<Entry>>() {}.getType());
			if (read != null) entries = new ArrayList<>(read);
		} catch (Exception e) {
			CrumbtrailClient.LOGGER.error("Could not read {}", file, e);
		}
		migrateOldDeathNames();
	}

	/**
	 * Version 1.0.0 named deaths by recency (death-1 = newest), recomputed each time, with a
	 * "Death (date)" label underneath. Freeze those as their current death-N so they stop shifting.
	 */
	private void migrateOldDeathNames() {
		boolean changed = false;
		int n = 0;
		for (int i = entries.size() - 1; i >= 0; i--) {
			Entry e = entries.get(i);
			if (!e.death()) continue;
			n++;
			if (e.label().toLowerCase(Locale.ROOT).startsWith("death-")) continue;
			entries.set(i, new Entry("death-" + n, e.x(), e.y(), e.z(), e.dimension(), e.savedAt(), true));
			changed = true;
		}
		if (!changed) return;
		try {
			save();
		} catch (IOException e) {
			CrumbtrailClient.LOGGER.error("Could not save renamed death points", e);
		}
	}

	private void save() throws IOException {
		Path file = fileFor(loadedKey);
		Files.createDirectories(DIR);
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(entries), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private static String sanitize(String key) {
		String s = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
		return s.isBlank() ? "unknown" : s;
	}
}

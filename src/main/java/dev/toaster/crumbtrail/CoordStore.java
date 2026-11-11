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

	/** The name you type for an entry: its label, or death-1 (most recent), death-2, ... for deaths. */
	public String nameOf(String worldKey, Entry entry) {
		load(worldKey);
		if (!entry.death()) return entry.label();
		int n = 0;
		for (int i = entries.size() - 1; i >= 0; i--) {
			if (entries.get(i).death()) {
				n++;
				if (entries.get(i) == entry) return "death-" + n;
			}
		}
		return "death";
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
	 * and trimming the oldest death points beyond {@link #MAX_DEATHS}. Returns false if skipped.
	 */
	public boolean addDeath(String worldKey, Entry entry) throws IOException {
		load(worldKey);
		for (int i = entries.size() - 1; i >= 0; i--) {
			Entry e = entries.get(i);
			if (!e.death()) continue;
			if (e.x() == entry.x() && e.y() == entry.y() && e.z() == entry.z()
					&& e.dimension().equals(entry.dimension())) return false;
			break; // only compare against the most recent death
		}
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
		return true;
	}

	public void remove(String worldKey, Entry entry) throws IOException {
		load(worldKey);
		entries.remove(entry);
		save();
	}

	/** Result of looking up what the player typed. Exactly one of {@code entry} / {@code error} is set. */
	public record Match(Entry entry, String error) {}

	/**
	 * Finds an entry by list number ("3"), exact name ("cows by the river", "death-1"),
	 * or a unique partial name ("cow").
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
		List<Entry> partial = new ArrayList<>();
		for (Entry e : entries) {
			String name = nameOf(worldKey, e).toLowerCase(Locale.ROOT);
			if (name.equals(q)) return new Match(e, null);
			if (name.contains(q)) partial.add(e);
		}
		if (partial.size() == 1) return new Match(partial.getFirst(), null);
		if (partial.isEmpty()) return new Match(null, "No spot named \"" + query.trim() + "\".");
		List<String> names = partial.stream().map(e -> nameOf(worldKey, e)).toList();
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

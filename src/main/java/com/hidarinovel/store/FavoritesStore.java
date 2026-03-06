package com.hidarinovel.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hidarinovel.model.FavoriteEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Persists favorite novels as JSON in {@code ~/.config/hidarinovel/favorites.json}.
 * Thread-safe via synchronised access (Spring Shell is single-threaded anyway).
 */
@Component
public class FavoritesStore {

    private static final Logger log = LoggerFactory.getLogger(FavoritesStore.class);

    private static final Path CONFIG_DIR = Path.of(
            System.getProperty("user.home"), ".config", "hidarinovel");
    private static final Path FAVORITES_FILE = CONFIG_DIR.resolve("favorites.json");

    private final ObjectMapper mapper;
    private LinkedHashMap<String, FavoriteEntry> entries; // slug → entry

    public FavoritesStore() {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.entries = load();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Adds or updates a favorite entry. */
    public void add(FavoriteEntry entry) {
        entries.put(entry.slug(), entry);
        save();
        log.debug("Favorite added: {}", entry.slug());
    }

    /** Removes a favorite by slug. Returns true if it existed. */
    public boolean remove(String slug) {
        boolean existed = entries.remove(slug) != null;
        if (existed) save();
        return existed;
    }

    /** Returns all favorites in insertion order. */
    public List<FavoriteEntry> list() {
        return new ArrayList<>(entries.values());
    }

    /** Returns a favorite by 1-based index. */
    public Optional<FavoriteEntry> get(int index) {
        if (index < 1 || index > entries.size()) return Optional.empty();
        return Optional.of(new ArrayList<>(entries.values()).get(index - 1));
    }

    /** Returns a favorite by slug. */
    public Optional<FavoriteEntry> getBySlug(String slug) {
        return Optional.ofNullable(entries.get(slug));
    }

    /** Updates an existing entry. */
    public void update(FavoriteEntry entry) {
        if (entries.containsKey(entry.slug())) {
            entries.put(entry.slug(), entry);
            save();
        }
    }

    public int size() { return entries.size(); }

    // ── Persistence ───────────────────────────────────────────────────────────

    private LinkedHashMap<String, FavoriteEntry> load() {
        var result = new LinkedHashMap<String, FavoriteEntry>();
        if (!Files.exists(FAVORITES_FILE)) return result;
        try {
            List<FavoriteEntry> list = mapper.readValue(
                    FAVORITES_FILE.toFile(),
                    new TypeReference<List<FavoriteEntry>>() {});
            list.forEach(e -> result.put(e.slug(), e));
            log.debug("Loaded {} favorites from {}", result.size(), FAVORITES_FILE);
        } catch (IOException e) {
            log.warn("Could not load favorites: {}", e.getMessage());
        }
        return result;
    }

    private void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            mapper.writeValue(FAVORITES_FILE.toFile(), new ArrayList<>(entries.values()));
            log.debug("Saved {} favorites to {}", entries.size(), FAVORITES_FILE);
        } catch (IOException e) {
            log.warn("Could not save favorites: {}", e.getMessage());
        }
    }
}

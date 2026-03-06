package com.hidarinovel.scraper;

import com.hidarinovel.model.Novel;
import com.hidarinovel.model.SearchFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Holds all registered {@link SiteScraperAdapter} beans and provides
 * aggregated search across all of them.
 *
 * <p>Spring automatically injects every {@code @Component} that implements
 * {@link SiteScraperAdapter}, so adding a new site requires only creating
 * a new adapter class — no changes needed here.
 */
@Component
public class ScraperRegistry {

    private static final Logger log = LoggerFactory.getLogger(ScraperRegistry.class);

    private final List<SiteScraperAdapter> adapters;
    private final Map<String, SiteScraperAdapter> byId;

    public ScraperRegistry(
            NovelManiaScraper novelManiaScraper,
            NovelsBrScraper novelsBrScraper,
            CentralNovelScraper centralNovelScraper) {
        this.adapters = List.of(novelManiaScraper, novelsBrScraper, centralNovelScraper);
        this.byId = adapters.stream()
                .collect(Collectors.toMap(SiteScraperAdapter::siteId, a -> a));
        log.info("Registered {} scraper(s): {}", adapters.size(),
                adapters.stream().map(SiteScraperAdapter::displayName)
                        .collect(Collectors.joining(", ")));
    }

    /** Returns all registered adapters in registration order. */
    public List<SiteScraperAdapter> all() {
        return adapters;
    }

    /**
     * Returns the adapter for the given {@code siteId}.
     * Falls back to the first registered adapter if the id is unknown.
     */
    public SiteScraperAdapter get(String siteId) {
        SiteScraperAdapter a = siteId != null ? byId.get(siteId) : null;
        if (a == null) {
            log.warn("Unknown siteId '{}', falling back to first registered adapter", siteId);
            a = adapters.getFirst();
        }
        return a;
    }

    /**
     * Searches all registered sites in parallel and returns combined results,
     * preserving the per-site order (all results from site A, then site B, …).
     * Sites that fail are skipped with a warning.
     */
    public List<Novel> searchAll(String query, SearchFilter filter) {
        // Launch all searches concurrently
        List<CompletableFuture<List<Novel>>> futures = adapters.stream()
                .map(adapter -> CompletableFuture.supplyAsync(() -> {
                    try {
                        List<Novel> found = adapter.search(query, filter);
                        log.debug("{}: {} result(s) for '{}'", adapter.displayName(), found.size(), query);
                        return found;
                    } catch (IOException e) {
                        log.warn("Search failed for {}: {}", adapter.displayName(), e.getMessage());
                        return List.<Novel>of();
                    }
                }))
                .toList();

        // Collect in registration order (join preserves order)
        return futures.stream()
                .flatMap(f -> f.join().stream())
                .collect(Collectors.toList());
    }
}

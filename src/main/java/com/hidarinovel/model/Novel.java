package com.hidarinovel.model;

import java.util.List;

/**
 * Novel representation populated from search results and/or the detail page.
 *
 * <p>{@code siteId} identifies which site scraper owns this novel
 * (e.g. "hidarinovel", "novelsbr"). It is used by {@link com.hidarinovel.scraper.ScraperRegistry}
 * to route operations to the correct adapter.
 *
 * <p>Search results provide partial data (siteId, slug, title, author, status,
 * totalChapters, coverUrl, url). The detail page fills in the remaining
 * fields (synopsis, altNames, genres, type, origin, views, favorites).
 */
public record Novel(
        String siteId,      // "hidarinovel", "novelsbr", …
        String slug,
        String title,
        String author,
        String status,
        int totalChapters,
        String coverUrl,
        String url,
        String synopsis,
        String altNames,
        List<String> genres,
        String type,
        String origin,
        String views,
        int favorites
) {

    /**
     * Compact constructor for search results (partial data).
     * Detail-only fields are set to defaults.
     */
    public Novel(String siteId, String slug, String title, String author, String status,
                 int totalChapters, String coverUrl, String url) {
        this(siteId, slug, title, author, status, totalChapters, coverUrl, url,
             "", "", List.of(), "", "", "", 0);
    }

    /**
     * Returns a new Novel with detail fields merged from the detail page.
     * The {@code siteId} is always preserved from this instance.
     */
    public Novel withDetails(String synopsis, String altNames, List<String> genres,
                             String type, String origin, String views, int favorites,
                             String coverUrl, String author, String status,
                             int totalChapters) {
        return new Novel(siteId, slug, title,
                author.isBlank() ? this.author : author,
                status.isBlank() ? this.status : status,
                totalChapters > 0 ? totalChapters : this.totalChapters,
                coverUrl.isBlank() ? this.coverUrl : coverUrl,
                url, synopsis, altNames, genres, type, origin, views, favorites);
    }
}

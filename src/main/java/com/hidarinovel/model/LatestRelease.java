package com.hidarinovel.model;

/**
 * A novel entry from the homepage "Últimos Lançamentos" table.
 * Holds the novel name, slug, latest chapter info, and publication timestamp.
 */
public record LatestRelease(
        String novelTitle,
        String novelSlug,
        String chapterTitle,
        String chapterUrl,
        String timestamp
) {}

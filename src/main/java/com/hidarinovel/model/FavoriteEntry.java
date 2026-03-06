package com.hidarinovel.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Represents a novel saved in the local favorites store.
 * Tracks the siteId, slug, title, last known chapter count, and timestamps.
 *
 * <p>The {@code @JsonCreator} factory method provides backward compatibility:
 * existing favorites.json files without a {@code siteId} field will default
 * to "hidarinovel".
 */
public record FavoriteEntry(
        String siteId,
        String slug,
        String title,
        String author,
        String coverUrl,
        String url,
        int lastKnownChapters,
        int lastDownloadedChapter,
        Instant addedAt,
        Instant lastCheckedAt
) {

    /**
     * Jackson deserializer — defaults {@code siteId} to "hidarinovel" when the
     * field is absent (backward compatibility with pre-multi-site favorites).
     */
    @JsonCreator
    public static FavoriteEntry create(
            @JsonProperty("siteId")                String siteId,
            @JsonProperty("slug")                  String slug,
            @JsonProperty("title")                 String title,
            @JsonProperty("author")                String author,
            @JsonProperty("coverUrl")              String coverUrl,
            @JsonProperty("url")                   String url,
            @JsonProperty("lastKnownChapters")     int lastKnownChapters,
            @JsonProperty("lastDownloadedChapter") int lastDownloadedChapter,
            @JsonProperty("addedAt")               Instant addedAt,
            @JsonProperty("lastCheckedAt")         Instant lastCheckedAt) {
        return new FavoriteEntry(
                siteId != null ? siteId : "hidarinovel",
                slug, title, author, coverUrl, url,
                lastKnownChapters, lastDownloadedChapter, addedAt, lastCheckedAt);
    }

    /** Creates a FavoriteEntry from a fully loaded novel. */
    public static FavoriteEntry from(Novel novel) {
        return new FavoriteEntry(
                novel.siteId(), novel.slug(), novel.title(), novel.author(),
                novel.coverUrl(), novel.url(),
                novel.totalChapters(), 0,
                Instant.now(), Instant.now());
    }

    /** Returns a copy with updated chapter tracking. */
    public FavoriteEntry withChapterUpdate(int totalChapters, int lastDownloaded) {
        return new FavoriteEntry(siteId, slug, title, author, coverUrl, url,
                totalChapters,
                lastDownloaded > 0 ? lastDownloaded : lastDownloadedChapter,
                addedAt, Instant.now());
    }

    /** Returns a copy with the last downloaded chapter updated. */
    public FavoriteEntry withLastDownloaded(int chapter) {
        return new FavoriteEntry(siteId, slug, title, author, coverUrl, url,
                lastKnownChapters, chapter, addedAt, lastCheckedAt);
    }
}

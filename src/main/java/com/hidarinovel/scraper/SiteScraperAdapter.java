package com.hidarinovel.scraper;

import com.hidarinovel.model.Chapter;
import com.hidarinovel.model.ChapterContent;
import com.hidarinovel.model.Novel;
import com.hidarinovel.model.SearchFilter;
import com.hidarinovel.model.Volume;

import java.io.IOException;
import java.util.List;

/**
 * Contract that every site scraper must implement.
 *
 * <p>To add a new site: create a Spring {@code @Component} that implements
 * this interface. It will be automatically discovered by {@link ScraperRegistry}.
 */
public interface SiteScraperAdapter {

    /** Stable identifier used as {@link Novel#siteId()} (e.g. "hidarinovel", "novelsbr"). */
    String siteId();

    /** Human-readable name shown in the CLI (e.g. "HidariNovel", "Novels BR"). */
    String displayName();

    /**
     * Searches for novels matching {@code query} with optional filters.
     * Every returned {@link Novel} must have {@link Novel#siteId()} set to
     * this adapter's {@link #siteId()}.
     */
    List<Novel> search(String query, SearchFilter filter) throws IOException;

    /**
     * Fetches the novel detail page and returns a fully populated {@link Novel}.
     * {@code partial} already has {@code slug} and {@code siteId} set.
     */
    Novel getNovelDetails(Novel partial) throws IOException;

    /** Returns all volumes with their chapter lists for the given slug. */
    List<Volume> getVolumes(String slug) throws IOException;

    /** Fetches and returns the full content of a chapter. */
    ChapterContent getChapterContent(Chapter chapter) throws IOException;

    /**
     * Downloads the cover image and returns raw bytes, or {@code null} on failure.
     */
    byte[] downloadCoverImage(String coverUrl);
}

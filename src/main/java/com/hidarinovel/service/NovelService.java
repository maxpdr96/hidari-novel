package com.hidarinovel.service;

import com.hidarinovel.export.EpubBuilder;
import com.hidarinovel.export.ExportFormat;
import com.hidarinovel.export.PdfExporter;
import com.hidarinovel.model.*;
import com.hidarinovel.scraper.ScraperRegistry;
import com.hidarinovel.scraper.SiteScraperAdapter;
import com.hidarinovel.store.FavoritesStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Central service managing interactive session state and orchestrating
 * scraping and exporting across all registered site adapters.
 */
@Service
public class NovelService {

    private static final Logger log = LoggerFactory.getLogger(NovelService.class);

    @Value("${hidarinovel.output-dir:${user.home}/Downloads/hidarinovel}")
    private String defaultOutputDir;

    private final ScraperRegistry  registry;
    private final PdfExporter      pdfExporter;
    private final EpubBuilder      epubBuilder;
    private final FavoritesStore   favoritesStore;

    // ── Session state ──────────────────────────────────────────────────────────
    private List<Novel>        searchResults  = Collections.emptyList();
    private Novel              currentNovel   = null;
    private SiteScraperAdapter currentScraper = null;
    private List<Volume>       volumes        = Collections.emptyList();
    private List<Chapter>      allChapters    = Collections.emptyList();
    private byte[]             coverImage     = null;

    public NovelService(ScraperRegistry registry, PdfExporter pdfExporter,
                        EpubBuilder epubBuilder, FavoritesStore favoritesStore) {
        this.registry       = registry;
        this.pdfExporter    = pdfExporter;
        this.epubBuilder    = epubBuilder;
        this.favoritesStore = favoritesStore;
    }

    // ── Search ────────────────────────────────────────────────────────────────

    public List<Novel> search(String query) throws IOException {
        return search(query, SearchFilter.empty());
    }

    /** Searches all registered sites and returns combined results. */
    public List<Novel> search(String query, SearchFilter filter) throws IOException {
        searchResults  = registry.searchAll(query, filter);
        currentNovel   = null;
        currentScraper = null;
        volumes        = Collections.emptyList();
        allChapters    = Collections.emptyList();
        coverImage     = null;
        return searchResults;
    }

    public List<Novel> getSearchResults() { return searchResults; }

    // ── Novel selection ───────────────────────────────────────────────────────

    /**
     * Selects result {@code index} (1-based) from the last search,
     * loads full details and volumes via the appropriate site adapter.
     */
    public Optional<Novel> selectNovel(int index) throws IOException {
        if (index < 1 || index > searchResults.size()) return Optional.empty();
        return selectNovelByPartial(searchResults.get(index - 1));
    }

    /**
     * Loads full details and volumes for {@code partial}, using the adapter
     * identified by {@code partial.siteId()}.
     */
    public Optional<Novel> selectNovelByPartial(Novel partial) throws IOException {
        SiteScraperAdapter adapter = registry.get(partial.siteId());
        Novel chosen    = adapter.getNovelDetails(partial);
        volumes         = adapter.getVolumes(chosen.slug());
        allChapters     = volumes.stream().flatMap(v -> v.chapters().stream()).toList();
        currentNovel    = chosen;
        currentScraper  = adapter;
        coverImage      = null;
        return Optional.of(currentNovel);
    }

    /**
     * Selects a novel by slug + siteId (used when restoring from favorites).
     */
    public Optional<Novel> selectNovelBySlug(String slug, String siteId) throws IOException {
        SiteScraperAdapter adapter = registry.get(siteId);
        Novel partial = new Novel(siteId, slug, "", "", "", 0, "",
                switch (adapter.siteId()) {
                    case "hidarinovel"   -> "https://hidarinovel.com.br/novels/" + slug;
                    case "centralnovel" -> "https://centralnovel.com/series/" + slug + "/";
                    default             -> "https://novels-br.com/novels/" + slug + "/";
                });
        return selectNovelByPartial(partial);
    }

    /** Selects a novel from a saved favorite entry. */
    public Optional<Novel> selectNovelByFavorite(FavoriteEntry entry) throws IOException {
        Novel partial = new Novel(entry.siteId(), entry.slug(), entry.title(),
                entry.author(), "", 0, entry.coverUrl(), entry.url());
        return selectNovelByPartial(partial);
    }

    public Optional<Novel>  getCurrentNovel()  { return Optional.ofNullable(currentNovel); }
    public List<Volume>     getVolumes()        { return volumes; }
    public List<Chapter>    getAllChapters()     { return allChapters; }

    /** Returns the cover image bytes, downloading lazily on first access. */
    public byte[] getCoverImage() {
        if (coverImage == null && currentNovel != null
                && !currentNovel.coverUrl().isBlank() && currentScraper != null) {
            coverImage = currentScraper.downloadCoverImage(currentNovel.coverUrl());
        }
        return coverImage;
    }

    // ── Chapter lookup ────────────────────────────────────────────────────────

    public List<Chapter> getRange(int from, int to) {
        return allChapters.stream()
                .filter(c -> c.globalNumber() >= from && c.globalNumber() <= to)
                .toList();
    }

    public Optional<Volume> getVolume(int number) {
        return volumes.stream().filter(v -> v.number() == number).findFirst();
    }

    // ── Discovery (HidariNovel-specific) ───────────────────────────────────────

    public List<Novel> getNew() throws IOException {
        var nm = novelManiaAdapter();
        searchResults = nm.getNew();
        return searchResults;
    }

    public List<Novel> getTrending() throws IOException {
        var nm = novelManiaAdapter();
        searchResults = nm.getTrending();
        return searchResults;
    }

    public List<Novel> getPopular() throws IOException {
        var nm = novelManiaAdapter();
        searchResults = nm.getPopular();
        return searchResults;
    }

    public List<LatestRelease> getLatestReleases() throws IOException {
        return novelManiaAdapter().getLatestReleases();
    }

    // ── Favorites ─────────────────────────────────────────────────────────────

    public FavoritesStore getFavoritesStore() { return favoritesStore; }

    public boolean addCurrentToFavorites() {
        if (currentNovel == null) return false;
        favoritesStore.add(FavoriteEntry.from(currentNovel));
        return true;
    }

    /**
     * Checks if a favorited novel has new chapters since it was last tracked.
     * Uses the site adapter matching the entry's {@code siteId}.
     */
    public int checkForUpdates(FavoriteEntry entry) throws IOException {
        Novel partial = new Novel(entry.siteId(), entry.slug(), entry.title(),
                entry.author(), "", 0, entry.coverUrl(), entry.url());
        SiteScraperAdapter adapter = registry.get(entry.siteId());
        Novel details = adapter.getNovelDetails(partial);
        int newChapters = details.totalChapters() - entry.lastKnownChapters();
        favoritesStore.update(entry.withChapterUpdate(details.totalChapters(), 0));
        return Math.max(0, newChapters);
    }

    // ── Download & export ─────────────────────────────────────────────────────

    public List<Path> download(int from, int to, ExportFormat format, boolean combine,
                                Path outputDir, Consumer<String> progress)
            throws IOException {

        if (currentNovel == null || currentScraper == null)
            throw new IllegalStateException("No novel selected.");

        List<Chapter> range = getRange(from, to);
        if (range.isEmpty())
            throw new IllegalArgumentException("No chapters found in range %d–%d.".formatted(from, to));

        List<ChapterContent> contents = fetchContents(range, progress);
        if (contents.isEmpty()) throw new IOException("All chapter fetches failed.");

        updateFavoriteLastDownloaded(to);

        Path dir = resolveOutputDir(outputDir);
        return switch (format) {
            case PDF  -> exportPdf(contents, dir, combine, from, to);
            case EPUB -> exportEpub(contents, dir, combine, from, to);
        };
    }

    public Path downloadVolume(Volume vol, ExportFormat format, Path outputDir,
                                Consumer<String> progress) throws IOException {
        if (currentNovel == null || currentScraper == null)
            throw new IllegalStateException("No novel selected.");

        List<Chapter> chapters = vol.chapters();
        if (chapters.isEmpty())
            throw new IllegalArgumentException("Volume %d has no chapters.".formatted(vol.number()));

        List<ChapterContent> contents = fetchContents(chapters, progress);
        if (contents.isEmpty()) throw new IOException("All chapter fetches failed.");

        updateFavoriteLastDownloaded(vol.lastGlobal());

        Path dir   = resolveOutputDir(outputDir);
        String ext = format.name().toLowerCase();
        Path file  = dir.resolve("%s-volume-%02d.%s"
                .formatted(currentNovel.slug(), vol.number(), ext));
        String epubTitle = currentNovel.title() + " — " + vol.title();

        switch (format) {
            case PDF  -> pdfExporter.exportCombined(contents, file);
            case EPUB -> epubBuilder.exportCombined(contents, file, epubTitle,
                    currentNovel.author(), getCoverImage());
        }
        return file;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<ChapterContent> fetchContents(List<Chapter> chapters,
                                               Consumer<String> progress) {
        List<ChapterContent> contents = new ArrayList<>();
        for (Chapter ch : chapters) {
            progress.accept("  [%d/%d] %s".formatted(
                    contents.size() + 1, chapters.size(), ch.shortLabel()));
            try {
                contents.add(currentScraper.getChapterContent(ch));
            } catch (IOException e) {
                log.error("Failed to fetch {}: {}", ch.url(), e.getMessage());
                progress.accept("  ✗ Failed: " + ch.shortLabel());
            }
        }
        return contents;
    }

    private List<Path> exportPdf(List<ChapterContent> contents, Path dir,
                                  boolean combine, int from, int to) throws IOException {
        if (combine) {
            Path file = dir.resolve(combinedFilename(from, to, "pdf"));
            pdfExporter.exportCombined(contents, file);
            return List.of(file);
        }
        return pdfExporter.exportPerChapter(contents, dir);
    }

    private List<Path> exportEpub(List<ChapterContent> contents, Path dir,
                                   boolean combine, int from, int to) throws IOException {
        String title  = currentNovel.title();
        String author = currentNovel.author();
        byte[] cover  = getCoverImage();
        if (combine) {
            Path file = dir.resolve(combinedFilename(from, to, "epub"));
            epubBuilder.exportCombined(contents, file, title, author, cover);
            return List.of(file);
        }
        return epubBuilder.exportPerChapter(contents, dir, title, author, cover);
    }

    private void updateFavoriteLastDownloaded(int lastChapter) {
        if (currentNovel == null) return;
        favoritesStore.getBySlug(currentNovel.slug()).ifPresent(entry ->
                favoritesStore.update(entry.withLastDownloaded(lastChapter)));
    }

    private Path resolveOutputDir(Path override) {
        Path base = override != null ? override : Path.of(defaultOutputDir);
        return base.resolve(currentNovel.slug());
    }

    private String combinedFilename(int from, int to, String ext) {
        return "%s-caps-%04d-%04d.%s".formatted(currentNovel.slug(), from, to, ext);
    }

    /**
     * Returns the HidariNovel adapter (for discovery methods that are site-specific).
     * Cast is safe because NovelManiaScraper exposes the extra methods.
     */
    private com.hidarinovel.scraper.NovelManiaScraper novelManiaAdapter() {
        return (com.hidarinovel.scraper.NovelManiaScraper) registry.get("novelmania");
    }
}

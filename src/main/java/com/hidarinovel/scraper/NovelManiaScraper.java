package com.hidarinovel.scraper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hidarinovel.model.Chapter;
import com.hidarinovel.model.ChapterContent;
import com.hidarinovel.model.LatestRelease;
import com.hidarinovel.model.Novel;
import com.hidarinovel.model.SearchFilter;
import com.hidarinovel.model.Volume;

/**
 * Scrapes novelmania.com.br using JSoup.
 *
 * <p>
 * The site is server-side rendered — no JavaScript execution is required.
 * A configurable throttle delay is applied before each chapter fetch to avoid
 * overloading the server.
 */
@Component
public class NovelManiaScraper implements SiteScraperAdapter {

    @Override
    public String siteId() { return "novelmania"; }

    @Override
    public String displayName() { return "NovelMania"; }

    private static final Logger log = LoggerFactory.getLogger(NovelManiaScraper.class);

    private static final String BASE_URL = "https://novelmania.com.br";
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MS = 20_000;

    // Known volume/book keywords → volume number (ignores year groupings like
    // "ano")
    private static final Pattern VOL_PAT = Pattern.compile(
            "(?:volume|livro|tomo|parte|temporada)-(\\d+)");
    // Fallback: first word-number pair after /capitulos/
    private static final Pattern VOL_FALLBACK_PAT = Pattern.compile("/capitulos/\\w+-(\\d+)");
    // Explicit chapter number (also handles site typo "capiulo")
    private static final Pattern CHAP_NUM_PAT = Pattern.compile("capit?ulo-(\\d+)");
    // Special chapter types (numbered or not: prologo-2, extra-1, intermissao-3,
    // ss-1, bonus, conto, …)
    private static final Pattern SPECIAL_CHAP_PAT = Pattern.compile(
            "prologo|epilogo|monologo|extra|intermissao|ss|bonus|conto|spin-off|agradecimentos|lista-de-personagens|teste");

    @Value("${novelmania.throttle-ms:800}")
    private long throttleMs;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Searches for novels matching {@code query} by title (no filters).
     */
    public List<Novel> search(String query) throws IOException {
        return search(query, SearchFilter.empty());
    }

    /**
     * Searches for novels matching {@code query} with optional filters.
     * Returns deduplicated results (the page may repeat the same novel in
     * multiple card elements).
     */
    public List<Novel> search(String query, SearchFilter filter) throws IOException {
        String url = BASE_URL + "/novels?titulo="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + filter.toQueryParams();
        log.debug("Searching: {}", url);
        Document doc = fetch(url);

        Map<String, Novel> seen = new LinkedHashMap<>();

        for (Element titleAnchor : doc.select("a.novel-title")) {
            String href = titleAnchor.attr("href");
            String title = titleAnchor.select("h5").text().trim();
            if (title.isBlank() || href.isBlank())
                continue;

            String slug = href.replaceFirst("^/novels/", "");
            if (seen.containsKey(slug))
                continue; // deduplicate

            // Sibling column holds the cover image
            Element row = titleAnchor.closest(".row");
            String coverUrl = "";
            String author = "";
            String status = "";
            int totalChapters = 0;

            if (row != null) {
                Element img = row.selectFirst("img.card-image");
                if (img != null)
                    coverUrl = img.absUrl("src");

                Element authorEl = row.selectFirst(".author");
                if (authorEl != null)
                    author = authorEl.text().trim();

                // Chapter count is shown as an <a> with a book icon
                Element chapEl = row.selectFirst("a[title='Número de capítulos']");
                if (chapEl != null) {
                    String chapText = chapEl.text().replaceAll("\\D", "").trim();
                    if (!chapText.isEmpty()) {
                        try {
                            totalChapters = Integer.parseInt(chapText);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }

            seen.put(slug, new Novel(siteId(), slug, title, author, status, totalChapters,
                    coverUrl, BASE_URL + href));
        }

        return new ArrayList<>(seen.values());
    }

    // ── Novel details ─────────────────────────────────────────────────────────

    /**
     * Fetches the novel detail page and extracts full metadata:
     * synopsis, alt names, genres, type, origin, views, favorites, cover, author,
     * status.
     *
     * @return a fully populated Novel record
     */
    public Novel getNovelDetails(Novel partial) throws IOException {
        String url = BASE_URL + "/novels/" + partial.slug();
        log.debug("Loading novel details: {}", url);
        Document doc = fetch(url);

        // Type (e.g. "Webnovel", "Light Novel")
        String type = "";
        Element sigla = doc.selectFirst(".novel-info span.sigla");
        if (sigla != null)
            type = sigla.text().trim();

        // Alt names
        String altNames = "";
        Element altEl = doc.selectFirst("h2.sub-titles");
        if (altEl != null)
            altNames = altEl.text().trim();

        // Metadata list: origin, chapters, favorites, views
        String origin = "";
        String views = "";
        int totalChapters = 0;
        int favorites = 0;

        for (Element li : doc.select(".novel-info ul.list-inline li a")) {
            String liTitle = li.attr("title");
            String liText = li.text().trim();
            switch (liTitle) {
                case "Nacionalidade" -> origin = liText;
                case "Número de capítulos" -> {
                    String num = liText.replaceAll("\\D", "");
                    if (!num.isEmpty())
                        totalChapters = Integer.parseInt(num);
                }
                case "Número de favoritos" -> {
                    String num = liText.replaceAll("\\D", "");
                    if (!num.isEmpty())
                        favorites = Integer.parseInt(num);
                }
                case "Número de visualizações" -> views = liText;
            }
        }

        // Author and status
        String author = "";
        String status = "";
        for (Element span : doc.select(".novel-info span.authors")) {
            String text = span.text().trim();
            if (text.startsWith("Autor:")) {
                author = text.replaceFirst("^Autor:\\s*", "").trim();
            } else if (text.startsWith("Status:")) {
                status = text.replaceFirst("^Status:\\s*", "").trim();
            }
        }

        // Synopsis — paragraphs after <h4>Sinopse</h4> until next <h4>
        var synopsisBuilder = new StringBuilder();
        Element textDiv = doc.selectFirst(".content-novel .text");
        if (textDiv != null) {
            boolean capturing = false;
            for (Element child : textDiv.children()) {
                if (child.tagName().equals("h4") && child.text().contains("Sinopse")) {
                    capturing = true;
                    continue;
                }
                if (capturing) {
                    if (child.tagName().equals("h4"))
                        break; // next section
                    if (!synopsisBuilder.isEmpty())
                        synopsisBuilder.append('\n');
                    synopsisBuilder.append(child.text().trim());
                }
            }
        }

        // Cover image
        String coverUrl = "";
        Element coverImg = doc.selectFirst("img.img-responsive[alt*='Capa']");
        if (coverImg != null)
            coverUrl = coverImg.absUrl("src");

        // Genres
        List<String> genres = new ArrayList<>();
        for (Element a : doc.select(".tags .list-tags li a")) {
            String genre = a.attr("title").trim();
            if (!genre.isBlank())
                genres.add(genre);
        }

        return partial.withDetails(synopsisBuilder.toString(), altNames, genres,
                type, origin, views, favorites, coverUrl, author, status, totalChapters);
    }

    // ── Volumes & chapters ────────────────────────────────────────────────────

    /**
     * Parses the novel page and returns all volumes with their chapter lists.
     * Chapter global numbers are assigned sequentially (1-based) across all
     * volumes in the order they appear on the page.
     * Chapter titles are extracted from the {@code <strong>} text in the listing.
     */
    public List<Volume> getVolumes(String novelSlug) throws IOException {
        String url = BASE_URL + "/novels/" + novelSlug;
        log.debug("Loading chapters: {}", url);
        Document doc = fetch(url);

        List<Volume> volumes = new ArrayList<>();
        int globalNum = 0;

        Elements volButtons = doc.select(".accordion.capitulo .btn.btn-link");
        for (int vi = 0; vi < volButtons.size(); vi++) {
            Element btn = volButtons.get(vi);
            String volTitle = btn.text().trim();
            String targetId = btn.attr("data-target").replace("#", "");

            Element panel = doc.getElementById(targetId);
            if (panel == null) {
                log.warn("Volume panel '{}' not found in DOM", targetId);
                continue;
            }

            int volNum = vi + 1;
            List<Chapter> chapters = new ArrayList<>();

            for (Element link : panel.select("a[href*='/capitulos/']")) {
                String href = link.attr("href");
                int[] coords = parseChapterUrl(href);
                if (coords == null) {
                    log.warn("Could not parse chapter URL: {}", href);
                    continue;
                }

                // Extract real chapter title from <strong> tag
                String chTitle = "";
                Element strong = link.selectFirst("strong");
                if (strong != null) {
                    chTitle = strong.text().trim();
                }

                globalNum++;
                chapters.add(new Chapter(globalNum, coords[1], volNum, volTitle,
                        chTitle, BASE_URL + href));
            }

            if (!chapters.isEmpty()) {
                volumes.add(new Volume(volNum, volTitle, chapters));
            }
        }

        return volumes;
    }

    // ── Chapter content ───────────────────────────────────────────────────────

    /**
     * Fetches a chapter page and returns its cleaned, image-inlined content.
     *
     * <p>
     * A throttle delay is applied before the request. Images found inside the
     * chapter body are downloaded and embedded as {@code data:} URIs so that
     * exporters can work offline.
     */
    public ChapterContent getChapterContent(Chapter chapter) throws IOException {
        throttle();
        log.debug("Fetching chapter: {}", chapter.url());
        Document doc = fetch(chapter.url());

        // Volume label (h3) and chapter title (h2)
        Element content = doc.getElementById("chapter-content");
        if (content == null) {
            throw new IOException("Chapter content not found at: " + chapter.url());
        }

        String volumeLabel = "";
        Element h3 = content.selectFirst("h3");
        if (h3 != null) {
            volumeLabel = h3.text().trim();
            h3.remove();
        }

        String chapterTitle = chapter.shortLabel();
        Element h2 = content.selectFirst("h2");
        if (h2 != null) {
            chapterTitle = h2.text().trim();
            // Keep h2 in content for rendering
        }

        // Remove non-content elements
        content.select(".donation-section, .pagination, .nav-chapter, " +
                "[class*='comment'], [class*='ads'], [class*='sponsor']").remove();

        // Also strip any stray <link>, <script>, <style> tags inside the body content
        content.select("link, script, style").remove();

        // Download images and replace src with data URIs
        inlineImages(content, chapter.url());

        // Switch to XML/XHTML output mode before serialising:
        // • void elements become self-closing (<br> → <br />, <link …> → <link … />)
        // • named HTML entities are replaced by numeric character refs
        // (&nbsp; → &#xa0;, &mdash; → &#x2014;, etc.) — required for valid XML
        doc.outputSettings()
                .syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml)
                .escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml)
                .charset(java.nio.charset.StandardCharsets.UTF_8);

        return new ChapterContent(chapter, chapterTitle, volumeLabel, content.html());
    }

    // ── Cover image download ──────────────────────────────────────────────────

    /**
     * Downloads the cover image from the given URL and returns the raw bytes.
     * Returns null on failure.
     */
    public byte[] downloadCoverImage(String coverUrl) {
        if (coverUrl == null || coverUrl.isBlank())
            return null;
        try {
            return downloadBytes(coverUrl, BASE_URL);
        } catch (Exception e) {
            log.warn("Could not download cover image {}: {}", coverUrl, e.getMessage());
            return null;
        }
    }

    // ── Image inlining ────────────────────────────────────────────────────────

    /**
     * Finds all {@code <img>} elements in {@code root}, downloads each image,
     * and replaces the {@code src} attribute with a base64 {@code data:} URI.
     * Failures are logged and the original src is left unchanged.
     */
    private void inlineImages(Element root, String pageUrl) {
        for (Element img : root.select("img[src]")) {
            String src = img.absUrl("src");
            if (src.isBlank() || src.startsWith("data:"))
                continue;
            try {
                throttle();
                byte[] bytes = downloadBytes(src, pageUrl);
                String mime = guessMime(src);
                String dataUri = "data:" + mime + ";base64,"
                        + Base64.getEncoder().encodeToString(bytes);
                img.attr("src", dataUri);
                log.debug("Inlined image: {}", src);
            } catch (Exception e) {
                log.warn("Could not inline image {}: {}", src, e.getMessage());
            }
        }
    }

    private byte[] downloadBytes(String url, String referer)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }

    private static String guessMime(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".png"))
            return "image/png";
        if (lower.contains(".gif"))
            return "image/gif";
        if (lower.contains(".webp"))
            return "image/webp";
        return "image/jpeg";
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private Document fetch(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .referrer(BASE_URL)
                .timeout(TIMEOUT_MS)
                .get();
    }

    /**
     * Returns [volumeNumber, capituloNumber] parsed from the URL slug, or null.
     * Known volume keywords (volume, livro, tomo, …) are tried first; falls back
     * to the first word-number pair after /capitulos/. "Ano" groupings are ignored.
     * Sections without a volume number (e.g. "introducao-capitulo-N") use volNum=0.
     * Special chapters (prologo, epilogo, monologo, extra, intermissao, ss —
     * numbered
     * or not) return capituloNumber = 0.
     */
    private static int[] parseChapterUrl(String url) {
        // Prefer known volume-type keywords so "ano-N-volume-N" resolves to the volume
        // number
        int volNum = 0;
        Matcher volM = VOL_PAT.matcher(url);
        if (volM.find()) {
            volNum = Integer.parseInt(volM.group(1));
        } else {
            Matcher fbM = VOL_FALLBACK_PAT.matcher(url);
            if (fbM.find()) {
                volNum = Integer.parseInt(fbM.group(1));
            }
            // else: no volume number found; volNum stays 0 (e.g. "introducao-capitulo-N")
        }

        Matcher chapM = CHAP_NUM_PAT.matcher(url);
        if (chapM.find()) {
            return new int[] { volNum, Integer.parseInt(chapM.group(1)) };
        }

        if (SPECIAL_CHAP_PAT.matcher(url).find()) {
            return new int[] { volNum, 0 };
        }

        return null;
    }

    // ── Homepage discovery ─────────────────────────────────────────────────────

    /**
     * Fetches the homepage and parses the "Novidades" (new novels) section.
     */
    public List<Novel> getNew() throws IOException {
        Document doc = fetch(BASE_URL);
        return parseHomepageSlider(doc, "Novidades");
    }

    /**
     * Fetches the homepage and parses the "Em alta" (trending) section.
     */
    public List<Novel> getTrending() throws IOException {
        Document doc = fetch(BASE_URL);
        return parseHomepageSlider(doc, "Em alta");
    }

    /**
     * Fetches the homepage and parses the "Populares" section.
     */
    public List<Novel> getPopular() throws IOException {
        Document doc = fetch(BASE_URL);
        List<Novel> novels = new ArrayList<>();

        for (Element card : doc.select(".popular-novels__card")) {
            Element titleLink = card.selectFirst("a.popular-novels__title-link");
            if (titleLink == null)
                continue;

            String title = titleLink.text().trim();
            String href = titleLink.attr("href");
            String slug = href.replaceFirst("^/novels/", "");
            if (title.isBlank() || slug.isBlank())
                continue;

            String coverUrl = "";
            Element img = card.selectFirst("img.popular-novels__cover");
            if (img != null)
                coverUrl = img.absUrl("src");

            String author = "";
            Element authorEl = card.selectFirst(".popular-novels__author-name");
            if (authorEl != null)
                author = authorEl.text().trim();

            novels.add(new Novel(siteId(), slug, title, author, "", 0, coverUrl, BASE_URL + href));
        }
        return novels;
    }

    /**
     * Fetches the homepage and parses the "Últimos Lançamentos" table.
     */
    public List<LatestRelease> getLatestReleases() throws IOException {
        Document doc = fetch(BASE_URL);
        List<LatestRelease> releases = new ArrayList<>();

        for (Element row : doc.select(".latest-releases__row")) {
            Element novelLink = row.selectFirst("a.latest-releases__novel-link");
            if (novelLink == null)
                continue;

            String novelTitle = novelLink.text().trim();
            String novelSlug = novelLink.attr("href").replaceFirst("^/novels/", "");

            // Chapter info from the desktop column
            String chapterTitle = "";
            String chapterUrl = "";
            Element chapLink = row.selectFirst("td.latest-releases__cell--chapter a.latest-releases__chapter-link");
            if (chapLink != null) {
                chapterTitle = chapLink.attr("title").trim();
                if (chapterTitle.isBlank())
                    chapterTitle = chapLink.text().trim();
                chapterUrl = BASE_URL + chapLink.attr("href");
            }

            String timestamp = "";
            Element timeEl = row.selectFirst("td.latest-releases__cell--published[data-timestamp]");
            if (timeEl != null)
                timestamp = timeEl.attr("data-timestamp");

            releases.add(new LatestRelease(novelTitle, novelSlug, chapterTitle, chapterUrl, timestamp));
        }
        return releases;
    }

    /**
     * Parses a swiper-based slider section on the homepage identified by
     * sectionTitle.
     * Both "Novidades" and "Em alta" use the same card structure.
     */
    private List<Novel> parseHomepageSlider(Document doc, String sectionTitle) {
        List<Novel> novels = new ArrayList<>();

        // Find the h1 with the section title, then navigate to its containing section
        for (Element h1 : doc.select("h1.title")) {
            if (!h1.text().trim().equalsIgnoreCase(sectionTitle))
                continue;

            // The swiper container is a sibling in the same row/section
            Element section = h1.closest(".row, section, .container");
            if (section == null)
                section = h1.parent();

            for (Element card : section.select(".card.novel")) {
                Element link = card.selectFirst("a.link");
                if (link == null)
                    continue;

                String href = link.attr("href");
                String slug = href.replaceFirst("^/novels/", "");

                String title = "";
                Element titleEl = card.selectFirst("h2.card-title");
                if (titleEl != null)
                    title = titleEl.text().trim();

                String coverUrl = "";
                Element img = card.selectFirst("img.card-image");
                if (img != null)
                    coverUrl = img.absUrl("src");

                if (!title.isBlank() && !slug.isBlank()) {
                    novels.add(new Novel(siteId(), slug, title, "", "", 0, coverUrl, BASE_URL + href));
                }
            }
            break; // found the right section
        }
        return novels;
    }

    private void throttle() {
        if (throttleMs <= 0)
            return;
        try {
            Thread.sleep(throttleMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.hidarinovel.scraper;

import com.hidarinovel.model.Chapter;
import com.hidarinovel.model.ChapterContent;
import com.hidarinovel.model.Novel;
import com.hidarinovel.model.SearchFilter;
import com.hidarinovel.model.Volume;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scraper for novels-br.com.
 *
 * <p>The site does not expose a URL-based search endpoint, so search is
 * implemented by fetching paginated listing pages and filtering by title
 * client-side. The listing is alphabetical with ~15 novels per page.
 *
 * <p>Chapter URLs follow the pattern:
 * {@code /novels/[slug]/livro-N-capitulo-M--chapter-title}
 */
@Component
public class NovelsBrScraper implements SiteScraperAdapter {

    private static final Logger log = LoggerFactory.getLogger(NovelsBrScraper.class);

    static final String BASE_URL    = "https://novels-br.com";
    private static final int TIMEOUT_MS = 20_000;

    private final UserAgentProvider userAgentProvider;

    /** Max search result pages to fetch (site returns ~16 results/page). */
    private static final int MAX_SEARCH_PAGES = 5;

    // Chapter URL patterns (same logic as NovelManiaScraper, no /capitulos/ prefix)
    private static final Pattern VOL_PAT = Pattern.compile(
            "(?:volume|livro|tomo|parte|temporada)-(\\d+)");
    private static final Pattern CHAP_NUM_PAT = Pattern.compile("capit?ulo-(\\d+)");
    private static final Pattern SPECIAL_CHAP_PAT = Pattern.compile(
            "prologo|epilogo|monologo|extra|intermissao|ss|bonus|conto|spin-off");

    @Value("${hidarinovel.throttle-ms:800}")
    private long throttleMs;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public NovelsBrScraper(UserAgentProvider userAgentProvider) {
        this.userAgentProvider = userAgentProvider;
    }

    @Override public String siteId()       { return "novelsbr"; }
    @Override public String displayName()  { return "Novels BR"; }

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Searches using the site's {@code /novels?simplifiedField=QUERY} endpoint.
     * Paginates through results until no more pages are found or the page limit
     * is reached.
     */
    @Override
    public List<Novel> search(String query, SearchFilter filter) throws IOException {
        List<Novel> results = new ArrayList<>();

        for (int page = 0; page < MAX_SEARCH_PAGES; page++) {
            String url = BASE_URL + "/novels?simplifiedField="
                    + java.net.URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
                    + "&page=" + page;
            log.debug("Novels BR search page {}: {}", page, url);
            Document doc = fetch(url);

            List<Novel> pageNovels = parseSearchResultPage(doc);
            if (pageNovels.isEmpty()) break; // no more results

            results.addAll(pageNovels);
        }

        log.debug("Novels BR search '{}': {} result(s)", query, results.size());
        return results;
    }

    /**
     * Parses the search results page.
     *
     * <p>Each result card has the structure:
     * <pre>
     *   &lt;img src="/s3/[slug].jpeg" title="[Title]"&gt;
     *   &lt;h2&gt;[Title]&lt;/h2&gt;
     *   &lt;h3&gt;[Author]&lt;/h3&gt;
     *   &lt;h4&gt;[Genre - Origin]&lt;/h4&gt;
     *   &lt;a href="/novels/[slug]"&gt;Ler Novel&lt;/a&gt;
     * </pre>
     * Search is scoped to {@code <main>} to exclude sidebar and navigation links
     * that also contain {@code /novels/[slug]} hrefs.
     */
    private List<Novel> parseSearchResultPage(Document doc) {
        List<Novel> novels = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Scope to main content — excludes header, aside, footer, nav
        Element scope = doc.selectFirst("main");
        if (scope == null) scope = doc.body();

        for (Element a : scope.select("a[href^='/novels/']")) {
            String href = a.attr("href");
            if (!href.matches("^/novels/[^/]+/?$")) continue;

            String slug = href.replaceFirst("^/novels/", "").replaceFirst("/$", "");
            if (slug.isBlank() || seen.contains(slug)) continue;
            seen.add(slug);

            Element card = a.parent();
            if (card == null) continue;

            // Title: <h2> inside the card, fall back to img title attribute
            String title = "";
            Element h2 = card.selectFirst("h2");
            if (h2 != null) title = h2.text().trim();
            if (title.isBlank()) {
                Element img = card.selectFirst("img[title]");
                if (img != null) title = img.attr("title").trim();
            }
            if (title.isBlank()) title = slug.replace("-", " ");

            // Author: <h3> inside the card
            String author = "";
            Element h3 = card.selectFirst("h3");
            if (h3 != null) author = h3.text().trim();

            // Cover image
            String coverUrl = "";
            Element img = card.selectFirst("img[src*='/s3/'], img[src*='" + slug + "']");
            if (img == null) img = card.selectFirst("img");
            if (img != null) coverUrl = img.absUrl("src");

            novels.add(new Novel(siteId(), slug, title, author, "", 0,
                    coverUrl, BASE_URL + "/novels/" + slug + "/"));
        }
        return novels;
    }

    // ── Novel details ─────────────────────────────────────────────────────────

    @Override
    public Novel getNovelDetails(Novel partial) throws IOException {
        String url = BASE_URL + "/novels/" + partial.slug() + "/";
        log.debug("Novels BR detail: {}", url);
        Document doc = fetch(url);

        // Title (prefer h1 on the page over slug-derived title)
        String title = partial.title();
        Element h1 = doc.selectFirst("h1");
        if (h1 != null && !h1.text().isBlank()) title = h1.text().trim();

        // Synopsis — try several common selectors
        String synopsis = "";
        for (String sel : new String[]{
                ".summary__content p", ".entry-content p", ".novel-description p",
                ".sinopse p", ".synopsis p", ".description p",
                ".tab-summary p", ".summary-content p"}) {
            Elements ps = doc.select(sel);
            if (!ps.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Element p : ps) {
                    if (!p.text().isBlank()) {
                        if (!sb.isEmpty()) sb.append('\n');
                        sb.append(p.text().trim());
                    }
                }
                if (!sb.isEmpty()) { synopsis = sb.toString(); break; }
            }
        }

        // Author — try metadata containers
        String author = partial.author();
        for (String sel : new String[]{
                ".author-content a", ".novel-author", "[itemprop='author']",
                ".info-meta .author", ".book-info .author"}) {
            Element el = doc.selectFirst(sel);
            if (el != null && !el.text().isBlank()) { author = el.text().trim(); break; }
        }

        // Status
        String status = partial.status();
        for (String sel : new String[]{
                ".summary-status", ".novel-status", ".status", ".book-status"}) {
            Element el = doc.selectFirst(sel);
            if (el != null && !el.text().isBlank()) { status = el.text().trim(); break; }
        }

        // Chapter count — look for spans/divs with a number near "capítulo"
        int totalChapters = partial.totalChapters();
        for (String sel : new String[]{
                ".chapter-count", ".total-chapters", ".numchap", ".chap-count"}) {
            Element el = doc.selectFirst(sel);
            if (el != null) {
                String num = el.text().replaceAll("\\D", "");
                if (!num.isEmpty()) { totalChapters = Integer.parseInt(num); break; }
            }
        }

        // Genres — look for category / tag links
        List<String> genres = new ArrayList<>();
        for (String sel : new String[]{
                "a[href*='/category/']", "a[href*='/genero/']",
                "a[href*='/genre/']", ".genres a", ".tags a", ".novel-tags a"}) {
            Elements links = doc.select(sel);
            if (!links.isEmpty()) {
                for (Element a : links) {
                    String g = a.text().trim();
                    if (!g.isBlank()) genres.add(g);
                }
                break;
            }
        }

        // Cover image
        String coverUrl = partial.coverUrl();
        for (String sel : new String[]{
                ".summary_image img", ".novel-cover img", ".book-cover img",
                "img[src*='" + partial.slug() + "']", ".thumb img", ".cover img"}) {
            Element img = doc.selectFirst(sel);
            if (img != null && !img.absUrl("src").isBlank()) {
                coverUrl = img.absUrl("src"); break;
            }
        }

        return partial.withDetails(synopsis, "", genres, "", "", "", 0,
                coverUrl, author, status, totalChapters);
    }

    // ── Volumes & chapters ────────────────────────────────────────────────────

    @Override
    public List<Volume> getVolumes(String slug) throws IOException {
        String url = BASE_URL + "/novels/" + slug + "/";
        log.debug("Novels BR chapters: {}", url);
        Document doc = fetch(url);

        // Find all links to chapter pages under this novel
        // Pattern: /novels/[slug]/[chapter-slug]  (exactly 3 path segments)
        String novelPrefix = "/novels/" + slug + "/";
        Pattern chapterHref = Pattern.compile(
                "^" + Pattern.quote(novelPrefix) + "([^/]+)$");

        // Ordered map: volNum → list of chapter Elements
        Map<Integer, List<Element>> volLinks = new LinkedHashMap<>();
        Map<Integer, String> volTitles = new LinkedHashMap<>();
        int[] globalCounter = {0};

        for (Element a : doc.select("a[href]")) {
            String href = a.attr("href").replaceFirst("/$", "");
            Matcher m = chapterHref.matcher(href);
            if (!m.matches()) continue;

            String chSlug = m.group(1);
            int[] coords = parseChapterSlug(chSlug);
            if (coords == null) {
                log.warn("Could not parse chapter slug: {}", chSlug);
                continue;
            }
            int volNum = coords[0];
            volLinks.computeIfAbsent(volNum, k -> new ArrayList<>()).add(a);
            volTitles.putIfAbsent(volNum, "Livro " + volNum);
        }

        // Try to find better volume titles from section headers on the page
        for (Element heading : doc.select("h2, h3, h4")) {
            String text = heading.text().trim();
            Matcher vm = VOL_PAT.matcher(text.toLowerCase());
            if (vm.find()) {
                int vn = Integer.parseInt(vm.group(1));
                volTitles.put(vn, text);
            }
        }

        // Build Chapter and Volume objects
        List<Volume> volumes = new ArrayList<>();
        List<Integer> sortedVols = new ArrayList<>(volLinks.keySet());
        Collections.sort(sortedVols);

        for (int volNum : sortedVols) {
            List<Element> links = volLinks.get(volNum);
            String volTitle = volTitles.getOrDefault(volNum, "Livro " + volNum);
            List<Chapter> chapters = new ArrayList<>();

            for (Element a : links) {
                String href = a.attr("href").replaceFirst("/$", "");
                Matcher m = chapterHref.matcher(href);
                if (!m.matches()) continue;

                String chSlug = m.group(1);
                int[] coords = parseChapterSlug(chSlug);
                if (coords == null) continue;

                globalCounter[0]++;
                int chapNum = coords[1];

                // Title: from link text, or embedded after "--" in slug
                String chTitle = a.text().trim();
                if (chTitle.isBlank()) chTitle = titleFromSlug(chSlug);

                chapters.add(new Chapter(globalCounter[0], chapNum, volNum,
                        volTitle, chTitle, BASE_URL + href + "/"));
            }

            if (!chapters.isEmpty()) {
                volumes.add(new Volume(volNum, volTitle, chapters));
            }
        }

        return volumes;
    }

    // ── Chapter content ───────────────────────────────────────────────────────

    @Override
    public ChapterContent getChapterContent(Chapter chapter) throws IOException {
        throttle();
        log.debug("Novels BR fetching: {}", chapter.url());
        Document doc = fetch(chapter.url());

        // Try multiple selectors for the reading content
        Element content = null;
        for (String sel : new String[]{
                "#chapter-content", ".reading-content", ".entry-content",
                ".chapter-content", ".chapter-body", ".text-left",
                "article .content", "main article", "main .content"}) {
            content = doc.selectFirst(sel);
            if (content != null) break;
        }
        if (content == null) {
            throw new IOException("Chapter content not found at: " + chapter.url());
        }

        // Remove non-content elements
        content.select(".pagination, .nav-chapter, script, style, link, "
                + "[class*='comment'], [class*='ads'], [class*='sponsor'], "
                + "[class*='navigation'], [class*='nav-']").remove();

        // Chapter title
        String chapterTitle = chapter.shortLabel();
        for (String sel : new String[]{"h1", "h2", ".chapter-title", ".entry-title"}) {
            Element h = content.selectFirst(sel);
            if (h != null && !h.text().isBlank()) { chapterTitle = h.text().trim(); break; }
        }

        // Inline images
        inlineImages(content, chapter.url());

        doc.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(Entities.EscapeMode.xhtml)
                .charset(StandardCharsets.UTF_8);

        return new ChapterContent(chapter, chapterTitle, chapter.volumeTitle(), content.html());
    }

    // ── Cover image ───────────────────────────────────────────────────────────

    @Override
    public byte[] downloadCoverImage(String coverUrl) {
        if (coverUrl == null || coverUrl.isBlank()) return null;
        try {
            return downloadBytes(coverUrl, BASE_URL);
        } catch (Exception e) {
            log.warn("Could not download cover image {}: {}", coverUrl, e.getMessage());
            return null;
        }
    }

    // ── Chapter slug parsing ──────────────────────────────────────────────────

    /**
     * Parses [volNum, chapNum] from a chapter slug like
     * {@code livro-1-capitulo-5--titulo} or {@code volume-2-prologo}.
     * Returns null if the slug cannot be parsed as a chapter.
     */
    private static int[] parseChapterSlug(String slug) {
        int volNum = 0;
        Matcher volM = VOL_PAT.matcher(slug);
        if (volM.find()) {
            volNum = Integer.parseInt(volM.group(1));
        }
        // If no known volume keyword, check if first segment is word-number
        if (volNum == 0) {
            Matcher fb = Pattern.compile("^\\w+-(\\d+)").matcher(slug);
            if (fb.find()) volNum = Integer.parseInt(fb.group(1));
        }

        Matcher chapM = CHAP_NUM_PAT.matcher(slug);
        if (chapM.find()) {
            return new int[]{volNum, Integer.parseInt(chapM.group(1))};
        }
        if (SPECIAL_CHAP_PAT.matcher(slug).find()) {
            return new int[]{volNum, 0};
        }
        return null;
    }

    /** Extracts a human-readable title from a slug like {@code livro-1-capitulo-5--saindo-de-casa}. */
    private static String titleFromSlug(String slug) {
        int doubleDash = slug.indexOf("--");
        if (doubleDash >= 0) {
            return slug.substring(doubleDash + 2).replace("-", " ");
        }
        return "";
    }

    // ── Image inlining ────────────────────────────────────────────────────────

    private void inlineImages(Element root, String pageUrl) {
        for (Element img : root.select("img[src]")) {
            String src = img.absUrl("src");
            if (src.isBlank() || src.startsWith("data:")) continue;
            try {
                throttle();
                byte[] bytes = downloadBytes(src, pageUrl);
                String mime = guessMime(src);
                img.attr("src", "data:" + mime + ";base64,"
                        + java.util.Base64.getEncoder().encodeToString(bytes));
            } catch (Exception e) {
                log.warn("Could not inline image {}: {}", src, e.getMessage());
            }
        }
    }

    // ── HTTP utilities ────────────────────────────────────────────────────────

    private Document fetch(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent(userAgentProvider.next())
                .referrer(BASE_URL)
                .timeout(TIMEOUT_MS)
                .get();
    }

    private byte[] downloadBytes(String url, String referer)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgentProvider.next())
                .header("Referer", referer)
                .build();
        HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() >= 400)
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        return resp.body();
    }

    private static String guessMime(String url) {
        String l = url.toLowerCase();
        if (l.contains(".png"))  return "image/png";
        if (l.contains(".gif"))  return "image/gif";
        if (l.contains(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private void throttle() {
        if (throttleMs <= 0) return;
        try { Thread.sleep(throttleMs); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

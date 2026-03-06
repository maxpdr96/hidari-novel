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
import java.util.stream.Collectors;

/**
 * Scraper for centralnovel.com (WordPress/Madara theme).
 *
 * <p>Search uses the standard WordPress {@code /?s=query} endpoint.
 * Chapter URLs follow the pattern:
 * {@code /[novel-slug]-volume-N-capitulo-M/} or {@code /[novel-slug]-capitulo-M/}
 */
@Component
public class CentralNovelScraper implements SiteScraperAdapter {

    private static final Logger log = LoggerFactory.getLogger(CentralNovelScraper.class);

    static final String BASE_URL = "https://centralnovel.com";
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MS = 20_000;
    private static final int MAX_SEARCH_PAGES = 5;

    // Link text: "Vol. 1 Cap. 5" or "Vol. 1 Cap. Pról." etc.
    private static final Pattern LINK_VOL_CHAP_PAT = Pattern.compile(
            "Vol\\.\\s*(\\d+)\\s+Cap\\.\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_VOL_SPECIAL_PAT = Pattern.compile(
            "Vol\\.\\s*(\\d+)\\s+Cap\\.\\s*(Pról|Posf|Extra|Bônus|Bnus|Interlúdio|Epílogo|Prólogo|SS)",
            Pattern.CASE_INSENSITIVE);

    // Volume header text: "Volume 5", "Volume  5", "Livro 3" (spaces or dash)
    private static final Pattern VOL_TITLE_PAT = Pattern.compile(
            "(?:volume|livro|tomo|parte|temporada)[\\s\\-]+(\\d+)",
            Pattern.CASE_INSENSITIVE);
    // URL-based fallback patterns (dash-separated)
    private static final Pattern VOL_PAT = Pattern.compile(
            "(?:volume|livro|tomo|parte|temporada)-(\\d+)");
    private static final Pattern CHAP_NUM_PAT = Pattern.compile("capitulo-(\\d+)");
    private static final Pattern SPECIAL_CHAP_PAT = Pattern.compile(
            "prologo|epilogo|posfacio|posf|extra|bonus|intermissao|ss|interludio");

    @Value("${hidarinovel.throttle-ms:800}")
    private long throttleMs;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override public String siteId()      { return "centralnovel"; }
    @Override public String displayName() { return "Central Novel"; }

    // ── Search ────────────────────────────────────────────────────────────────

    @Override
    public List<Novel> search(String query, SearchFilter filter) throws IOException {
        List<Novel> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int page = 1; page <= MAX_SEARCH_PAGES; page++) {
            String url = BASE_URL + "/?s="
                    + java.net.URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
                    + (page > 1 ? "&paged=" + page : "");
            log.debug("Central Novel search page {}: {}", page, url);
            Document doc = fetch(url);

            List<Novel> pageNovels = parseSearchResults(doc, seen);
            if (pageNovels.isEmpty()) break;
            results.addAll(pageNovels);

            if (doc.selectFirst(".next.page-numbers, a.next") == null) break;
        }

        log.debug("Central Novel search '{}': {} result(s)", query, results.size());
        return results;
    }

    private List<Novel> parseSearchResults(Document doc, Set<String> seen) {
        List<Novel> novels = new ArrayList<>();

        // Search results use article.maindet inside .listupd
        // Homepage/listing cards use .bsx — handle both structures:
        // - old: <div class="bsx"><a href="/series/...">...</a></div>
        // - new: <a href="/series/..."><div class="bsx">...</div></a>
        Elements cards = doc.select("article.maindet, .bsx");

        for (Element card : cards) {
            // Link may be inside the card OR the card's parent may be the link
            Element seriesLink = card.selectFirst("a[href*='/series/']");
            if (seriesLink == null) {
                Element parent = card.parent();
                if (parent != null && "a".equals(parent.tagName())
                        && parent.attr("href").contains("/series/")) {
                    seriesLink = parent;
                }
            }
            if (seriesLink == null) continue;

            String href = seriesLink.attr("href");
            String slug = href.replaceFirst("(?:https?://centralnovel\\.com)?/series/", "")
                              .replaceFirst("/$", "");
            if (slug.isBlank() || seen.contains(slug)) continue;
            seen.add(slug);

            // Title: h2 > a (maindet), .tt (bsx), img alt fallback
            String title = "";
            Element h2a = card.selectFirst("h2 a[title]");
            if (h2a != null) title = h2a.attr("title").trim();
            if (title.isBlank()) {
                Element titleEl = card.selectFirst(".tt");
                if (titleEl != null) title = titleEl.text().trim();
            }
            if (title.isBlank()) {
                Element img = card.selectFirst("img[alt]");
                if (img != null) title = img.attr("alt").trim();
            }
            if (title.isBlank()) title = slug.replace("-", " ");

            // Cover
            String coverUrl = "";
            Element img = card.selectFirst("img");
            if (img != null) coverUrl = img.absUrl("src");

            novels.add(new Novel(siteId(), slug, title, "", "", 0,
                    coverUrl, BASE_URL + "/series/" + slug + "/"));
        }
        return novels;
    }

    // ── Novel details ─────────────────────────────────────────────────────────

    @Override
    public Novel getNovelDetails(Novel partial) throws IOException {
        String url = BASE_URL + "/series/" + partial.slug() + "/";
        log.debug("Central Novel detail: {}", url);
        Document doc = fetch(url);

        // Title
        String title = partial.title();
        Element h1 = doc.selectFirst("h1.entry-title, h1");
        if (h1 != null && !h1.text().isBlank()) title = h1.text().trim();

        // Author & status — structure: .spe > span > <b>Label:</b> value/links
        String author = partial.author();
        String status = partial.status();
        Element spe = doc.selectFirst(".spe, .infox .spe");
        if (spe != null) {
            for (Element span : spe.select("span")) {
                Element bold = span.selectFirst("b");
                if (bold == null) continue;
                String label = bold.text().trim().toLowerCase();
                if (status.isBlank() && label.startsWith("status")) {
                    // Status value is plain text after the <b> tag
                    status = span.text().replaceFirst("(?i)status:?\\s*", "").trim();
                } else if (author.isBlank() && label.startsWith("autor")) {
                    // Author may be one or more <a> tags
                    Elements links = span.select("a");
                    if (!links.isEmpty()) {
                        author = links.get(0).text().trim();
                    } else {
                        author = span.text().replaceFirst("(?i)autor:?\\s*", "").trim();
                    }
                }
            }
        }

        // Genres
        List<String> genres = new ArrayList<>();
        for (Element a : doc.select("a[href*='/genre/']")) {
            String g = a.text().trim();
            if (!g.isBlank()) genres.add(g);
        }

        // Synopsis — try common selectors for this theme
        String synopsis = "";
        for (String sel : new String[]{
                ".entry-content[itemprop='description'] p",
                ".summary__content p",
                ".desc p",
                "[itemprop='description'] p",
                ".description p"}) {
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

        // Cover image
        String coverUrl = partial.coverUrl();
        for (String sel : new String[]{
                ".thumb img", ".cover img", ".summary_image img",
                "img[src*='CAPA']", "img[src*='capa']", ".wd-full img",
                "img[src*='" + partial.slug() + "']"}) {
            Element img = doc.selectFirst(sel);
            if (img != null && !img.absUrl("src").isBlank()) {
                coverUrl = img.absUrl("src"); break;
            }
        }

        // Total chapters = number of chapter links in the list
        int totalChapters = doc.select(".ts-chl-collapsible-content li a[href]").size();
        if (totalChapters == 0)
            totalChapters = doc.select("ul.main li a[href*='-capitulo-']").size();

        return partial.withDetails(synopsis, "", genres, "", "", "", 0,
                coverUrl, author, status, totalChapters);
    }

    // ── Volumes & chapters ────────────────────────────────────────────────────

    @Override
    public List<Volume> getVolumes(String slug) throws IOException {
        String url = BASE_URL + "/series/" + slug + "/";
        log.debug("Central Novel chapters: {}", url);
        Document doc = fetch(url);

        List<Volume> volumes = new ArrayList<>();
        int globalCounter = 0;

        Elements volHeaders = doc.select(".ts-chl-collapsible");

        if (!volHeaders.isEmpty()) {
            // Collect raw (volNum, volTitle, rawChapters) — site lists volumes newest-first
            record RawVol(int num, String title, List<Element> links) {}
            List<RawVol> raw = new ArrayList<>();

            for (Element header : volHeaders) {
                String volTitle = header.text().trim();
                int volNum = 1;
                Matcher vm = VOL_TITLE_PAT.matcher(volTitle);
                if (vm.find()) volNum = Integer.parseInt(vm.group(1));

                Element content = header.nextElementSibling();
                if (content == null || !content.hasClass("ts-chl-collapsible-content")) continue;

                List<Element> links = content.select("li a[href]").stream()
                        .filter(a -> !a.attr("href").contains("/pdf/"))
                        .collect(Collectors.toList());
                if (!links.isEmpty()) raw.add(new RawVol(volNum, volTitle, links));
            }

            // Sort volumes ascending so global chapter numbers flow 1→N
            raw.sort(Comparator.comparingInt(RawVol::num));

            for (RawVol rv : raw) {
                List<Chapter> chapters = new ArrayList<>();
                for (Element a : rv.links()) {
                    String href = a.attr("href");
                    String eplNum   = textOf(a.selectFirst(".epl-num"));
                    String eplTitle = textOf(a.selectFirst(".epl-title"));
                    String coordText = eplNum.isBlank() ? a.text().trim() : eplNum;

                    int[] coords = parseChapterCoords(href, coordText, slug);
                    if (coords == null) {
                        log.warn("Could not parse chapter: {} / {}", href, coordText);
                        continue;
                    }

                    globalCounter++;
                    String chTitle = eplTitle.isBlank() ? coordText : eplTitle;
                    chapters.add(new Chapter(globalCounter, coords[1], rv.num(),
                            rv.title(), chTitle, href));
                }
                if (!chapters.isEmpty()) volumes.add(new Volume(rv.num(), rv.title(), chapters));
            }
        } else {
            // Flat chapter list — no volume sections
            List<Chapter> chapters = new ArrayList<>();
            for (Element a : doc.select(".eplister li a[href]")) {
                String href = a.attr("href");
                if (href.contains("/pdf/")) continue;
                String eplNum   = textOf(a.selectFirst(".epl-num"));
                String eplTitle = textOf(a.selectFirst(".epl-title"));
                String coordText = eplNum.isBlank() ? a.text().trim() : eplNum;

                int[] coords = parseChapterCoords(href, coordText, slug);
                if (coords == null) continue;

                globalCounter++;
                String chTitle = eplTitle.isBlank() ? coordText : eplTitle;
                chapters.add(new Chapter(globalCounter, coords[1], 1,
                        "Sem Volume", chTitle, href));
            }
            if (!chapters.isEmpty()) volumes.add(new Volume(1, "Sem Volume", chapters));
        }

        return volumes;
    }

    private static String textOf(Element el) {
        return el == null ? "" : el.text().trim();
    }

    /**
     * Parses [volNum, chapNum] from a chapter link.
     * Tries link text first ("Vol. N Cap. M"), then URL patterns.
     */
    private int[] parseChapterCoords(String href, String linkText, String slug) {
        // 1) Link text: "Vol. N Cap. M"
        Matcher lm = LINK_VOL_CHAP_PAT.matcher(linkText);
        if (lm.find()) {
            return new int[]{Integer.parseInt(lm.group(1)), Integer.parseInt(lm.group(2))};
        }
        // 2) Link text: "Vol. N Cap. <special>"
        Matcher ls = LINK_VOL_SPECIAL_PAT.matcher(linkText);
        if (ls.find()) {
            return new int[]{Integer.parseInt(ls.group(1)), 0};
        }

        // 3) URL-based fallback — strip novel slug prefix from path
        String path = href.replaceFirst("https?://centralnovel\\.com", "")
                          .replaceFirst("^/", "")
                          .replaceFirst("/$", "");
        String chSlug = path.startsWith(slug + "-")
                ? path.substring(slug.length() + 1)
                : path;

        int volNum = 0;
        Matcher volM = VOL_PAT.matcher(chSlug);
        if (volM.find()) volNum = Integer.parseInt(volM.group(1));
        if (volNum == 0) volNum = 1;

        Matcher chapM = CHAP_NUM_PAT.matcher(chSlug);
        if (chapM.find()) return new int[]{volNum, Integer.parseInt(chapM.group(1))};

        if (SPECIAL_CHAP_PAT.matcher(chSlug).find()) return new int[]{volNum, 0};

        return null;
    }

    // ── Chapter content ───────────────────────────────────────────────────────

    @Override
    public ChapterContent getChapterContent(Chapter chapter) throws IOException {
        throttle();
        log.debug("Central Novel fetching: {}", chapter.url());
        Document doc = fetch(chapter.url());

        Element content = null;
        for (String sel : new String[]{
                ".entry-content", ".reading-content", ".chapter-content",
                "article .content", "main .content", "article"}) {
            content = doc.selectFirst(sel);
            if (content != null) break;
        }
        if (content == null)
            throw new IOException("Chapter content not found at: " + chapter.url());

        content.select(".pagination, .nav-chapter, script, style, link, "
                + "[class*='comment'], [class*='ads'], [class*='sponsor'], "
                + "[class*='navigation'], [class*='nav-'], .wp-block-buttons, "
                + ".sharedaddy, .jp-relatedposts, .wpdiscuz-form").remove();

        // Chapter title — prefer h1/h2 on the page
        String chapterTitle = chapter.shortLabel();
        for (String sel : new String[]{"h1.entry-title", "h1", "h2.entry-title", ".chapter-title"}) {
            Element h = doc.selectFirst(sel);
            if (h != null && !h.text().isBlank()) { chapterTitle = h.text().trim(); break; }
        }

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
                .userAgent(USER_AGENT)
                .referrer(BASE_URL)
                .timeout(TIMEOUT_MS)
                .get();
    }

    private byte[] downloadBytes(String url, String referer)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
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

package com.hidarinovel.model;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional search filters for the {@code /novels} endpoint.
 * All fields are nullable — only non-blank values are added to the query string.
 *
 * <p>Genre uses the site's numeric ID (e.g. "1" = Ação, "20" = Xuanhuan).
 * A name→ID lookup is provided via {@link #genreId(String)}.
 */
public record SearchFilter(
        String genre,
        String origin,
        String status,
        String order
) {

    /** Empty filter — no additional query params. */
    public static SearchFilter empty() {
        return new SearchFilter(null, null, null, null);
    }

    // ── Genre name → site numeric ID ──────────────────────────────────────────

    private static final Map<String, String> GENRE_MAP = new LinkedHashMap<>();

    static {
        GENRE_MAP.put("acao", "1");
        GENRE_MAP.put("adulto", "2");
        GENRE_MAP.put("antologia", "39");
        GENRE_MAP.put("artes marciais", "7");
        GENRE_MAP.put("aventura", "3");
        GENRE_MAP.put("comedia", "4");
        GENRE_MAP.put("conto", "38");
        GENRE_MAP.put("cotidiano", "16");
        GENRE_MAP.put("cultivo", "47");
        GENRE_MAP.put("distopia", "41");
        GENRE_MAP.put("drama", "23");
        GENRE_MAP.put("ecchi", "27");
        GENRE_MAP.put("erotico", "22");
        GENRE_MAP.put("escolar", "13");
        GENRE_MAP.put("exploracao", "45");
        GENRE_MAP.put("fantasia", "5");
        GENRE_MAP.put("futurista", "40");
        GENRE_MAP.put("harem", "21");
        GENRE_MAP.put("historico", "42");
        GENRE_MAP.put("horror", "43");
        GENRE_MAP.put("isekai", "30");
        GENRE_MAP.put("magia", "26");
        GENRE_MAP.put("mecha", "8");
        GENRE_MAP.put("medieval", "31");
        GENRE_MAP.put("militar", "24");
        GENRE_MAP.put("misterio", "9");
        GENRE_MAP.put("mitologia", "10");
        GENRE_MAP.put("psicologico", "11");
        GENRE_MAP.put("punk", "44");
        GENRE_MAP.put("realidade virtual", "36");
        GENRE_MAP.put("romance", "12");
        GENRE_MAP.put("sci-fi", "14");
        GENRE_MAP.put("sistema de jogo", "15");
        GENRE_MAP.put("sobrenatural", "17");
        GENRE_MAP.put("super-heroi", "46");
        GENRE_MAP.put("suspense", "29");
        GENRE_MAP.put("terror", "6");
        GENRE_MAP.put("wuxia", "18");
        GENRE_MAP.put("xianxia", "19");
        GENRE_MAP.put("xuanhuan", "20");
        GENRE_MAP.put("yaoi", "35");
        GENRE_MAP.put("yuri", "37");
    }

    /**
     * Resolves a genre name (case-insensitive, accents stripped) to its numeric
     * site ID, or returns null if not found.
     */
    public static String genreId(String name) {
        if (name == null || name.isBlank()) return null;
        String key = stripAccents(name.trim().toLowerCase());
        return GENRE_MAP.get(key);
    }

    /** Returns all known genre names (for help/display). */
    public static java.util.Set<String> knownGenres() {
        return GENRE_MAP.keySet();
    }

    // ── Order name → site value ───────────────────────────────────────────────

    private static final Map<String, String> ORDER_MAP = Map.of(
            "alfabetica", "0",
            "capitulos", "1",
            "popularidade", "2",
            "novidades", "3"
    );

    public static String orderId(String name) {
        if (name == null || name.isBlank()) return null;
        String key = stripAccents(name.trim().toLowerCase());
        return ORDER_MAP.get(key);
    }

    // ── Query param building ──────────────────────────────────────────────────

    /**
     * Converts non-blank filter fields to URL query parameters.
     * Returns a string like {@code "&categoria=20&status=ativo"} (leading &amp;).
     */
    public String toQueryParams() {
        var sb = new StringBuilder();
        appendIfPresent(sb, "categoria", resolveGenre());
        appendIfPresent(sb, "nacionalidade", origin);
        appendIfPresent(sb, "status", status);
        appendIfPresent(sb, "ordem", resolveOrder());
        return sb.toString();
    }

    private String resolveGenre() {
        if (genre == null || genre.isBlank()) return null;
        // Accept raw numeric ID or name
        if (genre.matches("\\d+")) return genre;
        return genreId(genre);
    }

    private String resolveOrder() {
        if (order == null || order.isBlank()) return null;
        if (order.matches("\\d")) return order;
        return orderId(order);
    }

    private static void appendIfPresent(StringBuilder sb, String param, String value) {
        if (value != null && !value.isBlank()) {
            sb.append('&').append(param).append('=')
              .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
    }

    private static String stripAccents(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }
}

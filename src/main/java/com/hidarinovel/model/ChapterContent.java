package com.hidarinovel.model;

/**
 * Full content of a fetched chapter, ready for export.
 *
 * <p>{@code htmlBody} is the inner HTML of {@code #chapter-content} with:
 * <ul>
 *   <li>Donation/ad sections removed</li>
 *   <li>Image {@code src} attributes replaced by {@code data:} URIs so that
 *       exporters can work fully offline without re-fetching the images</li>
 * </ul>
 *
 * @param chapter    the chapter metadata
 * @param title      human-readable chapter title, e.g. "Capítulo 1: Saindo de Casa"
 * @param volumeLabel e.g. "Livro 1"
 * @param htmlBody   enriched inner HTML ready for PDF/EPUB rendering
 */
public record ChapterContent(
        Chapter chapter,
        String title,
        String volumeLabel,
        String htmlBody
) {

    /** Full display title combining volume label and chapter title. */
    public String fullTitle() {
        return volumeLabel.isBlank() ? title : volumeLabel + " – " + title;
    }
}

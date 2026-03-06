package com.hidarinovel.model;

/**
 * A single chapter entry as discovered on the novel's chapter-list page.
 * The {@code globalNumber} is a sequential counter across all volumes
 * (1-based), used for range selection in the {@code download} command.
 *
 * <p>The {@code title} is the real chapter title parsed from the listing
 * page (e.g. "Capítulo 1: Yun Che, Xiao Che"). It may be empty if the
 * title could not be extracted.
 */
public record Chapter(
        int globalNumber,   // sequential across all volumes: 1..N
        int number,         // chapter number within the volume (restarts per volume)
        int volumeNumber,   // Livro/book number
        String volumeTitle, // e.g. "Livro 1: O Jovem Medíocre"
        String title,       // real chapter title from the listing page
        String url          // absolute URL of the chapter page
) {

    /** Short label used for progress display before the real title is known. */
    public String shortLabel() {
        if (title != null && !title.isBlank()) return title;
        return "Livro %d, Cap. %d".formatted(volumeNumber, number);
    }

    /** Filename-friendly identifier, e.g. "capitulo-0042". */
    public String fileBaseName() {
        return "capitulo-%04d".formatted(globalNumber);
    }
}

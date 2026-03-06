package com.hidarinovel.export;

public enum ExportFormat {
    PDF("pdf"),
    EPUB("epub");

    public final String extension;

    ExportFormat(String extension) {
        this.extension = extension;
    }

    public static ExportFormat fromString(String s) {
        return switch (s.toLowerCase().trim()) {
            case "pdf"  -> PDF;
            case "epub" -> EPUB;
            default -> throw new IllegalArgumentException(
                    "Unknown format '%s'. Use 'pdf' or 'epub'.".formatted(s));
        };
    }
}

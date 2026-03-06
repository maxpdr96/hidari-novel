package com.hidarinovel.export;

import com.hidarinovel.model.ChapterContent;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Exports {@link ChapterContent} objects to PDF using OpenHTMLtoPDF + PDFBox.
 *
 * <p>Images are already embedded as {@code data:} URIs in the chapter HTML,
 * so no network access is required during PDF rendering.
 */
@Component
public class PdfExporter {

    private static final Logger log = LoggerFactory.getLogger(PdfExporter.class);

    private static final String CSS = """
            @page { margin: 2.5cm; }
            body {
                font-family: Georgia, "Times New Roman", serif;
                font-size: 11pt;
                line-height: 1.75;
                color: #1a1a1a;
            }
            .chapter { page-break-after: always; }
            .chapter:last-child { page-break-after: auto; }
            .vol-label {
                font-size: 12pt;
                color: #666;
                text-align: center;
                margin-top: 1em;
                margin-bottom: 0.2em;
            }
            h2 {
                font-size: 16pt;
                text-align: center;
                margin-top: 0;
                margin-bottom: 1.5em;
                border-bottom: 1px solid #ccc;
                padding-bottom: 0.4em;
            }
            p {
                margin: 0.5em 0;
                text-align: justify;
                text-indent: 1.5em;
            }
            p:first-of-type { text-indent: 0; }
            img {
                max-width: 100%;
                display: block;
                margin: 1em auto;
            }
            """;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Exports each chapter to its own PDF file inside {@code outputDir}.
     * Files are named {@code capitulo-0001.pdf}.
     *
     * @return list of created file paths
     */
    public List<Path> exportPerChapter(List<ChapterContent> chapters, Path outputDir)
            throws IOException {
        Files.createDirectories(outputDir);
        List<Path> created = new java.util.ArrayList<>();
        for (ChapterContent cc : chapters) {
            Path out = outputDir.resolve(cc.chapter().fileBaseName() + ".pdf");
            renderToFile(singleChapterHtml(cc), out);
            created.add(out);
            log.debug("PDF written: {}", out);
        }
        return created;
    }

    /**
     * Combines all chapters into a single PDF with page breaks between them.
     *
     * @param outputFile full path of the output PDF
     */
    public void exportCombined(List<ChapterContent> chapters, Path outputFile)
            throws IOException {
        Files.createDirectories(outputFile.getParent());
        renderToFile(combinedHtml(chapters), outputFile);
        log.debug("Combined PDF written: {}", outputFile);
    }

    // ── HTML assembly ─────────────────────────────────────────────────────────

    private String singleChapterHtml(ChapterContent cc) {
        return wrapDocument(chapterDiv(cc, false));
    }

    private String combinedHtml(List<ChapterContent> chapters) {
        var sb = new StringBuilder();
        for (int i = 0; i < chapters.size(); i++) {
            sb.append(chapterDiv(chapters.get(i), i < chapters.size() - 1));
        }
        return wrapDocument(sb.toString());
    }

    /** Wraps HTML body content in a complete HTML5 document. */
    private static String wrapDocument(String body) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="UTF-8"/>
                  <style>%s</style>
                </head>
                <body>
                %s
                </body>
                </html>
                """.formatted(CSS, body);
    }

    /** Renders a single chapter div with optional page-break class. */
    private static String chapterDiv(ChapterContent cc, boolean pageBreakAfter) {
        String cls = pageBreakAfter ? "chapter" : "";
        return """
                <div class="%s">
                  <p class="vol-label">%s</p>
                  %s
                </div>
                """.formatted(cls, xmlEsc(cc.volumeLabel()), cc.htmlBody());
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private static void renderToFile(String html, Path outputFile) throws IOException {
        try (OutputStream os = Files.newOutputStream(outputFile)) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            // No external base URI needed — images are already data URIs
            builder.withHtmlContent(html, null);
            builder.toStream(os);
            builder.run();
        } catch (Exception e) {
            throw new IOException("PDF rendering failed for " + outputFile + ": " + e.getMessage(), e);
        }
    }

    private static String xmlEsc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

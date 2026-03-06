package com.hidarinovel.export;

import com.hidarinovel.model.ChapterContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds EPUB 3 files without any external EPUB library.
 *
 * <p>EPUB is a ZIP archive with a specific internal structure. Each chapter
 * becomes an XHTML document. Images are already inlined as {@code data:} URIs
 * in the chapter HTML, so no additional image handling is needed.
 *
 * <p>Supports both per-chapter export (one EPUB per chapter) and combined
 * export (all chapters in a single EPUB with a full table of contents).
 *
 * <p>When a cover image is provided, it is embedded as a full-bleed cover page
 * that e-readers display in their library view.
 */
@Component
public class EpubBuilder {

    private static final Logger log = LoggerFactory.getLogger(EpubBuilder.class);

    private static final String STYLE_CSS = """
            body {
                font-family: Georgia, "Times New Roman", serif;
                font-size: 1em;
                line-height: 1.7;
                margin: 1em 1.5em;
                color: #1a1a1a;
            }
            .vol-label {
                font-size: 0.95em;
                color: #777;
                text-align: center;
                margin-bottom: 0.2em;
            }
            h2 {
                font-size: 1.35em;
                text-align: center;
                margin-top: 0.2em;
                margin-bottom: 1.2em;
                border-bottom: 1px solid #ccc;
                padding-bottom: 0.3em;
            }
            p {
                margin: 0.4em 0;
                text-align: justify;
                text-indent: 1.4em;
            }
            p:first-of-type { text-indent: 0; }
            img {
                max-width: 100%;
                display: block;
                margin: 0.8em auto;
            }
            """;

    private static final String COVER_CSS = """
            body { margin: 0; padding: 0; text-align: center; }
            img { max-width: 100%; max-height: 100%; }
            """;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Exports each chapter to its own EPUB file inside {@code outputDir}.
     * Files are named {@code capitulo-0001.epub}.
     *
     * @param coverImage optional cover image bytes (may be null)
     * @return list of created file paths
     */
    public List<Path> exportPerChapter(List<ChapterContent> chapters, Path outputDir,
                                        String novelTitle, String author, byte[] coverImage)
            throws IOException {
        Files.createDirectories(outputDir);
        List<Path> created = new ArrayList<>();
        for (ChapterContent cc : chapters) {
            Path out = outputDir.resolve(cc.chapter().fileBaseName() + ".epub");
            writeEpub(List.of(cc), novelTitle, author, out, coverImage);
            created.add(out);
            log.debug("EPUB written: {}", out);
        }
        return created;
    }

    /**
     * Combines all chapters into a single EPUB with a full table of contents.
     *
     * @param outputFile full path of the output EPUB
     * @param coverImage optional cover image bytes (may be null)
     */
    public void exportCombined(List<ChapterContent> chapters, Path outputFile,
                                String novelTitle, String author, byte[] coverImage)
            throws IOException {
        Files.createDirectories(outputFile.getParent());
        writeEpub(chapters, novelTitle, author, outputFile, coverImage);
        log.debug("Combined EPUB written: {}", outputFile);
    }

    // ── EPUB assembly ─────────────────────────────────────────────────────────

    private void writeEpub(List<ChapterContent> chapters, String title,
                            String author, Path outputFile, byte[] coverImage)
            throws IOException {
        String uid = UUID.randomUUID().toString();
        boolean hasCover = coverImage != null && coverImage.length > 0;

        try (OutputStream fos = Files.newOutputStream(outputFile);
             ZipOutputStream zip = new ZipOutputStream(fos, StandardCharsets.UTF_8)) {

            // 1. mimetype — must be FIRST and UNCOMPRESSED
            writeMimetype(zip);

            // 2. META-INF/container.xml
            writeText(zip, "META-INF/container.xml", containerXml());

            // 3. Stylesheet
            writeText(zip, "OEBPS/style.css", STYLE_CSS);

            // 4. Cover image and cover page (if available)
            if (hasCover) {
                writeBytes(zip, "OEBPS/cover.jpg", coverImage);
                writeText(zip, "OEBPS/cover.xhtml", coverXhtml(title));
            }

            // 5. Chapter XHTML files
            List<String[]> toc = new ArrayList<>();  // [id, title, filename]
            for (int i = 0; i < chapters.size(); i++) {
                ChapterContent cc = chapters.get(i);
                String id       = "ch%04d".formatted(i + 1);
                String filename = "chapter%04d.xhtml".formatted(i + 1);
                writeText(zip, "OEBPS/" + filename, chapterXhtml(cc));
                toc.add(new String[]{id, cc.fullTitle(), filename});
            }

            // 6. Navigation document (EPUB3)
            writeText(zip, "OEBPS/nav.xhtml", navXhtml(title, toc));

            // 7. Package document (content.opf)
            writeText(zip, "OEBPS/content.opf", contentOpf(uid, title, author, toc, hasCover));
        }
    }

    // ── XML/XHTML generators ──────────────────────────────────────────────────

    private static String containerXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0"
                    xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf"
                        media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """;
    }

    private static String contentOpf(String uid, String title, String author,
                                      List<String[]> toc, boolean hasCover) {
        var manifest = new StringBuilder();
        var spine    = new StringBuilder();

        manifest.append("""
                    <item id="nav" href="nav.xhtml"
                        media-type="application/xhtml+xml" properties="nav"/>
                    <item id="style" href="style.css" media-type="text/css"/>
                """);

        if (hasCover) {
            manifest.append("""
                        <item id="cover-image" href="cover.jpg"
                            media-type="image/jpeg" properties="cover-image"/>
                        <item id="cover" href="cover.xhtml"
                            media-type="application/xhtml+xml"/>
                    """);
            spine.append("    <itemref idref=\"cover\"/>\n");
        }

        for (String[] entry : toc) {
            manifest.append("    <item id=\"%s\" href=\"%s\" media-type=\"application/xhtml+xml\"/>\n"
                    .formatted(entry[0], entry[2]));
            spine.append("    <itemref idref=\"%s\"/>\n".formatted(entry[0]));
        }

        var metaExtra = hasCover
                ? "\n    <meta name=\"cover\" content=\"cover-image\"/>"
                : "";

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0"
                    unique-identifier="uid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>%s</dc:title>
                    <dc:creator>%s</dc:creator>
                    <dc:language>pt</dc:language>
                    <dc:identifier id="uid">%s</dc:identifier>%s
                  </metadata>
                  <manifest>
                %s  </manifest>
                  <spine>
                %s  </spine>
                </package>
                """.formatted(xmlEsc(title), xmlEsc(author), uid, metaExtra, manifest, spine);
    }

    private static String navXhtml(String title, List<String[]> toc) {
        var items = new StringBuilder();
        for (String[] entry : toc) {
            items.append("      <li><a href=\"%s\">%s</a></li>\n"
                    .formatted(entry[2], xmlEsc(entry[1])));
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:epub="http://www.idpf.org/2007/ops">
                <head><meta charset="UTF-8"/><title>%s</title></head>
                <body>
                  <nav epub:type="toc" id="toc">
                    <h1>Sumário</h1>
                    <ol>
                %s    </ol>
                  </nav>
                </body>
                </html>
                """.formatted(xmlEsc(title), items);
    }

    private static String coverXhtml(String title) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                  <meta charset="UTF-8"/>
                  <title>%s</title>
                  <style>%s</style>
                </head>
                <body>
                  <div style="text-align: center; padding: 0; margin: 0;">
                    <img src="cover.jpg" alt="Capa"/>
                  </div>
                </body>
                </html>
                """.formatted(xmlEsc(title), COVER_CSS);
    }

    private static String chapterXhtml(ChapterContent cc) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                  <meta charset="UTF-8"/>
                  <title>%s</title>
                  <link rel="stylesheet" href="style.css"/>
                </head>
                <body>
                  <p class="vol-label">%s</p>
                  %s
                </body>
                </html>
                """.formatted(xmlEsc(cc.fullTitle()), xmlEsc(cc.volumeLabel()), cc.htmlBody());
    }

    // ── ZIP helpers ───────────────────────────────────────────────────────────

    /** The mimetype entry must be uncompressed and must be the first entry. */
    private static void writeMimetype(ZipOutputStream zip) throws IOException {
        byte[] bytes = "application/epub+zip".getBytes(StandardCharsets.UTF_8);
        ZipEntry entry = new ZipEntry("mimetype");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void writeText(ZipOutputStream zip, String entryName, String text)
            throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void writeBytes(ZipOutputStream zip, String entryName, byte[] data)
            throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(data);
        zip.closeEntry();
    }

    private static String xmlEsc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}

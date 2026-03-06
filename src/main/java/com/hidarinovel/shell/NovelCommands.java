package com.hidarinovel.shell;

import com.hidarinovel.export.ExportFormat;
import com.hidarinovel.model.*;
import com.hidarinovel.service.NovelService;
import com.hidarinovel.store.FavoritesStore;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.springframework.context.annotation.Lazy;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Spring Shell commands for the HidariNovel CLI.
 */
@ShellComponent
public class NovelCommands {

    private static final AttributedStyle BOLD   = AttributedStyle.DEFAULT.bold();
    private static final AttributedStyle DIM    = AttributedStyle.DEFAULT.faint();
    private static final AttributedStyle CYAN   = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
    private static final AttributedStyle GREEN  = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
    private static final AttributedStyle YELLOW = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
    private static final AttributedStyle RED    = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);
    private static final AttributedStyle MAGENTA = AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final NovelService service;
    private final LineReader   lineReader;

    public NovelCommands(NovelService service, @Lazy LineReader lineReader) {
        this.service    = service;
        this.lineReader = lineReader;
    }

    // ── search ────────────────────────────────────────────────────────────────

    @ShellMethod(key = "search", value = "Search for a novel with optional filters")
    public String search(
            @ShellOption(arity = -1, help = "Novel title (no quotes needed)") String[] words,
            @ShellOption(help = "Filter by genre (e.g. xuanhuan, fantasia, acao)",
                         defaultValue = "") String genre,
            @ShellOption(help = "Filter by origin (chinesa, japonesa, coreana, brasileira)",
                         defaultValue = "") String origin,
            @ShellOption(help = "Filter by status (ativo, completo, pausado, parado)",
                         defaultValue = "") String status,
            @ShellOption(help = "Order by (alfabetica, capitulos, popularidade, novidades)",
                         defaultValue = "") String order) {

        String query = String.join(" ", words).trim();
        SearchFilter filter = new SearchFilter(
                genre.isBlank() ? null : genre,
                origin.isBlank() ? null : origin,
                status.isBlank() ? null : status,
                order.isBlank() ? null : order
        );

        boolean hasFilters = !genre.isBlank() || !origin.isBlank()
                || !status.isBlank() || !order.isBlank();

        if (query.isBlank() && !hasFilters)
            return s("Forneça um título ou pelo menos um filtro.", YELLOW);

        String msg = hasFilters
                ? "Buscando com filtros..."
                : "Buscando por \"%s\"...".formatted(query);
        print(s(msg, CYAN));

        List<Novel> results;
        try {
            results = service.search(query, filter);
        } catch (IOException e) {
            return s("Busca falhou: " + e.getMessage(), RED);
        }

        if (results.isEmpty()) {
            return s("Nenhuma novel encontrada.", YELLOW);
        }

        return displayAndSelect(results);
    }

    // ── Discovery commands ────────────────────────────────────────────────────

    @ShellMethod(key = "trending", value = "Show trending novels (Em alta)")
    public String trending() {
        print(s("Buscando novels em alta...", CYAN));
        try {
            List<Novel> results = service.getTrending();
            if (results.isEmpty()) return s("Nenhuma novel encontrada.", YELLOW);
            return displayAndSelect(results);
        } catch (IOException e) {
            return s("Falha: " + e.getMessage(), RED);
        }
    }

    @ShellMethod(key = "popular", value = "Show most popular novels")
    public String popular() {
        print(s("Buscando novels populares...", CYAN));
        try {
            List<Novel> results = service.getPopular();
            if (results.isEmpty()) return s("Nenhuma novel encontrada.", YELLOW);
            return displayAndSelect(results);
        } catch (IOException e) {
            return s("Falha: " + e.getMessage(), RED);
        }
    }

    @ShellMethod(key = "new", value = "Show newest novels (Novidades)")
    public String newNovels() {
        print(s("Buscando novidades...", CYAN));
        try {
            List<Novel> results = service.getNew();
            if (results.isEmpty()) return s("Nenhuma novel encontrada.", YELLOW);
            return displayAndSelect(results);
        } catch (IOException e) {
            return s("Falha: " + e.getMessage(), RED);
        }
    }

    @ShellMethod(key = "latest", value = "Show latest chapter releases")
    public String latest() {
        print(s("Buscando últimos lançamentos...", CYAN));
        try {
            List<LatestRelease> releases = service.getLatestReleases();
            if (releases.isEmpty()) return s("Nenhum lançamento encontrado.", YELLOW);

            var sb = new StringBuilder();
            sb.append(s("Últimos Lançamentos:\n", BOLD));
            sb.append(s("─".repeat(62) + "\n", DIM));

            for (LatestRelease r : releases) {
                sb.append(s("  " + r.novelTitle(), BOLD))
                  .append(s("  →  " + r.chapterTitle(), DIM));
                if (!r.timestamp().isBlank()) {
                    sb.append(s("  (%s)".formatted(formatTimestamp(r.timestamp())), DIM));
                }
                sb.append('\n');
            }

            sb.append('\n');
            sb.append(s("Use ", DIM))
              .append(s("search <título>", BOLD))
              .append(s(" para selecionar uma dessas novels.", DIM));

            return sb.toString().stripTrailing();
        } catch (IOException e) {
            return s("Falha: " + e.getMessage(), RED);
        }
    }

    // ── Favorites ─────────────────────────────────────────────────────────────

    @ShellMethod(key = "favorites", value = "Manage favorite novels (add, list, select N, remove N)")
    public String favorites(
            @ShellOption(arity = -1, help = "Action: add, list, select <N>, remove <N>")
                    String[] args) {

        if (args.length == 0) return favoritesUsage();

        String action = args[0].toLowerCase(); 
        FavoritesStore store = service.getFavoritesStore();

        return switch (action) {
            case "add" -> {
                if (service.getCurrentNovel().isEmpty())
                    yield noNovelSelected();
                if (service.addCurrentToFavorites()) {
                    yield s("✓ ", GREEN)
                            + service.getCurrentNovel().get().title()
                            + s(" adicionada aos favoritos.", GREEN);
                }
                yield s("Erro ao adicionar.", RED);
            }
            case "list", "ls" -> {
                List<FavoriteEntry> entries = store.list();
                if (entries.isEmpty())
                    yield s("Nenhum favorito salvo. Use ", YELLOW)
                            + s("favorites add", BOLD)
                            + s(" com uma novel selecionada.", YELLOW);

                var sb = new StringBuilder();
                sb.append(s("Favoritos (%d):\n".formatted(entries.size()), BOLD));
                sb.append(s("─".repeat(62) + "\n", DIM));

                for (int i = 0; i < entries.size(); i++) {
                    FavoriteEntry e = entries.get(i);
                    sb.append(s("%3d. ".formatted(i + 1), BOLD))
                      .append(e.title());
                    if (!e.author().isBlank())
                        sb.append(s(" por " + e.author(), DIM));
                    if (e.lastKnownChapters() > 0)
                        sb.append(s("  [%d caps]".formatted(e.lastKnownChapters()), DIM));
                    if (e.lastDownloadedChapter() > 0)
                        sb.append(s("  (baixado até: %d)".formatted(e.lastDownloadedChapter()), DIM));
                    sb.append('\n');
                }
                sb.append('\n');
                sb.append(s("Use ", DIM))
                  .append(s("favorites select <N>", BOLD))
                  .append(s(" para selecionar.", DIM));
                yield sb.toString().stripTrailing();
            }
            case "select", "sel" -> {
                if (args.length < 2) yield s("Use: favorites select <número>", YELLOW);
                int idx;
                try { idx = Integer.parseInt(args[1]); }
                catch (NumberFormatException e) { yield s("Número inválido.", YELLOW); }

                var entryOpt = store.get(idx);
                if (entryOpt.isEmpty())
                    yield s("Favorito #%d não encontrado.".formatted(idx), YELLOW);

                FavoriteEntry entry = entryOpt.get();
                print(s("Carregando %s...".formatted(entry.title()), CYAN));
                try {
                    var novelOpt = service.selectNovelByFavorite(entry);
                    yield novelOpt
                            .map(n -> novelCard(n) + "\n\n"
                                    + s("Use ", DIM) + s("chapters", BOLD)
                                    + s(" ou ", DIM) + s("download", BOLD)
                                    + s(" para continuar.", DIM))
                            .orElse(s("Falha ao carregar novel.", RED));
                } catch (IOException e) {
                    yield s("Erro: " + e.getMessage(), RED);
                }
            }
            case "remove", "rm" -> {
                if (args.length < 2) yield s("Use: favorites remove <número>", YELLOW);
                int idx;
                try { idx = Integer.parseInt(args[1]); }
                catch (NumberFormatException e) { yield s("Número inválido.", YELLOW); }

                var entryOpt = store.get(idx);
                if (entryOpt.isEmpty())
                    yield s("Favorito #%d não encontrado.".formatted(idx), YELLOW);

                store.remove(entryOpt.get().slug());
                yield s("✓ ", GREEN) + entryOpt.get().title()
                        + s(" removida dos favoritos.", GREEN);
            }
            default -> favoritesUsage();
        };
    }

    private String favoritesUsage() {
        return s("Uso: ", BOLD) + "favorites <ação>\n"
                + s("  add      ", BOLD) + s("— adiciona a novel atual aos favoritos\n", DIM)
                + s("  list     ", BOLD) + s("— lista todos os favoritos\n", DIM)
                + s("  select N ", BOLD) + s("— seleciona o favorito N\n", DIM)
                + s("  remove N ", BOLD) + s("— remove o favorito N", DIM);
    }

    // ── Check updates ─────────────────────────────────────────────────────────

    @ShellMethod(key = "check-updates", value = "Check for new chapters in favorite novels")
    public String checkUpdates() {
        FavoritesStore store = service.getFavoritesStore();
        List<FavoriteEntry> entries = store.list();

        if (entries.isEmpty()) {
            return s("Nenhum favorito salvo. Use ", YELLOW)
                    + s("favorites add", BOLD)
                    + s(" para rastrear novels.", YELLOW);
        }

        print(s("Verificando atualizações para %d novel(s)...".formatted(entries.size()), CYAN));

        var sb = new StringBuilder();
        sb.append(s("Resultado da verificação:\n", BOLD));
        sb.append(s("─".repeat(62) + "\n", DIM));

        int updatedCount = 0;
        for (FavoriteEntry entry : entries) {
            print(s("  Verificando: %s...".formatted(entry.title()), DIM));
            try {
                int newChaps = service.checkForUpdates(entry);
                if (newChaps > 0) {
                    sb.append(s("  ✦ ", MAGENTA))
                      .append(s(entry.title(), BOLD))
                      .append(s("  +%d novos capítulos".formatted(newChaps), GREEN))
                      .append('\n');
                    updatedCount++;
                } else {
                    sb.append(s("  ✓ ", GREEN))
                      .append(entry.title())
                      .append(s("  sem novidades", DIM))
                      .append('\n');
                }
            } catch (IOException e) {
                sb.append(s("  ✗ ", RED))
                  .append(entry.title())
                  .append(s("  erro: %s".formatted(e.getMessage()), RED))
                  .append('\n');
            }
        }

        if (updatedCount > 0) {
            sb.append('\n');
            sb.append(s("Use ", DIM))
              .append(s("favorites select N", BOLD))
              .append(s(" e depois ", DIM))
              .append(s("download", BOLD))
              .append(s(" para baixar os novos capítulos.", DIM));
        }

        return sb.toString().stripTrailing();
    }

    // ── output-dir ────────────────────────────────────────────────────────────

    @ShellMethod(key = "output-dir", value = "Show or set the default output directory for downloads")
    public String outputDir(
            @ShellOption(arity = -1, help = "New output directory path (omit to show current)",
                         defaultValue = ShellOption.NULL) String[] path) {

        if (path == null || path.length == 0) {
            return s("Diretório de saída atual: ", DIM) + s(service.getOutputDir(), BOLD);
        }

        String newPath = String.join(" ", path).trim();
        if (newPath.isBlank()) {
            return s("Caminho inválido.", YELLOW);
        }
        service.setOutputDir(newPath);
        return s("✓ Diretório de saída definido para: ", GREEN) + s(newPath, BOLD);
    }

    // ── info ──────────────────────────────────────────────────────────────────

    @ShellMethod(key = "info", value = "Show detailed info about the currently selected novel")
    public String info() {
        return service.getCurrentNovel()
                .map(this::novelCardFull)
                .orElse(noNovelSelected());
    }

    // ── chapters ──────────────────────────────────────────────────────────────

    @ShellMethod(key = "chapters", value = "List volumes or chapters of the selected novel")
    public String chapters(
            @ShellOption(help = "Show individual chapters of this volume number",
                         defaultValue = "0") int volume) {

        if (service.getCurrentNovel().isEmpty()) return noNovelSelected();

        List<Volume> volumes = service.getVolumes();
        if (volumes.isEmpty()) return s("No chapters loaded.", YELLOW);

        if (volume > 0) {
            return service.getVolume(volume)
                    .map(this::chapterList)
                    .orElse(s("Volume %d not found.".formatted(volume), YELLOW));
        }

        // Show volume summary
        var sb = new StringBuilder();
        sb.append(s("Volumes (%d):\n".formatted(volumes.size()), BOLD));
        sb.append(s("─".repeat(62) + "\n", DIM));

        for (Volume vol : volumes) {
            int count = vol.chapters().size();
            sb.append(s("  Livro %d: ".formatted(vol.number()), BOLD))
              .append(vol.title().replaceFirst("^Livro \\d+: ", ""))
              .append(s("  (caps. %d–%d, %d capítulos)".formatted(
                      vol.firstGlobal(), vol.lastGlobal(), count), DIM))
              .append('\n');
        }

        sb.append('\n');
        sb.append(s("Use ", DIM))
          .append(s("chapters --volume N", BOLD))
          .append(s(" para ver capítulos individuais de um volume.", DIM));

        return sb.toString().stripTrailing();
    }

    // ── download ──────────────────────────────────────────────────────────────

    @ShellMethod(key = "download",
                 value = "Download and export chapters to PDF or EPUB")
    public String download(
            @ShellOption(help = "First chapter number (global)") int from,
            @ShellOption(help = "Last chapter number (global)")  int to,
            @ShellOption(help = "Output format: pdf or epub", defaultValue = "epub")
                    String format,
            @ShellOption(help = "Combine all chapters into a single file",
                         defaultValue = "false") boolean combine,
            @ShellOption(help = "Output directory (default: ~/Downloads/hidarinovel)",
                         defaultValue = "") String output) {

        if (service.getCurrentNovel().isEmpty()) return noNovelSelected();
        if (from > to)
            return s("--from must be ≤ --to.", YELLOW);

        ExportFormat fmt;
        try {
            fmt = ExportFormat.fromString(format);
        } catch (IllegalArgumentException e) {
            return s(e.getMessage(), RED);
        }

        List<Chapter> range = service.getRange(from, to);
        if (range.isEmpty()) {
            return s("Nenhum capítulo encontrado no intervalo %d–%d. Use 'chapters' para ver os números disponíveis."
                    .formatted(from, to), YELLOW);
        }

        String novelTitle = service.getCurrentNovel().get().title();
        String mode = combine ? "arquivo único" : "um arquivo por capítulo";
        print(s("Baixando %d capítulos de %s → %s [%s]..."
                .formatted(range.size(), novelTitle, fmt.name(), mode), CYAN));

        Path outputDir = output.isBlank() ? null : Path.of(output);

        try {
            List<Path> created = service.download(from, to, fmt, combine, outputDir,
                    msg -> print(s(msg, DIM)));

            var sb = new StringBuilder();
            sb.append(s("\n✓ Concluído! ", GREEN))
              .append("%d arquivo(s) gerado(s):\n".formatted(created.size()));
            created.forEach(p -> sb.append("  ").append(p).append('\n'));
            return sb.toString().stripTrailing();

        } catch (IllegalArgumentException e) {
            return s(e.getMessage(), YELLOW);
        } catch (IOException e) {
            return s("Erro ao exportar: " + e.getMessage(), RED);
        }
    }

    // ── download-volume ───────────────────────────────────────────────────────

    @ShellMethod(key = "download-volume",
                 value = "Download one, a range of volumes (e.g. 2, 2-10), or 'all'")
    public String downloadVolume(
            @ShellOption(help = "Volume number, range (2-10), or 'all'") String volume,
            @ShellOption(help = "Output format: pdf or epub", defaultValue = "epub")
                    String format,
            @ShellOption(help = "Output directory", defaultValue = "") String output) {

        if (service.getCurrentNovel().isEmpty()) return noNovelSelected();

        int volFrom, volTo;
        try {
            if (volume.equalsIgnoreCase("all")) {
                volFrom = 1;
                volTo = service.getVolumes().size();
                if (volTo == 0)
                    return s("Nenhum volume carregado.", YELLOW);
            } else if (volume.contains("-")) {
                String[] parts = volume.split("-", 2);
                volFrom = Integer.parseInt(parts[0].trim());
                volTo   = Integer.parseInt(parts[1].trim());
            } else {
                volFrom = volTo = Integer.parseInt(volume.trim());
            }
        } catch (NumberFormatException e) {
            return s("Formato inválido. Use um número (ex: 2), intervalo (ex: 2-10) ou 'all'.", YELLOW);
        }

        if (volFrom > volTo)
            return s("O volume inicial deve ser menor ou igual ao final.", YELLOW);

        ExportFormat fmt;
        try {
            fmt = ExportFormat.fromString(format);
        } catch (IllegalArgumentException e) {
            return s(e.getMessage(), RED);
        }

        Path outputDir = output.isBlank() ? null : Path.of(output);
        var sb = new StringBuilder();
        int downloaded = 0;

        for (int v = volFrom; v <= volTo; v++) {
            var volOpt = service.getVolume(v);
            if (volOpt.isEmpty()) {
                sb.append(s("Volume %d não encontrado, pulando.\n".formatted(v), YELLOW));
                continue;
            }
            Volume vol = volOpt.get();
            print(s("Baixando Livro %d: %s...".formatted(v, vol.title()), CYAN));
            try {
                List<Path> created = service.download(
                        vol.firstGlobal(), vol.lastGlobal(),
                        fmt, true, outputDir,
                        msg -> print(s(msg, DIM)));
                sb.append(s("✓ Livro %d: ".formatted(v), GREEN))
                  .append(created.getFirst()).append('\n');
                downloaded++;
            } catch (Exception e) {
                sb.append(s("✗ Livro %d: %s\n".formatted(v, e.getMessage()), RED));
            }
        }

        if (downloaded == 0) return s("Nenhum volume baixado.", YELLOW);
        return "\n" + sb.toString().stripTrailing();
    }

    // ── Shared selection flow ─────────────────────────────────────────────────

    /**
     * Displays a numbered novel list and prompts for selection.
     * On selection, loads the novel details and volumes.
     */
    private String displayAndSelect(List<Novel> results) {
        var sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            Novel n = results.get(i);
            sb.append(s("%3d. ".formatted(i + 1), BOLD)).append(n.title());
            if (!n.author().isBlank())
                sb.append(s(" por " + n.author(), DIM));
            if (n.totalChapters() > 0)
                sb.append(s("  [%d caps]".formatted(n.totalChapters()), DIM));
            sb.append(s("  · " + siteName(n.siteId()), MAGENTA));
            sb.append('\n');
        }
        print(sb.toString().stripTrailing());

        int choice = promptInt("Selecionar (1-%d, 0 para cancelar): ".formatted(results.size()),
                0, results.size());
        if (choice == 0) return "Cancelado.";

        print(s("Carregando detalhes e capítulos...", CYAN));
        try {
            return service.selectNovel(choice)
                    .map(n -> novelCard(n) + "\n\n"
                            + s("Use ", DIM) + s("chapters", BOLD)
                            + s(" ou ", DIM) + s("download", BOLD)
                            + s(" para continuar.", DIM))
                    .orElse(s("Seleção inválida.", RED));
        } catch (IOException e) {
            return s("Falha ao carregar: " + e.getMessage(), RED);
        }
    }

    // ── Rendering helpers ─────────────────────────────────────────────────────

    /** Compact card used after selection — shows key fields. */
    private String novelCard(Novel n) {
        var sb = new StringBuilder();
        sb.append(s("━".repeat(55) + "\n", DIM));
        sb.append(s(n.title(), BOLD));
        if (!n.type().isBlank()) sb.append(s("  [" + n.type() + "]", DIM));
        sb.append('\n');
        if (!n.author().isBlank())    field(sb, "Autor",     n.author());
        if (!n.status().isBlank())    field(sb, "Status",    n.status());
        if (!n.origin().isBlank())    field(sb, "Origem",    n.origin());
        if (n.totalChapters() > 0)    field(sb, "Capítulos", String.valueOf(n.totalChapters()));
        field(sb, "Site", siteName(n.siteId()));

        int volCount = service.getVolumes().size();
        if (volCount > 0) field(sb, "Volumes", String.valueOf(volCount));

        sb.append(s("━".repeat(55), DIM));
        return sb.toString();
    }

    /** Full card used by the 'info' command — shows all metadata. */
    private String novelCardFull(Novel n) {
        var sb = new StringBuilder();
        sb.append(s("━".repeat(55) + "\n", DIM));
        sb.append(s(n.title(), BOLD));
        if (!n.type().isBlank()) sb.append(s("  [" + n.type() + "]", DIM));
        sb.append('\n');

        field(sb, "Site", siteName(n.siteId()));
        if (!n.altNames().isBlank())  field(sb, "Nomes Alt.", n.altNames());
        if (!n.author().isBlank())    field(sb, "Autor",      n.author());
        if (!n.status().isBlank())    field(sb, "Status",     n.status());
        if (!n.origin().isBlank())    field(sb, "Origem",     n.origin());
        if (n.totalChapters() > 0)    field(sb, "Capítulos",  String.valueOf(n.totalChapters()));

        int volCount = service.getVolumes().size();
        if (volCount > 0) field(sb, "Volumes", String.valueOf(volCount));

        if (n.favorites() > 0)       field(sb, "Favoritos",      String.valueOf(n.favorites()));
        if (!n.views().isBlank())     field(sb, "Visualizações",  n.views());

        if (!n.genres().isEmpty()) {
            field(sb, "Gêneros", String.join(", ", n.genres()));
        }

        sb.append(s("━".repeat(55) + "\n", DIM));

        // Synopsis
        if (!n.synopsis().isBlank()) {
            sb.append(s("Sinopse:\n", BOLD));
            sb.append(n.synopsis());
            sb.append('\n');
            sb.append(s("━".repeat(55), DIM));
        }

        return sb.toString();
    }

    private String chapterList(Volume vol) {
        var sb = new StringBuilder();
        sb.append(s("Livro %d: %s (%d capítulos)\n".formatted(
                vol.number(), vol.title(), vol.chapters().size()), BOLD));
        sb.append(s("─".repeat(55) + "\n", DIM));
        for (Chapter ch : vol.chapters()) {
            sb.append(s("%5d. ".formatted(ch.globalNumber()), BOLD));
            if (ch.title() != null && !ch.title().isBlank()) {
                sb.append(ch.title());
            } else {
                sb.append("Cap. %d".formatted(ch.number()));
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private void field(StringBuilder sb, String label, String value) {
        sb.append(s(label + ": ", DIM)).append(value).append('\n');
    }

    // ── Input helpers ─────────────────────────────────────────────────────────

    private int promptInt(String prompt, int min, int max) {
        while (true) {
            try {
                String raw = lineReader.readLine(prompt).trim();
                int val = Integer.parseInt(raw);
                if (val >= min && val <= max) return val;
                print(s("Por favor, insira um número entre %d e %d.".formatted(min, max), YELLOW));
            } catch (NumberFormatException e) {
                print(s("Número inválido — tente novamente.", YELLOW));
            } catch (UserInterruptException | EndOfFileException e) {
                return 0;
            }
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String s(String text, AttributedStyle style) {
        return new AttributedStringBuilder().style(style).append(text).toAnsi();
    }

    private void print(String text) {
        System.out.println(text);
    }

    private String noNovelSelected() {
        return s("Nenhuma novel selecionada. Use ", YELLOW)
             + s("search <título>", BOLD)
             + s(" primeiro.", YELLOW);
    }

    private static String siteName(String siteId) {
        return switch (siteId == null ? "" : siteId) {
            case "hidarinovel"   -> "HidariNovel";
            case "novelsbr"     -> "Novels BR";
            case "centralnovel" -> "Central Novel";
            default             -> siteId;
        };
    }

    private String formatTimestamp(String ts) {
        try {
            Instant instant = Instant.parse(ts.contains("T") ? ts : ts + "T00:00:00Z");
            return DATE_FMT.format(instant);
        } catch (Exception e) {
            return ts;
        }
    }
}

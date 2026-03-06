# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Run interactively (development)
mvn spring-boot:run

# Build fat JAR and run
mvn clean package
java -jar target/hidarinovel-1.0.0.jar

# Compile only (fast check)
mvn compile

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=ClassName

# Run a single test method
mvn test -Dtest=ClassName#methodName
```

Once the Spring Shell prompt appears, type `help` to list all commands.

## Architecture

The app is a **Spring Shell CLI** that scrapes web novel sites, then exports chapters as EPUB or PDF.

### Request flow

1. The user runs a shell command (e.g. `search`, `download`, `read`).
2. **`NovelCommands`** (sole `@ShellComponent`) delegates to **`NovelService`**.
3. `NovelService` holds **session state** (currently selected novel, loaded volumes/chapters, active scraper). This state persists across commands within a single CLI session.
4. `NovelService` routes calls to the matching **`SiteScraperAdapter`** via **`ScraperRegistry`**.
5. Scrapers return model objects (`Novel`, `Volume`, `Chapter`, `ChapterContent`).
6. `NovelService` hands content to **`EpubBuilder`** or **`PdfExporter`** and writes files to disk.

### Adding a new site scraper

Create a `@Component` that implements `SiteScraperAdapter`. It will be auto-discovered by `ScraperRegistry` — no other changes needed. Inject `UserAgentProvider` via constructor for random UA rotation.

### Key design constraints

- **`FavoritesStore`** keys entries by `slug` only (not `siteId + slug`). If two different sites share a slug, the second will silently overwrite the first.
- **`globalNumber`** is a sequential 1-based counter assigned across all volumes in load order. It is **not** the chapter number shown on the site — it is only meaningful within a single session.
- `NovelService.selectNovelBySlug()` hard-codes URL patterns per site; update it when adding a new scraper.
- Throttle delay between chapter fetches is controlled by `novelmania.throttle-ms` (default 800 ms, shared by all scrapers via `hidarinovel.throttle-ms`).

### Progress callbacks

Download methods accept a `ProgressCallback(int current, int total, String label)` instead of a plain `Consumer<String>`. `NovelCommands.printProgressBar()` renders the visual bar using `\r\033[K`.

### Terminal reader (`read` command)

Uses JLine raw mode (`terminal.enterRawMode()` / `terminal.setAttributes(saved)`). Always restore attributes in a `finally` block. Terminal width/height come from `terminal.getWidth()` / `terminal.getHeight()`. Both `Terminal` and `LineReader` must be injected `@Lazy`.

## Configuration (`application.properties`)

| Property | Default | Purpose |
|---|---|---|
| `novelmania.throttle-ms` | `800` | ms delay between chapter HTTP fetches |
| `hidarinovel.output-dir` | `~/Documents/shared/others/Novels` | base download directory |

## Model summary

| Record | Key fields |
|---|---|
| `Novel` | `siteId`, `slug`, `url`; partial constructor for search results, `withDetails()` for detail page |
| `Volume` | `number`, `title`, `chapters`; `firstGlobal()` / `lastGlobal()` |
| `Chapter` | `globalNumber` (session sequential), `number` (within volume), `volumeNumber`, `url` |
| `ChapterContent` | `chapter`, `title`, `volumeLabel`, `htmlBody` (images already inlined as data URIs) |
| `FavoriteEntry` | `siteId`, `slug`, `lastKnownChapters`, `lastDownloadedChapter`; Jackson-persisted |

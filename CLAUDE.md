# Profile Scraper Agent

AI-powered recruitment candidate scraper: searches LinkedIn, Naukri, and Indeed (via Playwright
or SerpAPI), extracts profile data with Gemini 3.6 Flash, and exports it to Excel. Ships with a
JMIX Flow UI (Vaadin 24 + Spring Boot 3) web app and a standalone CLI, sharing the same core
(`ProfileScraperAgent`).

## Tech stack

- Java 17, Maven (no wrapper — `mvn` must be on PATH)
- Spring Boot 3.5.11 + JMIX 2.8 (Flow UI / Vaadin platform 24.9.12, Flow 24.9.13) — web UI
- Gemini 3.6 Flash (Google AI) via `HttpURLConnection` (`com.profilescraper.Http`) — no SDK dependency
- Microsoft Playwright 1.44 — portal browser automation (LinkedIn/Naukri/Indeed login scraping)
- SerpAPI — credential-free LinkedIn discovery via Google Search
- Apache POI 5.2.5 — Excel export
- H2 in-memory DB — JMIX's own metadata store only; this app defines no JPA entities

## Entry points

- `com.profilescraper.ProfileScraperApplication` — Spring Boot web app (embedded Tomcat, port 8080)
- `com.profilescraper.Main` — CLI (`java -jar ... "job description"`, or interactive prompt with no args)

Both go through `ProfileScraperAgent`. The two differ on a *missing* key: the web app fails at
Spring startup (`${GEMINI_API_KEY}` placeholder can't resolve → `BeanCreationException` on
`scraperService`), while the CLI's no-arg constructor defaults the key to `""` and starts fine,
failing later at the first Gemini call.

## Build & run

```bash
mvn clean package                    # full build incl. Vaadin frontend prep + both jars
mvn spring-boot:run                  # run the WEB APP (needs GEMINI_API_KEY in the environment)
java -jar target/profile-scraper-agent-1.0.0-fat.jar "job description"   # CLI, one-shot
java -jar target/profile-scraper-agent-1.0.0-fat.jar                     # CLI, interactive prompt
```

**`mvn spring-boot:run` is the only way to start the web app.** Neither built jar launches it:
`spring-boot-maven-plugin` declares no `<executions>`, so `repackage` never runs and no Spring
Boot executable jar is produced. Both `profile-scraper-agent-1.0.0.jar` and
`...-1.0.0-fat.jar` carry `Main-Class: com.profilescraper.Main` — the **CLI**. (The README's
`java -jar target/profile-scraper-agent-1.0.0.jar` → web app instruction is wrong; it silently
starts the CLI instead.) The plain jar also needs `target/lib/` beside it (`classpathPrefix=lib/`,
populated by `dependency:copy-dependencies`); the `-fat` shaded jar is self-contained.

First-time Playwright setup (only needed for LinkedIn/Naukri/Indeed portal-login scraping, not
for SerpAPI or the Gemini AI search path):
```bash
mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
```

Login is **not required** to reach the web UI — `SecurityConfig` registers a permit-all filter
chain at `@Order(1)` that runs before JMIX's own `@Order(400)` login-wall config, bypassing it
entirely. (The README's "admin/admin" login is stale; there's no auth gate to hit.)

## Required / optional environment variables

| Variable | Required | Purpose |
|---|---|---|
| `GEMINI_API_KEY` | Yes (for either entry point) | Read via `${GEMINI_API_KEY}` in `application.properties` → `gemini.api-key` → injected into `ScraperService`'s constructor |
| `SERP_API_MAX_RESULTS` | No | Overrides the default 20-profile result cap (KAN-27). Despite the name it is read in **two** places — `SerpApiScraper` *and* `ProfileScraperAgent` — so it caps the Gemini AI search path too, not just SerpAPI. Blank/non-numeric/≤0 silently falls back to 20. The two `DEFAULT_MAX_RESULTS` constants are separate copies; change both or the paths diverge |

SerpAPI and portal (LinkedIn/Naukri/Indeed) credentials are entered directly in the web UI per
search, not via environment variables.

## Project structure

```
src/main/java/com/profilescraper/
├── ProfileScraperApplication.java   # Spring Boot entry point (web)
├── Main.java                        # CLI entry point
├── ProfileScraperAgent.java         # Gemini AI integration & orchestration (shared by both entry points)
├── ExcelExporter.java               # .xlsx generation with hyperlinks, colour-coded status
├── AppConfig.java                   # scraperExecutor thread pool bean (2-4 threads)
├── LocationFilter.java              # enforces the UI Location field against scraper output
├── model/CandidateProfile.java      # candidate data model
├── service/ScraperService.java      # Spring @Service — routes to Gemini AI search or a PortalScraper
├── scraper/
│   ├── JobPortal.java                # portal enum (LINKEDIN, NAUKRI, INDEED, SERP_API)
│   ├── PortalScraper.java            # scraper interface
│   ├── AbstractPortalScraper.java    # Playwright base class with anti-detection
│   ├── PortalScraperFactory.java     # picks a scraper impl by JobPortal
│   ├── LinkedInScraper.java / NaukriScraper.java / IndeedScraper.java   # Playwright login scrapers
│   └── SerpApiScraper.java           # Google Search (site:linkedin.com/in/) scraper, no login
├── view/MainView.java                # Vaadin UI — search form + results grid
└── security/SecurityConfig.java, InMemoryUserRepository.java
```

`src/main/frontend/generated/**` is Vaadin-generated build output — never hand-edit it; it's
regenerated by `vaadin:prepare-frontend` / `vaadin:build-frontend` and will show as a large,
noisy diff after any build. Treat changes there as build artifacts, not code review targets.

## Gotchas specific to this app

- **Keep build-plugin versions pinned.** Both plugins below were once declared without a
  `<version>`, so Maven resolved whatever the newest release happened to be — and broke:
  - `vaadin-maven-plugin`, pinned to **`24.9.12`**. Unpinned it grabbed `25.2.4` against this
    project's Vaadin 24.x jars and failed `prepare-frontend` outright with `NoSuchMethodError`
    on `EngineAutoConfiguration$Builder`. Pin to the **platform** version (`vaadin-core`), not
    the **Flow** version: jmix-bom 2.8.0 resolves platform `24.9.12` but Flow `24.9.13` (Flow
    runs slightly ahead). Pinning `24.9.13` builds, but warns on every run. Bump with `jmix.version`.
  - `spring-boot-maven-plugin`, pinned to **`3.5.11`** to match the Spring Boot jmix-bom brings
    in. Unpinned it resolved to `4.1.0` — a major version ahead of the running framework.
- **`ScraperService`'s constructor builds a `ProfileScraperAgent` eagerly** — a missing
  `GEMINI_API_KEY` fails Spring context startup (`BeanCreationException` on `scraperService`),
  not a later HTTP call.
- **HTTP goes through `com.profilescraper.Http` (`HttpURLConnection`), never
  `java.net.http.HttpClient`.** Constructing an `HttpClient` opens an NIO selector, and on hosts
  where that fails every request dies before it is sent, with no system property to disable the
  behaviour. These are one-shot request/response calls that use none of what `HttpClient` adds
  (HTTP/2, async, pooling). Don't reintroduce it.
- **The Gemini model is pinned** in `ProfileScraperAgent.MODEL` and Google retires models. When
  one goes, the API returns HTTP 404 `"This model ... is no longer available"` and names the
  replacement in the message — that is the authoritative source, not a guess. `gemini-2.0-flash`
  was retired this way and is now `gemini-3.6-flash`.
- `AbstractPortalScraper` carries the shared Playwright setup for the three login-based
  scrapers; changes there affect LinkedIn, Naukri, and Indeed simultaneously. `SerpApiScraper`
  also extends it but overrides `scrapeProfiles` to use plain HTTP, ignoring the browser path.
- **Search criteria are not self-enforcing, and filtering alone is not enough.** A constraint
  has to reach the *query* as well as the output filter. Location was filter-only at first, so
  a search for Mumbai asked Google for recruiters worldwide, discarded most of them, and kept
  any whose city the snippet didn't state. It now goes into the query (`SerpApiScraper`
  quotes it; the Playwright scrapers prepend it) *and* is re-checked by `LocationFilter`.
- **`LocationFilter.cities` is the single parse of the Location field** — semicolons separate
  alternatives, commas narrow one place. Every consumer must use it. When the query parsed the
  raw text and the filter parsed just the city, `Mumbai, India only` went to Google as a
  literal phrase that matched nothing. The filter requires a *stated* matching location:
  blanks are dropped, because roughly half of `SerpApiScraper`'s results have no location and
  keeping them returned Canada and Australia for a Mumbai search. On the AI path it runs
  *before* the result cap so the cap is spent on matching candidates; on the portal paths it
  runs in `ScraperService` after the scraper's own cap, so results can come back short. Gemini `temperature` is 0.4, not the default 1.0, for the same reason.
- `MainView` is a hybrid: a Jmix `StandardView` whose buttons come from the XML descriptor
  (`src/main/resources/com/profilescraper/view/main_view.xml`) via `@ViewComponent`, while the
  form fields (portal `ComboBox`, username/password, grid) are built programmatically in
  `initPortalSection()` / `initGrid()`, called from `@Subscribe onInit`. Adding a control means
  deciding which half it belongs to.

## Known environment issue on this machine (Windows Enterprise, this dev box)

Modern JDKs (confirmed on 21 and this box's 17.0.12) hardcode their NIO `Selector` wakeup pipe to
attempt a Unix-domain-socket loopback `connect()` first (`sun.nio.ch.PipeImpl`, no fallback, not
configurable via any system property). On this machine that `connect()` fails with
`java.net.SocketException: Invalid argument`, so **any** Java process that opens an NIO
`Selector` — including a bare `Selector.open()` with no app code involved — throws
`IOException: Unable to establish loopback connection`. Reproduced identically via this session's
shell and a native PowerShell process, so it's not a sandboxing artifact — most likely AV/EDR/VPN
software intercepting Winsock AF_UNIX calls. If you hit this, it is a host problem, not a
regression in this repo. Outbound networking itself is fine: `HttpURLConnection` reaches the
public internet from this box without trouble.

**To run the app on an affected machine:**
```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.tomcat.nio2=true"
```
`server.tomcat.nio2=true` swaps Tomcat's default NIO connector for NIO2, which uses IOCP on
Windows rather than a selector (see `AppConfig.nio2Connector`). It is opt-in and does nothing
when unset, so production is unaffected. Two things opened selectors and both are now handled:
the app's own HTTP moved off `java.net.http.HttpClient` (see the gotcha above), and this property
gets Tomcat's connector up. Verified end to end on this box — the app starts, serves `:8080`,
renders the UI, and reaches the Gemini API.

The build is unaffected too, but keep `ProfileScraperAgent.applyResultLimit` `static` so
`ProfileScraperAgentTest` never has to construct an agent for what is a pure list test.

This is not permanent machine state: the selector worked here on 2026-07-30 and has failed
consistently since. Treat it as "currently broken here" and re-check rather than assuming.

## Coding conventions observed in this codebase

- Section-comment banners (`// ─── Section ───`) divide large classes into logical blocks
  (see `SerpApiScraper.java`, `ScraperService.java`).
- Regex patterns used for scraping are `private static final Pattern` fields with a one-line
  Javadoc example of what they match, declared near the top of the class.
- Jira ticket references (e.g. `KAN-27`) appear in comments next to behavior that ticket
  introduced — keep that trail when touching related code.

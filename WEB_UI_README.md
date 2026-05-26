# Profile Scraper Agent — JMIX Web UI Setup

## Overview

Your Profile Scraper project now has **two interfaces**:

1. **CLI (Command-Line Interface)** — Original, lightweight, no UI framework
2. **Web UI (JMIX + Spring Boot)** — Interactive, sortable, filterable data grid

Both can run independently from the same codebase.

---

## Running the Web UI

### Prerequisites

1. **Java 17+** installed and on PATH
2. **ANTHROPIC_API_KEY** environment variable set
   ```powershell
   $env:ANTHROPIC_API_KEY="sk-ant-..."
   ```

### Build

```bash
mvn clean package
```

### Run the Web Application

```bash
java -Dspring.profiles.active=web -jar target/profile-scraper-agent-1.0.0.jar
```

Or with Spring Boot Maven plugin:

```bash
mvn spring-boot:run
```

### Access

- **URL:** `http://localhost:8080`
- **Default login:** admin / admin (if JMIX login is enabled)

### Features

- **Job Description Input** — Textbox for role requirements
- **Search Button** — Triggers Claude Opus 4.6 web search
- **Results Data Grid:**
  - Columns: Name, Title, Company, Location, Experience, Skills, Match Score, Email
  - **Sorting:** Click column headers to sort ascending/descending
  - **Filtering:** JMIX provides built-in filter UI (properties panel)
  - **Pagination:** Automatic for large datasets (100+ rows)
  - **Column Resizing:** Drag column borders to adjust width
- **Export to Excel** — Download results as formatted .xlsx file

---

## Running the CLI

```bash
java -jar target/profile-scraper-agent-fat.jar "Senior React Developer, 5+ years, Mumbai, fintech"
```

Or interactive mode:

```bash
java -jar target/profile-scraper-agent-fat.jar
```

The CLI remains **unchanged** and fully functional.

---

## Project Structure

```
src/main/java/com/profilescraper/
├── Main.java                          # CLI entry point (UNCHANGED)
├── SpringApplication.java             # Web UI entry point (NEW)
├── ProfileScraperAgent.java           # Core Claude integration (UNCHANGED)
├── ExcelExporter.java                 # Excel export (SHARED)
├── model/
│   └── CandidateProfile.java          # Data model (UNCHANGED)
├── service/
│   └── ScraperService.java            # Spring service wrapper (NEW)
└── screen/
    └── MainScreen.java                # JMIX controller (NEW)

src/main/resources/
├── application.properties              # Anthropic API key
├── application.yml                     # JMIX + Spring Boot config (NEW)
├── com/profilescraper/
│   ├── menu.xml                        # JMIX menu definition (NEW)
│   ├── screens.xml                     # JMIX screen registration (NEW)
│   └── screen/
│       └── main_screen.xml             # JMIX screen layout (NEW)
```

---

## Dependencies Added

- **Spring Boot 3.3.1** — Web framework, dependency injection
- **JMIX 4.8.4** — Enterprise UI framework
  - Built-in components: DataGrid, Forms, Buttons, Notifications
  - Automatic sorting, filtering, pagination
  - Layout management and responsive design
- **H2 Database** — In-memory database (for JMIX metadata)

---

## Configuration

Edit `src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: profile-scraper-agent
  datasource:
    url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false

server:
  port: 8080

jmix:
  ui:
    theme: helium  # Or: cuba, halo, hover
```

---

## Troubleshooting

### Build Error: "JMIX components not found"

Ensure you're using the correct Maven version:

```bash
mvn clean package -U
```

The `-U` flag forces Maven to update snapshots.

### Web UI Doesn't Load

1. Check logs: `tail -f target/logs/app.log`
2. Verify port 8080 is not in use
3. Ensure `ANTHROPIC_API_KEY` is set

### Search Takes Too Long

Claude's web search can take 2-5 minutes. The UI shows a "Searching..." status. This is normal.

---

## Next Steps / Customization

### Change Theme

In `application.yml`:

```yaml
jmix:
  ui:
    theme: hover  # Alternatives: cuba, halo, hover
```

### Add More Columns

Edit `src/main/resources/com/profilescraper/screen/main_screen.xml`:

```xml
<column property="summary" width="400px">
    <caption>Summary</caption>
</column>
```

### Customize Search Behavior

Modify `MainScreen.java` → `onSearch()` method to add:
- Result caching
- Batch processing
- Custom filtering logic

### Deploy to Production

Use Spring Boot's built-in:

```bash
java -jar profile-scraper-agent-1.0.0.jar \
  --server.port=9000 \
  --spring.datasource.url=jdbc:h2:file:./data/app
```

---

## Comparison: CLI vs Web UI

| Feature | CLI | Web UI |
|---------|-----|---------|
| **Interface** | Terminal | Browser |
| **Output** | Excel + Console | Grid + Download |
| **Sorting/Filtering** | None | Built-in |
| **Dependencies** | Minimal | Spring Boot + JMIX |
| **Memory Usage** | ~100 MB | ~500 MB |
| **Best For** | Scripting, automation | Interactive browsing |

---

## Questions?

Refer to:
- [JMIX Docs](https://docs.jmix.io)
- [Spring Boot Docs](https://spring.io/projects/spring-boot)
- [Anthropic API](https://docs.anthropic.com)

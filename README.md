# Profile Scraper Agent

AI-powered recruitment candidate profile scraper with a web UI and CLI interface. Searches LinkedIn, Naukri, and Indeed using Gemini 2.0 Flash intelligence and exports results to Excel.

---

## Features

- **Multi-portal search** — LinkedIn (Playwright), Naukri, Indeed, and SerpAPI (credential-free Google Search)
- **AI-powered extraction** — Gemini 2.0 Flash parses candidate details from raw page content
- **Web UI** — JMIX Flow UI (Vaadin 24) with sortable, filterable data grid
- **CLI mode** — Lightweight terminal interface for scripting and automation
- **Excel export** — Formatted `.xlsx` with clickable profile links, colour-coded Open to Work status
- **Pagination** — Fetches up to 30+ candidates across multiple search pages
- **Status tracking** — Detects "Open to Work" and last-updated date per profile

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Web framework | Spring Boot 3 + JMIX 2.8 (Flow UI / Vaadin 24) |
| AI / parsing | Gemini 2.0 Flash (Google AI) |
| Browser automation | Microsoft Playwright 1.44 |
| LinkedIn discovery | SerpAPI (Google Search — no login required) |
| Excel generation | Apache POI 5.2.5 |
| JSON parsing | Jackson 2.15 |
| Database | H2 in-memory (JMIX metadata store) |
| Build | Maven 3.8+ |

---

## Prerequisites

- **Java 17+** on PATH
- **Maven 3.8+**
- **Gemini API key** — [Get one here](https://aistudio.google.com/app/apikey)
- **SerpAPI key** *(optional, for credential-free LinkedIn search)* — [serpapi.com](https://serpapi.com)
- **Portal credentials** *(optional, for Playwright-based scraping)*

---

## Quick Start

### 1. Clone and build

```powershell
git clone <repo-url>
cd profile-scraper
mvn clean package
```

### 2. Install Playwright browser (first time only)

```powershell
mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
```

### 3. Set environment variables

```powershell
$env:GEMINI_API_KEY   = "your-gemini-api-key"
$env:SERP_API_KEY     = "your-serpapi-key"        # optional
```

### 4. Run the web application

```powershell
java -jar target/profile-scraper-agent-1.0.0.jar
```

### 5. Open the app

```
http://localhost:8080
```

Login: **admin / admin**

---

## Using the Web UI

1. **Enter a job description** — e.g. `"Senior Java Developer, 7+ years, Bangalore, fintech"`
2. **Click Search Candidates** — the AI agent queries configured portals (takes 2–5 minutes)
3. **Browse results** in the grid — sort by any column, scroll through pages
4. **Click Export to Excel** — downloads `candidates_[role]_[date].xlsx`
5. **Click Clear** to start a new search

### Grid columns

| Column | Description |
|---|---|
| Name | Candidate full name |
| Title | Current job title |
| Company | Current employer |
| Location | City / region |
| Experience | Years of experience |
| Key Skills | Comma-separated skill tags |
| Email | Contact email (when available) |
| Status | Open to Work / Active / Not specified |
| Last Updated | Profile freshness date |
| Profile URL | Clickable link to the source profile |

---

## CLI Mode

```powershell
# One-shot search
java -jar target/profile-scraper-agent-fat.jar "Senior React Developer, 5+ years, Mumbai, fintech"

# Interactive mode
java -jar target/profile-scraper-agent-fat.jar
```

The CLI outputs results to the console and saves an Excel file in the working directory.

---

## Project Structure

```
src/main/java/com/profilescraper/
├── ProfileScraperApplication.java   # Spring Boot entry point (web)
├── Main.java                        # CLI entry point
├── ProfileScraperAgent.java         # Gemini AI integration & orchestration
├── ExcelExporter.java               # Excel (.xlsx) generation with hyperlinks
├── AppConfig.java                   # Spring configuration
├── model/
│   └── CandidateProfile.java        # Candidate data model
├── service/
│   └── ScraperService.java          # Spring service — routes to AI or portal scrapers
├── scraper/
│   ├── JobPortal.java               # Supported portal enum
│   ├── PortalScraper.java           # Scraper interface
│   ├── AbstractPortalScraper.java   # Playwright base class with anti-detection
│   ├── PortalScraperFactory.java    # Factory — selects scraper by portal
│   ├── LinkedInScraper.java         # LinkedIn Playwright scraper (up to 5 pages)
│   ├── NaukriScraper.java           # Naukri Playwright scraper
│   ├── IndeedScraper.java           # Indeed Playwright scraper
│   └── SerpApiScraper.java          # SerpAPI Google Search scraper (3 pages)
├── view/
│   └── MainView.java                # Vaadin UI — search form + results grid
└── security/
    ├── SecurityConfig.java          # Spring Security config
    └── InMemoryUserRepository.java  # Default admin user
```

---

## Supported Portals

| Portal | Method | Credentials needed |
|---|---|---|
| **LinkedIn** | Playwright browser automation | LinkedIn username + password |
| **Naukri** | Playwright browser automation | Naukri username + password |
| **Indeed** | Playwright browser automation | Indeed username + password |
| **SerpAPI** | Google Search API | `SERP_API_KEY` only — no login |

> SerpAPI is the recommended starting point — it discovers publicly indexed LinkedIn profiles without requiring any portal login credentials.

---

## Configuration

Edit `src/main/resources/application.yml`:

```yaml
server:
  port: 8080          # change if port is in use

vaadin:
  push-mode: automatic   # enables real-time UI updates from background threads

logging:
  level:
    com.profilescraper: DEBUG   # set to INFO to reduce log noise
```

---

## Building for Production

```powershell
mvn vaadin:build-frontend -Pproduction
mvn clean package -Pproduction
java -jar target/profile-scraper-agent-1.0.0.jar
```

---

## Troubleshooting

### Port 8080 already in use
```powershell
java -jar target/profile-scraper-agent-1.0.0.jar --server.port=9090
# Then open: http://localhost:9090
```

### GEMINI_API_KEY not found
```powershell
$env:GEMINI_API_KEY = "your-key"
java -jar target/profile-scraper-agent-1.0.0.jar
```

### Build fails — dependency errors
```powershell
mvn clean package -U    # -U forces snapshot update
```

### Search hangs or returns no results
- Confirm `GEMINI_API_KEY` is set and valid
- For portal scraping: verify portal credentials are correct
- For SerpAPI: confirm `SERP_API_KEY` is set
- Allow 2–5 minutes — Gemini web search is intentionally thorough

### Playwright: browser not found
```powershell
mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
```

---

## License

Internal tool — not licensed for redistribution.

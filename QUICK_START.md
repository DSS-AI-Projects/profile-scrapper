# JMIX Web UI — Quick Start

## 1. Build the Project

```powershell
cd c:\claude\code\workspace\profile-scraper
mvn clean package
```

**Expected output:**
```
[INFO] BUILD SUCCESS
[INFO] Total time: ...
```

---

## 2. Run the Web UI

```powershell
java -jar target/profile-scraper-agent-1.0.0.jar
```

**Expected output:**
```
  ╭─────────────────────────────────────╮
  │  Application startup completed in ... │
  ╰─────────────────────────────────────╯
  >>> App is running at: http://localhost:8080
```

---

## 3. Access the App

Open your browser and navigate to:

```
http://localhost:8080
```

### Login
- **Username:** admin  
- **Password:** admin

---

## 4. How to Use

1. **Enter Job Description**
   - Paste a job requirement in the textarea
   - Example: "Senior React Developer, 5+ years, Mumbai, fintech"

2. **Click Search Candidates**
   - The AI agent will search multiple job portals
   - Status shows: "Searching... may take 2-5 minutes"
   - Grid auto-populates with results

3. **Interact with Results**
   - **Sort:** Click any column header
   - **Filter:** (Optional) Right-click grid → Filter
   - **Scroll:** Results paginate automatically (100+ rows)
   - **Resize:** Drag column borders to adjust width

4. **Export Results**
   - Click **Export to Excel** button
   - Download as `candidates_[role]_[date].xlsx`
   - Opens in Excel with formatting applied

5. **Clear & Reset**
   - Click **Clear** button to start a new search

---

## 5. Architecture

```
User Input (Web Browser)
         ↓
    JMIX UI Framework
         ↓
    MainScreen Controller
         ↓
    ScraperService (Spring)
         ↓
    ProfileScraperAgent
         ↓
    Claude Opus 4.6 + Web Search
         ↓
    Parse Results → DataGrid
         ↓
    Export to Excel (Optional)
```

---

## 6. Features at a Glance

| Feature | Status | Notes |
|---------|--------|-------|
| **Interactive Search** | ✓ | Non-blocking, async background thread |
| **Data Grid** | ✓ | 8 columns: Name, Title, Company, Location, Experience, Skills, Score, Email |
| **Sorting** | ✓ | Click column headers; ascending/descending toggle |
| **Filtering** | ✓ | JMIX built-in filter UI available |
| **Pagination** | ✓ | Automatic for 100+ results |
| **Column Resizing** | ✓ | Drag borders to adjust width |
| **Export** | ✓ | Download as formatted Excel file |
| **Status Messages** | ✓ | Real-time feedback during search |
| **Responsive Design** | ✓ | Works on desktop & tablet |

---

## 7. Configuration

Edit `src/main/resources/application.yml`:

```yaml
server:
  port: 8080                    # Change port if needed

jmix:
  ui:
    theme: helium               # Options: helium, hover, cuba, halo
    
spring:
  jpa:
    show-sql: false             # Set true for SQL debugging
```

---

## 8. Troubleshooting

### **"Port 8080 already in use"**
```powershell
java -jar target/profile-scraper-agent-1.0.0.jar --server.port=9000
# Then access: http://localhost:9000
```

### **"ANTHROPIC_API_KEY not found"**
```powershell
$env:ANTHROPIC_API_KEY="sk-ant-..."
java -jar target/profile-scraper-agent-1.0.0.jar
```

### **"Build fails with dependency errors"**
```powershell
mvn clean package -U
# -U forces Maven to update snapshots
```

### **"Search button doesn't work or hangs"**
- Check console logs for errors
- Verify ANTHROPIC_API_KEY is set
- Wait 2-5 minutes (Claude search is slow, intentional)

---

## 9. CLI Still Works!

If you prefer the command-line interface:

```powershell
java -jar target/profile-scraper-agent-fat.jar "Senior Java Developer, 5+ years, Bangalore"
```

Both CLI and Web UI coexist in the same build.

---

## 10. Next Steps & Customization

### Add More Columns
Edit `src/main/resources/com/profilescraper/screen/main_screen.xml`:
```xml
<column property="summary" width="300px">
    <caption>Summary</caption>
</column>
```

### Change Theme
In `application.yml`:
```yaml
jmix:
  ui:
    theme: hover  # sleek dark theme
```

### Increase Search Timeout
In `ScraperService.java`:
- Modify `MAX_RETRIES` in ProfileScraperAgent (default: 4)
- Increase to 6-8 for more thorough searches

### Enable Database Persistence
- Add H2 file database in application.yml
- Persist search history & results
- Create a "Recent Searches" list

---

## 11. File Locations

```
src/main/java/
├── SpringApplication.java           ← Web entry point
├── screen/MainScreen.java           ← UI controller
├── service/ScraperService.java      ← Business logic
└── ProfileScraperAgent.java         ← Core agent (unchanged)

src/main/resources/
├── application.yml                  ← Config
└── com/profilescraper/
    ├── menu.xml                     ← Menu definition
    ├── screens.xml                  ← Screen registration
    └── screen/main_screen.xml       ← UI layout
```

---

## 12. Support & Documentation

- **JMIX Docs:** https://docs.jmix.io
- **Spring Boot:** https://spring.io/projects/spring-boot
- **Anthropic API:** https://docs.anthropic.com

Enjoy! 🚀

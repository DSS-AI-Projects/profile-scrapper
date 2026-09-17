package com.profilescraper.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.profilescraper.model.CandidateProfile;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Candidate scraper that uses the <a href="https://serpapi.com">SerpAPI</a>
 * Google Search API to find publicly-indexed LinkedIn profiles — no browser
 * login or Playwright required.
 *
 * <h3>Data extraction strategy (layered)</h3>
 * <ol>
 *   <li><b>Google page title</b> — reliable source for name, current title,
 *       and company (pattern: "Name - Title at Company | LinkedIn").</li>
 *   <li><b>{@code rich_snippet.top.extensions}</b> — structured array that
 *       Google extracts from LinkedIn's schema.org markup; typically contains
 *       {@code ["Job Title", "City, Country"]}.</li>
 *   <li><b>Snippet</b> — LinkedIn's meta description indexed by Google.
 *       Split on {@code ·} / {@code |} separators, then apply regex patterns
 *       for location, years of experience, skills, company, and email.</li>
 * </ol>
 *
 * <h3>Email</h3>
 * Email addresses are almost never present in public Google / LinkedIn snippets.
 * The scraper attempts a regex match and records any address found, but in
 * practice this field will usually remain empty for SERP API results.
 */
public class SerpApiScraper extends AbstractPortalScraper {

    private static final String      SERPAPI_ENDPOINT  = "https://serpapi.com/search.json";
    private static final int         RESULTS_PER_PAGE  = 10;  // Google returns 10 per page
    private static final int         MAX_PAGES         = 3;   // 3 API calls → up to 30 results
    private static final int         DEFAULT_MAX_RESULTS = 20; // KAN-27: hard cap on profiles returned
    /**
     * Hard cap on the number of profiles returned by this scraper (KAN-27).
     * Defaults to {@value #DEFAULT_MAX_RESULTS}; can be overridden without a code
     * change via the optional {@code SERP_API_MAX_RESULTS} environment variable.
     */
    private static final int         MAX_RESULTS       = readMaxResults();
    private static final ObjectMapper MAPPER           = new ObjectMapper();

    /**
     * Reads the optional {@code SERP_API_MAX_RESULTS} env-var override, parsing it
     * defensively. Falls back to {@link #DEFAULT_MAX_RESULTS} when the variable is
     * absent, blank, non-numeric, or not a positive integer.
     */
    private static int readMaxResults() {
        String raw = System.getenv("SERP_API_MAX_RESULTS");
        if (raw == null || raw.isBlank()) return DEFAULT_MAX_RESULTS;
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_MAX_RESULTS;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_RESULTS;
        }
    }

    // ── Precompiled regex patterns ────────────────────────────────────────────────

    /** "8 years", "8+ years", "8 yrs", "over 8 years of experience" */
    private static final Pattern EXP_PATTERN = Pattern.compile(
            "(?:over\\s+|more\\s+than\\s+)?(\\d+)\\+?\\s*(?:years?|yrs?)",
            Pattern.CASE_INSENSITIVE);

    /** "Skills: Java, Spring Boot, …" / "Expertise: …" / "Proficient in …" */
    private static final Pattern SKILLS_PATTERN = Pattern.compile(
            "(?i)(?:skills?|expertise|specializ(?:es?|ed|ing)\\s+in|"
            + "proficient\\s+in|experienced\\s+in)[:\\-\\s]+([^.\\n]{5,150})");

    /** "at CompanyName" or "@ CompanyName" inside snippet text */
    private static final Pattern COMPANY_PATTERN = Pattern.compile(
            "(?:^|\\s)(?:at|@)\\s+([A-Z][a-zA-Z0-9 &.,''\\-]{2,60}?)"
            + "(?=\\s*[.,|·\\n]|\\s*in\\s|\\s*since|\\s*$)",
            Pattern.MULTILINE);

    /** Standard e-mail address */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    /** "Updated 3 days ago", "Active 2 weeks ago", "Recently active" */
    private static final Pattern UPDATED_PATTERN = Pattern.compile(
            "(?:updated|active|last\\s+seen)\\s+"
            + "(?:\\d+\\s+(?:day|week|month|hour)s?\\s+ago|recently|just\\s+now)",
            Pattern.CASE_INSENSITIVE);

    /**
     * "City, State, Country" or "City, Country" inline in text.
     * Must start with a capital letter; terminated by a separator or end-of-string.
     */
    private static final Pattern LOCATION_INLINE = Pattern.compile(
            "\\b([A-Z][a-zA-Z .'-]{1,25},\\s*[A-Z][a-zA-Z .'-]{1,25}"
            + "(?:,\\s*[A-Z][a-zA-Z .'-]{1,25})?)(?=[·|.,\\n]|\\s*\\d|$)");

    // ─────────────────────────────────────────────────────────────────────────────

    @Override
    public JobPortal getPortal() { return JobPortal.SERP_API; }

    // ─── Public entry point ───────────────────────────────────────────────────────

    /**
     * @param jobDescription natural-language job requirement used as keywords
     * @param apiKey         SerpAPI key (entered in the "SerpAPI Key" field in the UI)
     * @param ignored        not used (no password needed for SERP API)
     */
    @Override
    public List<CandidateProfile> scrapeProfiles(String jobDescription,
                                                  String apiKey,
                                                  String ignored) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new Exception(
                    "SerpAPI key is required. Get a free key at https://serpapi.com/manage-api-key");
        }

        String query = buildSerpQuery(jobDescription);
        logger.info("SerpAPI: query=[{}]", query);

        List<CandidateProfile> profiles = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            int start = page * RESULTS_PER_PAGE;
            logger.info("SerpAPI: fetching page {} (start={})", page + 1, start);

            String responseBody  = callSerpApi(query, apiKey.trim(), start);
            List<CandidateProfile> pageResults = parseResponse(responseBody);
            profiles.addAll(pageResults);

            logger.info("SerpAPI: page {} → {} result(s) (total so far: {})",
                    page + 1, pageResults.size(), profiles.size());

            if (pageResults.size() < RESULTS_PER_PAGE) {
                logger.info("SerpAPI: fewer than {} results on page {} — no more pages",
                        RESULTS_PER_PAGE, page + 1);
                break;   // Google has no more results for this query
            }

            // KAN-27: stop paginating once the result cap is reached to avoid
            // wasting further SerpAPI calls on results that will be truncated.
            if (profiles.size() >= MAX_RESULTS) {
                logger.info("SerpAPI: reached result cap of {} — stopping pagination",
                        MAX_RESULTS);
                break;
            }

            // Brief pause between API calls to be a good citizen
            if (page < MAX_PAGES - 1) {
                try { Thread.sleep(400); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        List<CandidateProfile> limited = applyResultLimit(profiles);
        logger.info("SerpAPI: {} profile(s) extracted, returning {} (cap={})",
                profiles.size(), limited.size(), MAX_RESULTS);
        return limited;
    }

    /**
     * Enforces the {@link #MAX_RESULTS} cap (KAN-27), preserving insertion order.
     * Returns a truncated copy of the first {@code MAX_RESULTS} profiles when the
     * input exceeds the cap, or the input list unchanged when it is at or below it.
     *
     * <p>Package-private so it can be unit-tested directly without touching the
     * network-calling HTTP path.
     */
    List<CandidateProfile> applyResultLimit(List<CandidateProfile> profiles) {
        if (profiles == null || profiles.size() <= MAX_RESULTS) return profiles;
        return new ArrayList<>(profiles.subList(0, MAX_RESULTS));
    }

    // ─── Query builder ────────────────────────────────────────────────────────────

    /**
     * Builds a concise, targeted Google Search query from the job description.
     *
     * <p>Strategy: strip English stop-words and generic HR filler phrases so only
     * meaningful technical / role terms remain (e.g. "Java Developer Spring Boot AWS").
     * A 20-word query with "we are looking for a candidate who has" returns far fewer
     * Google results than a 5-word query with the actual skill names.
     *
     * <p>The query is prefixed with {@code site:linkedin.com/in/} to restrict hits to
     * public LinkedIn profile pages.
     */
    private String buildSerpQuery(String jobDescription) {
        String cleaned = jobDescription
                // Keep letters, digits, common tech chars; collapse everything else to space
                .replaceAll("[^a-zA-Z0-9#+./&() ]", " ")
                // Remove common English stop-words and HR filler words (case-insensitive)
                .replaceAll("(?i)\\b(a|an|the|and|or|but|if|in|on|at|to|for|of|with|by|"
                        + "from|is|are|was|were|be|been|being|have|has|had|do|does|did|"
                        + "will|would|could|should|may|might|shall|can|need|must|"
                        + "we|our|us|you|your|they|their|it|its|this|that|these|those|"
                        + "who|which|what|when|where|how|all|any|each|every|both|more|"
                        + "looking|seeking|hiring|require|required|requirements|"
                        + "candidate|candidates|position|role|job|opening|vacancy|"
                        + "immediate|urgently|apply|application|joining|notice|period|"
                        + "good|strong|excellent|solid|sound|proven|hands|on|"
                        + "knowledge|understanding|experience|experienced|exp|yrs|years|"
                        + "minimum|maximum|least|plus|preferred|desirable|added|"
                        + "ability|able|capable|responsible|responsibilities|duty|duties|"
                        + "including|such|as|etc|also|well|may|work|team|"
                        + "please|send|submit|contact|email|resume|cv|salary|"
                        + "location|based|india|remote|onsite|hybrid)\\b", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();

        // Truncate at a word boundary, keeping the most meaningful leading terms
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80);
            int lastSpace = cleaned.lastIndexOf(' ');
            if (lastSpace > 30) cleaned = cleaned.substring(0, lastSpace);
        }
        cleaned = cleaned.trim();

        // Safety net: if cleaning wiped everything out fall back to raw first 60 chars
        if (cleaned.length() < 5) {
            cleaned = jobDescription.replaceAll("[^a-zA-Z0-9 ]", " ")
                    .trim().replaceAll("\\s+", " ");
            if (cleaned.length() > 60) cleaned = cleaned.substring(0, 60).trim();
        }

        logger.debug("SerpAPI buildSerpQuery: input=[{}] → query terms=[{}]",
                jobDescription.length() > 80 ? jobDescription.substring(0, 80) + "…" : jobDescription,
                cleaned);

        return "site:linkedin.com/in/ " + cleaned;
    }

    // ─── HTTP call ────────────────────────────────────────────────────────────────

    private String callSerpApi(String query, String apiKey, int start) throws Exception {
        String url = SERPAPI_ENDPOINT
                + "?engine=google"
                + "&q="       + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&num="     + RESULTS_PER_PAGE
                + (start > 0 ? "&start=" + start : "")   // omit start=0 (it's the default)
                + "&hl=en"
                + "&api_key=" + apiKey;                   // no gl= → global results

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        logger.info("SerpAPI: sending request …");
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            String body = response.body();
            if (body.contains("out of searches") || body.contains("run out"))
                throw new Exception(
                        "SerpAPI monthly quota exhausted. Upgrade at https://serpapi.com/pricing");
            if (body.contains("Invalid API key") || body.contains("invalid api key"))
                throw new Exception(
                        "Invalid SerpAPI key. Verify at https://serpapi.com/manage-api-key");
            throw new Exception("SerpAPI returned HTTP " + response.statusCode()
                    + ": " + body.substring(0, Math.min(300, body.length())));
        }

        logger.info("SerpAPI: HTTP 200 received ({} chars)", response.body().length());
        return response.body();
    }

    // ─── Response → profile list ──────────────────────────────────────────────────

    private List<CandidateProfile> parseResponse(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);

        if (root.has("error"))
            throw new Exception("SerpAPI error: " + root.path("error").asText());

        JsonNode organic = root.path("organic_results");
        if (organic.isMissingNode() || !organic.isArray() || organic.isEmpty()) {
            logger.warn("SerpAPI: no organic_results in response");
            return new ArrayList<>();
        }

        List<CandidateProfile> profiles = new ArrayList<>();
        for (JsonNode result : organic) {
            try {
                CandidateProfile p = parseResult(result);
                if (p != null && !p.getFullName().isBlank()) profiles.add(p);
            } catch (Exception e) {
                logger.debug("SerpAPI: skipping result — {}", e.getMessage());
            }
        }
        return profiles;
    }

    // ─── Single result → CandidateProfile ────────────────────────────────────────

    private CandidateProfile parseResult(JsonNode result) {
        String link    = result.path("link").asText("").trim();
        String title   = result.path("title").asText("").trim();
        String snippet = result.path("snippet").asText("").trim();

        if (!link.contains("linkedin.com/in/")) return null;

        CandidateProfile p = new CandidateProfile();
        p.setProfileUrl(link.contains("?") ? link.substring(0, link.indexOf('?')) : link);

        // Layer 1 — page title (name, current title, company)
        parseTitle(title, p);

        // Status: "#OPENTOWORK" / "Open to Work" is sometimes embedded in the title
        String titleLower = title.toLowerCase();
        if (titleLower.contains("open to work") || titleLower.contains("opentowork")
                || titleLower.contains("#opentowork")) {
            p.setStatus("Open to Work");
        }

        // Last Updated: SerpAPI surfaces a per-result 'date' field when available
        String serpDate = result.path("date").asText("").trim();
        if (!serpDate.isBlank()) p.setLastUpdated(serpDate);

        // Layer 2 — rich_snippet structured extensions (title, location, status)
        enrichFromRichSnippet(result.path("rich_snippet"), p);

        // Layer 3 — snippet text (location, exp, skills, company, email)
        if (!snippet.isBlank()) {
            p.setSummary(snippet);
            enrichFromSnippet(snippet, p);
        }

        logger.debug("SerpAPI parsed: name=[{}] title=[{}] company=[{}] "
                + "location=[{}] exp=[{}] skills=[{}]",
                p.getFullName(), p.getCurrentTitle(), p.getCurrentCompany(),
                p.getLocation(), p.getYearsOfExperience(), p.getKeySkills());

        p.setMatchScore(CandidateProfile.MatchScore.MEDIUM);
        return p;
    }

    // ─── Layer 1: title parsing ───────────────────────────────────────────────────

    /**
     * Parses the Google page title into name, current title, and company.
     *
     * <p>Supported title formats:
     * <table>
     *   <tr><td>{@code John Doe - Scrum Master at TechCorp | LinkedIn}</td>
     *       <td>dash-separated (most common)</td></tr>
     *   <tr><td>{@code John Doe · Scrum Master · TechCorp | LinkedIn}</td>
     *       <td>dot-separated (newer LinkedIn format)</td></tr>
     *   <tr><td>{@code John Doe | Senior Developer | LinkedIn}</td>
     *       <td>pipe-separated</td></tr>
     *   <tr><td>{@code John Doe | LinkedIn}</td>
     *       <td>name only</td></tr>
     * </table>
     */
    private void parseTitle(String title, CandidateProfile p) {
        // Remove trailing "| LinkedIn", "- LinkedIn", "on LinkedIn …" suffixes
        String clean = title
                .replaceAll("(?i)\\s*[|]\\s*LinkedIn.*$", "")
                .replaceAll("(?i)\\s*-\\s*LinkedIn.*$", "")
                .replaceAll("(?i)\\s*on LinkedIn.*$", "")
                .trim();

        // Format A: "Name - Title [at Company]"   (dash separator)
        if (clean.contains(" - ")) {
            String[] parts = clean.split(" - ", 2);
            p.setFullName(parts[0].trim());
            splitTitleAndCompany(parts[1].trim(), p);
            return;
        }

        // Format B: "Name · Title [· Company]"   (middle-dot separator)
        if (clean.contains("·")) {
            String[] parts = clean.split("·");
            if (parts.length >= 1) p.setFullName(parts[0].trim());
            if (parts.length >= 2) splitTitleAndCompany(parts[1].trim(), p);
            if (parts.length >= 3 && p.getCurrentCompany().isBlank())
                p.setCurrentCompany(parts[2].trim());
            return;
        }

        // Format C: "Name | Title"   (pipe separator)
        if (clean.contains(" | ")) {
            String[] parts = clean.split(" \\| ", 2);
            p.setFullName(parts[0].trim());
            if (parts.length > 1) splitTitleAndCompany(parts[1].trim(), p);
            return;
        }

        // Format D: bare name
        p.setFullName(clean);
    }

    /** Splits "Scrum Master at TechCorp" into title and company. */
    private void splitTitleAndCompany(String rest, CandidateProfile p) {
        String lower = rest.toLowerCase();
        if (lower.contains(" at ")) {
            int idx = lower.indexOf(" at ");
            p.setCurrentTitle(rest.substring(0, idx).trim());
            p.setCurrentCompany(rest.substring(idx + 4).trim());
        } else {
            p.setCurrentTitle(rest);
        }
    }

    // ─── Layer 2: rich_snippet ────────────────────────────────────────────────────

    /**
     * SerpAPI returns {@code rich_snippet.top.extensions} for many LinkedIn
     * profile results — an array of strings extracted from the page's
     * structured data (schema.org Person markup).
     *
     * <p>Typical values: {@code ["Scrum Master", "Mumbai, Maharashtra, India"]}
     * or just {@code ["Mumbai, Maharashtra, India"]}.
     */
    private void enrichFromRichSnippet(JsonNode richSnippet, CandidateProfile p) {
        if (richSnippet == null || richSnippet.isMissingNode()) return;
        JsonNode extensions = richSnippet.path("top").path("extensions");
        if (!extensions.isArray()) return;

        for (JsonNode ext : extensions) {
            String val = ext.asText("").trim();
            if (val.isBlank()) continue;

            String valLower = val.toLowerCase();

            // Open to Work badge appears as an extension on some LinkedIn results
            if ((valLower.contains("open to work") || valLower.contains("opentowork"))
                    && p.getStatus().isBlank()) {
                p.setStatus("Open to Work");
                continue;
            }

            // Contains comma + not a boilerplate string → treat as location
            if (val.contains(",")
                    && val.length() <= 80
                    && p.getLocation().isBlank()
                    && !valLower.contains("connection")
                    && !valLower.contains("follower")) {
                p.setLocation(val);
                continue;
            }

            // Short string without comma → likely a job title
            if (!val.contains(",") && val.length() <= 100 && p.getCurrentTitle().isBlank()) {
                p.setCurrentTitle(val);
            }
        }
    }

    // ─── Layer 3: snippet enrichment ─────────────────────────────────────────────

    /**
     * Attempts to extract Location, Years of Experience, Key Skills, Company,
     * and Email from the LinkedIn meta-description snippet.
     *
     * <p>Each field is only written if it is still blank (earlier layers take
     * precedence).
     */
    private void enrichFromSnippet(String snippet, CandidateProfile p) {

        // ── Location ──────────────────────────────────────────────────────────────
        if (p.getLocation().isBlank()) {
            String loc = extractLocationFromSnippet(snippet);
            if (!loc.isBlank()) p.setLocation(loc);
        }

        // ── Years of experience ───────────────────────────────────────────────────
        if (p.getYearsOfExperience().isBlank()) {
            Matcher m = EXP_PATTERN.matcher(snippet);
            if (m.find()) {
                p.setYearsOfExperience(m.group(1) + "+ yrs");
            }
        }

        // ── Key skills ────────────────────────────────────────────────────────────
        if (p.getKeySkills().isBlank()) {
            Matcher m = SKILLS_PATTERN.matcher(snippet);
            if (m.find()) {
                String skills = m.group(1).trim()
                        .replaceAll("\\s+", " ")
                        .replaceAll("[.·|]+$", "");
                if (skills.length() > 150) skills = skills.substring(0, 150).trim();
                if (!skills.isBlank()) p.setKeySkills(skills);
            }
        }

        // ── Company (fallback if title had no "at …" pattern) ────────────────────
        if (p.getCurrentCompany().isBlank()) {
            Matcher m = COMPANY_PATTERN.matcher(snippet);
            if (m.find()) {
                String company = m.group(1).trim();
                if (company.length() >= 2 && company.length() <= 80)
                    p.setCurrentCompany(company);
            }
        }

        // ── Email (rare — LinkedIn almost never exposes it publicly) ──────────────
        if (p.getEmail().isBlank()) {
            Matcher m = EMAIL_PATTERN.matcher(snippet);
            if (m.find()) {
                String email = m.group();
                if (!email.contains("linkedin.com") && !email.contains("example."))
                    p.setEmail(email);
            }
        }

        // ── Status (Open to Work, Hiring, etc.) ───────────────────────────────────
        if (p.getStatus().isBlank()) {
            String lower = snippet.toLowerCase();
            if (lower.contains("open to work") || lower.contains("#opentowork")
                    || lower.contains("opentowork")
                    || lower.contains("actively looking")
                    || lower.contains("actively seeking")
                    || lower.contains("open for opportunities")) {
                p.setStatus("Open to Work");
            } else if (lower.contains("we're hiring") || lower.contains("we are hiring")
                    || lower.contains("now hiring")) {
                p.setStatus("Hiring");
            } else if (lower.contains("providing services")) {
                p.setStatus("Providing Services");
            }
        }

        // ── Last Updated (fallback — snippet rarely has this) ─────────────────────
        if (p.getLastUpdated().isBlank()) {
            Matcher m = UPDATED_PATTERN.matcher(snippet);
            if (m.find()) p.setLastUpdated(m.group());
        }
    }

    /**
     * Extracts a location string from the snippet by first splitting on
     * {@code ·} / {@code |} / newline separators (LinkedIn meta-description
     * format), then falling back to an inline regex if no segment matches.
     *
     * <p>A segment is treated as a location when it:
     * <ul>
     *   <li>starts with a capital letter</li>
     *   <li>contains at least one comma</li>
     *   <li>is between 5 and 80 characters</li>
     *   <li>does not contain boilerplate words such as "connection", "follower",
     *       "LinkedIn", "view", "profile"</li>
     *   <li>does not contain a four-digit year (avoids date ranges)</li>
     * </ul>
     */
    private String extractLocationFromSnippet(String snippet) {
        String[] segments = snippet.split("[·|\\n]");
        for (String seg : segments) {
            String t = seg.trim();
            if (t.length() >= 5 && t.length() <= 80
                    && t.contains(",")
                    && Character.isUpperCase(t.charAt(0))
                    && !t.toLowerCase().contains("connection")
                    && !t.toLowerCase().contains("follower")
                    && !t.toLowerCase().contains("linkedin")
                    && !t.toLowerCase().contains("view")
                    && !t.toLowerCase().contains("profile")
                    && !t.matches(".*\\d{4}.*")) {
                return t;
            }
        }
        // Inline fallback: "Mumbai, Maharashtra, India" anywhere in text
        Matcher m = LOCATION_INLINE.matcher(snippet);
        return m.find() ? m.group(1).trim() : "";
    }
}

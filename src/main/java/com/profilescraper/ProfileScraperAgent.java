package com.profilescraper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.profilescraper.model.CandidateProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core AI agent using Google Gemini 2.0 Flash with built-in Google Search grounding.
 *
 * <p>Gemini's Google Search tool searches the web automatically inside a single API
 * call — no manual pause_turn loop needed (unlike the previous Claude implementation).
 * Uses Java 11's built-in {@link HttpClient} — no extra SDK dependency required.</p>
 *
 * <p>Free-tier limits (Google AI Studio): 15 req/min, 1 500 req/day.</p>
 *
 * Required: {@code GEMINI_API_KEY} environment variable (or pass key to constructor).
 * Get a free key at: <a href="https://aistudio.google.com/apikey">aistudio.google.com/apikey</a>
 */
public class ProfileScraperAgent {

    private static final Logger logger = LoggerFactory.getLogger(ProfileScraperAgent.class);

    private static final String API_URL         = "https://generativelanguage.googleapis.com"
                                                + "/v1beta/models/gemini-2.0-flash:generateContent";
    private static final int    MAX_OUTPUT_TOKENS = 8192;

    private static final int    DEFAULT_MAX_RESULTS = 10; // KAN-27: hard cap on profiles returned
    /**
     * Hard cap on the number of profiles returned by the AI search (KAN-27).
     * Defaults to {@value #DEFAULT_MAX_RESULTS}; can be overridden without a code
     * change via the optional {@code SERP_API_MAX_RESULTS} environment variable
     * (shared with {@link com.profilescraper.scraper.SerpApiScraper} so both LinkedIn
     * discovery paths honour the same limit).
     */
    private static final int    MAX_RESULTS         = readMaxResults();

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

    private final String     apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** Spring-managed constructor — receives API key via DI. */
    public ProfileScraperAgent(String apiKey) {
        this.apiKey      = apiKey;
        this.httpClient  = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /** CLI / no-Spring constructor — reads GEMINI_API_KEY from environment. */
    public ProfileScraperAgent() {
        this(Optional.ofNullable(System.getenv("GEMINI_API_KEY")).orElse(""));
    }

    // ─── Public API ─────────────────────────────────────────────────────────────

    public List<CandidateProfile> scrapeProfiles(String jobDescription) throws Exception {
        logger.info("=== Profile Scraper Agent starting (Gemini 2.0 Flash) ===");
        logger.info("Job description: {}", jobDescription);

        System.out.println("\n" + "=".repeat(70));
        System.out.println(" Profile Scraper Agent — Powered by Gemini 2.0 Flash");
        System.out.println("=".repeat(70));
        System.out.println(" Searching LinkedIn, Naukri, Indeed, Shine, Monster...");
        System.out.println(" Please wait — this may take a minute.\n");

        if (Thread.currentThread().isInterrupted()) {
            return new ArrayList<>();
        }

        String responseText = callGemini(jobDescription);

        System.out.println("\n" + "-".repeat(70));
        System.out.println(" Parsing results...");

        List<CandidateProfile> profiles = parseProfiles(responseText);
        List<CandidateProfile> limited  = applyResultLimit(profiles);

        logger.info("Scraping complete. Found {} candidates, returning {} (cap={}).",
                profiles.size(), limited.size(), MAX_RESULTS);
        System.out.println(" Found " + limited.size() + " candidates.");
        System.out.println("=".repeat(70) + "\n");

        return limited;
    }

    /**
     * Enforces the {@link #MAX_RESULTS} cap (KAN-27), preserving order. Returns a
     * truncated copy of the first {@code MAX_RESULTS} profiles when the input
     * exceeds the cap, or the input list unchanged when it is at or below it.
     *
     * <p>Package-private and static so unit tests can exercise it without constructing
     * an agent — the constructor builds an {@link HttpClient}, which needs a working
     * NIO selector and would otherwise drag a network dependency into a pure list test.
     */
    static List<CandidateProfile> applyResultLimit(List<CandidateProfile> profiles) {
        if (profiles == null || profiles.size() <= MAX_RESULTS) return profiles;
        return new ArrayList<>(profiles.subList(0, MAX_RESULTS));
    }

    // ─── Gemini API call ─────────────────────────────────────────────────────────

    private String callGemini(String jobDescription) throws Exception {

        // ── Build request body with Jackson (type-safe, no string concat) ─────────
        ObjectNode body = objectMapper.createObjectNode();

        // system_instruction
        body.putObject("system_instruction")
                .putArray("parts")
                .addObject().put("text", buildSystemPrompt());

        // contents (single user turn)
        body.putArray("contents")
                .addObject()
                .put("role", "user")
                .putArray("parts")
                .addObject().put("text", buildUserPrompt(jobDescription));

        // tools — enable built-in Google Search grounding
        body.putArray("tools")
                .addObject()
                .putObject("google_search");

        // generation_config
        body.putObject("generation_config")
                .put("max_output_tokens", MAX_OUTPUT_TOKENS)
                .put("temperature", 1.0);

        String requestJson = objectMapper.writeValueAsString(body);
        logger.debug("Gemini request (first 300 chars): {}",
                requestJson.substring(0, Math.min(300, requestJson.length())));

        // ── HTTP POST ─────────────────────────────────────────────────────────────
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL + "?key=" + apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .timeout(Duration.ofSeconds(180))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            logger.error("Gemini API error {}: {}", response.statusCode(), response.body());
            throw new RuntimeException(
                    "Gemini API returned " + response.statusCode() + ": " + response.body());
        }

        return extractText(response.body());
    }

    /** Pull the plain-text content out of the Gemini response JSON. */
    private String extractText(String responseJson) throws Exception {
        JsonNode root       = objectMapper.readTree(responseJson);
        JsonNode candidates = root.path("candidates");

        if (candidates.isEmpty()) {
            logger.warn("No candidates in Gemini response. Body (500 chars): {}",
                    responseJson.substring(0, Math.min(500, responseJson.length())));
            return "";
        }

        StringBuilder sb    = new StringBuilder();
        JsonNode      parts = candidates.get(0).path("content").path("parts");
        for (JsonNode part : parts) {
            if (part.has("text")) {
                sb.append(part.get("text").asText());
            }
        }

        String text = sb.toString();
        logger.info("Gemini response: {} chars", text.length());
        System.out.print(text);
        System.out.flush();
        return text;
    }

    // ─── JSON parsing ────────────────────────────────────────────────────────────

    private List<CandidateProfile> parseProfiles(String responseText) {
        if (responseText == null || responseText.isBlank()) {
            logger.warn("Empty response from Gemini.");
            return new ArrayList<>();
        }

        // 1. Preferred: ```json ... ``` fenced block
        Pattern fencedJson = Pattern.compile("```json\\s*\\n?([\\s\\S]*?)\\n?```");
        Matcher m = fencedJson.matcher(responseText);
        if (m.find()) {
            List<CandidateProfile> r = tryParseJson(m.group(1).trim());
            if (r != null) return r;
        }

        // 2. Any fenced block whose content starts with '['
        Pattern anyFence = Pattern.compile("```\\s*\\n?([\\s\\S]*?)\\n?```");
        Matcher m2 = anyFence.matcher(responseText);
        while (m2.find()) {
            String candidate = m2.group(1).trim();
            if (candidate.startsWith("[")) {
                List<CandidateProfile> r = tryParseJson(candidate);
                if (r != null) return r;
            }
        }

        // 3. Last resort: raw JSON array anywhere in the text
        Pattern rawArray = Pattern.compile("(\\[\\s*\\{[\\s\\S]*?\\}\\s*\\])");
        Matcher m3 = rawArray.matcher(responseText);
        String lastMatch = null;
        while (m3.find()) { lastMatch = m3.group(1); }
        if (lastMatch != null) {
            List<CandidateProfile> r = tryParseJson(lastMatch);
            if (r != null) return r;
        }

        logger.error("Could not extract a valid JSON array from the Gemini response.");
        logger.debug("Full response:\n{}", responseText);
        return new ArrayList<>();
    }

    private List<CandidateProfile> tryParseJson(String json) {
        try {
            List<CandidateProfile> profiles =
                    objectMapper.readValue(json, new TypeReference<>() {});
            logger.info("Successfully parsed {} profile(s) from JSON.", profiles.size());
            return profiles;
        } catch (Exception e) {
            String snippet = json.length() > 200 ? json.substring(0, 200) + "..." : json;
            logger.warn("JSON parse failed ({}). Snippet: {}", e.getMessage(), snippet);
            return null;
        }
    }

    // ─── Prompts ─────────────────────────────────────────────────────────────────

    private String buildSystemPrompt() {
        return """
                You are the "Profile Scraper Agent" — a professional recruitment research assistant.

                YOUR TASK
                ─────────
                Use Google Search to find 20–30 real candidate profiles on LinkedIn, Naukri.com,
                Indeed, Shine, Monster, and Glassdoor that match the given job requirement.

                SEARCH STRATEGY
                ───────────────
                • Run multiple varied queries: role + skills, role + location, site:linkedin.com/in, etc.
                • Try different experience ranges and skill combinations
                • Only surface candidates with publicly accessible profiles

                FOR EACH CANDIDATE, EXTRACT  (empty string "" if not publicly available)
                ─────────────────────────────────────────────────────────────────────────
                fullName, currentTitle, currentCompany, location, yearsOfExperience,
                keySkills (comma-separated), profileUrl, email, phone, summary,
                status (e.g. "Open to Work", "Hiring", or ""), lastUpdated (e.g. "2 weeks ago", ""),
                matchScore

                MATCH SCORE
                ───────────
                "High"   — meets most requirements (skills, experience, location)
                "Medium" — partial match; some gaps
                "Low"    — broad inclusion for future consideration

                RULES
                ─────
                ✓ Only PUBLICLY AVAILABLE information — no fabrication
                ✓ Sort results: High → Medium → Low

                OUTPUT FORMAT  (mandatory — end your response with this exact block, nothing after)
                ──────────────────────────────────────────────────────────────────────────────────
                ```json
                [
                  {
                    "fullName": "",
                    "currentTitle": "",
                    "currentCompany": "",
                    "location": "",
                    "yearsOfExperience": "",
                    "keySkills": "",
                    "profileUrl": "",
                    "email": "",
                    "phone": "",
                    "summary": "",
                    "status": "",
                    "lastUpdated": "",
                    "matchScore": "High"
                  }
                ]
                ```
                """;
    }

    private String buildUserPrompt(String jobDescription) {
        return """
                Find candidate profiles matching this job requirement:

                %s

                Use Google Search to find profiles on LinkedIn, Naukri.com, Indeed, Shine, and Monster.
                Run multiple varied search queries to maximise coverage.
                Return ALL results as a single ```json ... ``` code block at the very end.
                """.formatted(jobDescription);
    }
}

package com.profilescraper.scraper;

/**
 * Job portals supported by the portal-login scraping mode.
 *
 * <p>{@link #SERP_API} is special: it does not require a browser login.
 * It uses the SerpAPI Google Search API with a {@code site:linkedin.com/in/} query
 * to find publicly-indexed candidate profiles — no credentials, just an API key.</p>
 */
public enum JobPortal {

    LINKEDIN("LinkedIn",               "https://www.linkedin.com"),
    NAUKRI  ("Naukri",                 "https://www.naukri.com"),
    INDEED  ("Indeed",                 "https://www.indeed.com"),
    SERP_API("SERP API (Google Search)", "https://serpapi.com");

    private final String displayName;
    private final String baseUrl;

    JobPortal(String displayName, String baseUrl) {
        this.displayName = displayName;
        this.baseUrl     = baseUrl;
    }

    public String getDisplayName() { return displayName; }
    public String getBaseUrl()     { return baseUrl; }

    /** Returns true for portals that only need an API key (no username/password). */
    public boolean isApiKeyOnly() { return this == SERP_API; }

    @Override
    public String toString() { return displayName; }
}

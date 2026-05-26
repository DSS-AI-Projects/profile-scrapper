package com.profilescraper.service;

import com.profilescraper.ProfileScraperAgent;
import com.profilescraper.model.CandidateProfile;
import com.profilescraper.scraper.JobPortal;
import com.profilescraper.scraper.PortalScraperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Spring Service that routes candidate-scraping requests to either:
 * <ul>
 *   <li>The AI-powered {@link ProfileScraperAgent} (Gemini 2.0 Flash + Google Search) when
 *       no portal is specified.</li>
 *   <li>A Playwright-based {@link com.profilescraper.scraper.PortalScraper} when the user
 *       picks a specific portal and supplies their credentials.</li>
 * </ul>
 */
@Service
public class ScraperService {

    private static final Logger logger = LoggerFactory.getLogger(ScraperService.class);

    private final ProfileScraperAgent agent;

    public ScraperService(@Value("${gemini.api-key}") String apiKey) {
        this.agent = new ProfileScraperAgent(apiKey);
    }

    // ─── AI / Gemini search (no portal login) ────────────────────────────────────

    /**
     * Use Gemini 2.0 Flash with Google Search grounding to find candidates.
     * No portal login is required.
     */
    public List<CandidateProfile> scrapeProfiles(String jobDescription) throws Exception {
        logger.info("AI search for: {}", jobDescription);
        return agent.scrapeProfiles(jobDescription);
    }

    // ─── Portal-login scraping ────────────────────────────────────────────────────

    /**
     * Log into the specified portal with the supplied credentials and scrape
     * candidate profiles matching the job description.
     *
     * @param jobDescription natural-language job requirement
     * @param portal         the portal to target
     * @param username       portal login e-mail or username
     * @param password       portal password
     * @return list of candidate profiles found (may be empty)
     */
    public List<CandidateProfile> scrapeProfiles(String jobDescription,
                                                  JobPortal portal,
                                                  String username,
                                                  String password) throws Exception {
        logger.info("Portal search on {} for: {}", portal.getDisplayName(), jobDescription);
        return PortalScraperFactory.get(portal)
                .scrapeProfiles(jobDescription, username, password);
    }
}

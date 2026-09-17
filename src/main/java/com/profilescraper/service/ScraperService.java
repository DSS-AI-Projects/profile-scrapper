package com.profilescraper.service;

import com.profilescraper.LocationFilter;
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
        return scrapeProfiles(jobDescription, "");
    }

    /**
     * @param location optional location constraint; blank means no location filtering.
     */
    public List<CandidateProfile> scrapeProfiles(String jobDescription, String location)
            throws Exception {
        logger.info("AI search for: {} (location: {})", jobDescription,
                location == null || location.isBlank() ? "any" : location);
        return agent.scrapeProfiles(jobDescription, location);
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
        return scrapeProfiles(jobDescription, "", portal, username, password);
    }

    /**
     * Portal scrapers receive the location only as part of the free-text job description, so
     * the constraint is enforced here on their output instead. Unlike the AI path this runs
     * after the scraper's own result cap, so a heavily off-location page of results can come
     * back short.
     *
     * @param location optional location constraint; blank means no location filtering.
     */
    public List<CandidateProfile> scrapeProfiles(String jobDescription,
                                                  String location,
                                                  JobPortal portal,
                                                  String username,
                                                  String password) throws Exception {
        logger.info("Portal search on {} for: {} (location: {})", portal.getDisplayName(),
                jobDescription, location == null || location.isBlank() ? "any" : location);

        List<CandidateProfile> found = PortalScraperFactory.get(portal)
                .scrapeProfiles(jobDescription, username, password);

        List<CandidateProfile> onLocation = LocationFilter.apply(found, location);
        if (onLocation.size() != found.size()) {
            logger.info("Location filter '{}' dropped {} of {} candidates from {}.",
                    location, found.size() - onLocation.size(), found.size(),
                    portal.getDisplayName());
        }
        return onLocation;
    }
}

package com.profilescraper.scraper;

import com.profilescraper.model.CandidateProfile;

import java.util.List;

/**
 * Contract for portal-specific candidate scrapers.
 *
 * <p>Each implementation logs into the target portal with the supplied
 * credentials, searches for candidates matching the job description, and
 * returns whatever profile data is publicly accessible on the account.</p>
 */
public interface PortalScraper {

    /**
     * Scrape candidate profiles from this portal.
     *
     * @param jobDescription  natural-language job requirement (skills, location, experience, etc.)
     * @param username         portal login e-mail or username
     * @param password         portal password
     * @return list of profiles found (may be empty but never null)
     * @throws Exception if login fails or the portal is unreachable
     */
    List<CandidateProfile> scrapeProfiles(String jobDescription,
                                          String username,
                                          String password) throws Exception;

    /** The portal this scraper targets. */
    JobPortal getPortal();
}

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
     * @param jobDescription  natural-language job requirement (skills, experience, etc.)
     * @param location         optional location constraint from the UI, blank for anywhere.
     *                         Implementations must fold this into the search they actually run:
     *                         filtering it out afterwards is not enough, because a portal asked
     *                         for "IT Recruiter" with no location returns a worldwide pool and
     *                         most of it is discarded — or worse, kept because the portal did
     *                         not state a location for the candidate either.
     * @param username         portal login e-mail or username
     * @param password         portal password
     * @return list of profiles found (may be empty but never null)
     * @throws Exception if login fails or the portal is unreachable
     */
    List<CandidateProfile> scrapeProfiles(String jobDescription,
                                          String location,
                                          String username,
                                          String password) throws Exception;

    /** The portal this scraper targets. */
    JobPortal getPortal();
}

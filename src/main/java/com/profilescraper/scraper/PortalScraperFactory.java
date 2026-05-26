package com.profilescraper.scraper;

import java.util.EnumMap;
import java.util.Map;

/**
 * Factory that returns the correct {@link PortalScraper} for a given {@link JobPortal}.
 */
public class PortalScraperFactory {

    private static final Map<JobPortal, PortalScraper> SCRAPERS;

    static {
        SCRAPERS = new EnumMap<>(JobPortal.class);
        SCRAPERS.put(JobPortal.LINKEDIN, new LinkedInScraper());
        SCRAPERS.put(JobPortal.NAUKRI,   new NaukriScraper());
        SCRAPERS.put(JobPortal.INDEED,   new IndeedScraper());
        SCRAPERS.put(JobPortal.SERP_API, new SerpApiScraper());
    }

    private PortalScraperFactory() {}   // utility class

    /**
     * Return the scraper for the given portal.
     *
     * @throws IllegalArgumentException if no scraper is registered for the portal
     */
    public static PortalScraper get(JobPortal portal) {
        PortalScraper scraper = SCRAPERS.get(portal);
        if (scraper == null) {
            throw new IllegalArgumentException(
                    "No scraper registered for portal: " + portal);
        }
        return scraper;
    }
}

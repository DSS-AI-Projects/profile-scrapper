package com.profilescraper.scraper;

import com.profilescraper.LocationFilter;

import java.util.List;

/** Helpers for folding the UI's search inputs into the text a portal actually searches on. */
final class SearchTerms {

    private SearchTerms() {
    }

    /**
     * Puts the requested location in front of the job description so portal scrapers search for
     * it rather than merely being filtered on it afterwards.
     *
     * <p>Leading, because scrapers derive keywords by truncating this text — trailing terms are
     * the first thing dropped, and the location is the one term that must survive.
     *
     * <p>Only the <em>first</em> city is used when several are given. These portals search a
     * single free-text box with no OR syntax, so listing every alternative would read as one
     * long phrase and match nothing. {@link LocationFilter} still accepts any of the requested
     * cities on the way out, so extra ones are not rejected — they are just less likely to be
     * returned by the portal in the first place. {@code SerpApiScraper} has no such limit and
     * queries all of them.
     */
    static String withLocation(String jobDescription, String location) {
        List<String> cities = LocationFilter.cities(location);
        if (cities.isEmpty()) return jobDescription;
        return cities.get(0) + " " + (jobDescription == null ? "" : jobDescription);
    }
}

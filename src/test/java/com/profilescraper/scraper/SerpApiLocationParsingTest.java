package com.profilescraper.scraper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards {@code SerpApiScraper.looksLikePlace}. The rejected strings below were all recorded as
 * real candidate locations in production, because "starts with a capital and contains a comma"
 * also describes a job-title list and a sentence. A wrong location is worse than none — it
 * defeats location filtering while looking authoritative.
 */
class SerpApiLocationParsingTest {

    private final SerpApiScraper scraper = new SerpApiScraper();

    private boolean looksLikePlace(String value) throws Exception {
        Method m = SerpApiScraper.class.getDeclaredMethod("looksLikePlace", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(scraper, value);
    }

    @Test
    @DisplayName("Real place names are accepted")
    void acceptsPlaces() throws Exception {
        for (String place : new String[]{
                "Mumbai, Maharashtra, India",
                "Navi Mumbai, India",
                "Bengaluru, Karnataka, India",
                "Scarborough, Ontario, Canada",
                "City of Johannesburg, Gauteng, South Africa",
                "Edison, New Jersey, United States"}) {
            assertTrue(looksLikePlace(place), place + " should be treated as a place");
        }
    }

    @Test
    @DisplayName("Job-title lists and bio prose are rejected")
    void rejectsNonPlaces() throws Exception {
        for (String notAPlace : new String[]{
                "Filing, Answering phones",
                "Author, Celebrity Biographer, Animation Historian",
                "I build and protect reputations for complex technology companies",
                "Talent Acquisition, Recruiting, and Sourcing",
                "Helping founders hire faster, smarter, better."}) {
            assertFalse(looksLikePlace(notAPlace), notAPlace + " should not be treated as a place");
        }
    }
}

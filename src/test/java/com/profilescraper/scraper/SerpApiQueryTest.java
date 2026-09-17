package com.profilescraper.scraper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the Google query built from the UI's Location field.
 *
 * <p>The query and {@code LocationFilter} must read that field the same way. When they did not,
 * a search for "Mumbai, India only" asked Google for that entire string as a literal phrase —
 * matching almost nothing — while the filter matched on just "Mumbai".
 */
class SerpApiQueryTest {

    private final SerpApiScraper scraper = new SerpApiScraper();

    private String query(String jobDescription, String location) throws Exception {
        Method m = SerpApiScraper.class.getDeclaredMethod(
                "buildSerpQuery", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(scraper, jobDescription, location);
    }

    @Test
    @DisplayName("A single city is quoted as one term")
    void singleCity() throws Exception {
        assertTrue(query("IT Technical Recruiter", "Mumbai").endsWith("\"Mumbai\""));
    }

    @Test
    @DisplayName("A qualifying comma is dropped, not sent to Google as a phrase")
    void commaQualifierIsNotSentAsAPhrase() throws Exception {
        String q = query("IT Technical Recruiter", "Mumbai, India only");

        assertTrue(q.endsWith("\"Mumbai\""), "should search the city, got: " + q);
        assertFalse(q.contains("India only"), "the qualifier must not reach Google: " + q);
    }

    @Test
    @DisplayName("Several cities become a bracketed OR group")
    void multipleCitiesBecomeOrGroup() throws Exception {
        String q = query("IT Technical Recruiter", "Mumbai; Thane; Pune");

        assertTrue(q.endsWith("(\"Mumbai\" OR \"Thane\" OR \"Pune\")"),
                "unbracketed OR would bind only adjacent terms, got: " + q);
    }

    @Test
    @DisplayName("No location leaves the query untouched")
    void noLocation() throws Exception {
        assertEquals(query("IT Technical Recruiter", ""),
                query("IT Technical Recruiter", null));
        assertFalse(query("IT Technical Recruiter", "").contains("\""));
    }
}

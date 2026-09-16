package com.profilescraper.scraper;

import com.profilescraper.model.CandidateProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link SerpApiScraper#applyResultLimit(List)} (KAN-27).
 *
 * <p>These exercise only the in-memory limit-enforcement helper and never touch
 * the network-calling {@code callSerpApi}/{@code scrapeProfiles} HTTP path, so the
 * tests stay hermetic and fast without a mocking framework.
 */
class SerpApiScraperTest {

    private final SerpApiScraper scraper = new SerpApiScraper();

    private static List<CandidateProfile> profiles(int count) {
        List<CandidateProfile> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CandidateProfile p = new CandidateProfile();
            p.setFullName("Candidate " + i);
            p.setProfileUrl("https://www.linkedin.com/in/candidate-" + i);
            list.add(p);
        }
        return list;
    }

    @Test
    @DisplayName("More than 10 profiles is truncated to exactly 10, preserving order")
    void truncatesToTenPreservingOrder() {
        List<CandidateProfile> input = profiles(15);

        List<CandidateProfile> result = scraper.applyResultLimit(input);

        assertEquals(10, result.size(), "result should be capped at 10");
        for (int i = 0; i < 10; i++) {
            assertEquals("Candidate " + i, result.get(i).getFullName(),
                    "order of the first 10 profiles must be preserved");
        }
    }

    @Test
    @DisplayName("Fewer than 10 profiles pass through unchanged")
    void passesThroughWhenFewerThanTen() {
        List<CandidateProfile> input = profiles(7);

        List<CandidateProfile> result = scraper.applyResultLimit(input);

        assertSame(input, result, "list at or below the cap should be returned as-is");
        assertEquals(7, result.size());
    }

    @Test
    @DisplayName("Exactly 10 profiles pass through unchanged")
    void passesThroughWhenExactlyTen() {
        List<CandidateProfile> input = profiles(10);

        List<CandidateProfile> result = scraper.applyResultLimit(input);

        assertSame(input, result, "list exactly at the cap should be returned as-is");
        assertEquals(10, result.size());
    }
}

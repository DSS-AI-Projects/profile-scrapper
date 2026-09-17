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

    private static final int DEFAULT_CAP = 20;

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
    @DisplayName("More than the cap is truncated to exactly the cap, preserving order")
    void truncatesToCapPreservingOrder() {
        List<CandidateProfile> input = profiles(DEFAULT_CAP + 5);

        List<CandidateProfile> result = scraper.applyResultLimit(input);

        assertEquals(DEFAULT_CAP, result.size(), "result should be capped at " + DEFAULT_CAP);
        for (int i = 0; i < DEFAULT_CAP; i++) {
            assertEquals("Candidate " + i, result.get(i).getFullName(),
                    "order of the first " + DEFAULT_CAP + " profiles must be preserved");
        }
    }

    @Test
    @DisplayName("Fewer than the cap pass through unchanged")
    void passesThroughWhenFewerThanCap() {
        List<CandidateProfile> input = profiles(7);

        List<CandidateProfile> result = scraper.applyResultLimit(input);

        assertSame(input, result, "list at or below the cap should be returned as-is");
        assertEquals(7, result.size());
    }

    @Test
    @DisplayName("Exactly the cap passes through unchanged")
    void passesThroughWhenExactlyCap() {
        List<CandidateProfile> input = profiles(DEFAULT_CAP);

        List<CandidateProfile> result = scraper.applyResultLimit(input);

        assertSame(input, result, "list exactly at the cap should be returned as-is");
        assertEquals(DEFAULT_CAP, result.size());
    }
}

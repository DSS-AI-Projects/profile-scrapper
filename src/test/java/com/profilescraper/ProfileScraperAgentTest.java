package com.profilescraper;

import com.profilescraper.model.CandidateProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for the KAN-27 result cap on the Gemini AI-search path, enforced by
 * {@link ProfileScraperAgent#applyResultLimit(List)}.
 *
 * <p>These tests exercise the package-private helper directly and never call the
 * Gemini HTTP endpoint, keeping them hermetic and fast. They assume the default cap
 * of 10 (i.e. the optional {@code SERP_API_MAX_RESULTS} env var is not set).
 */
class ProfileScraperAgentTest {

    private static final int DEFAULT_CAP = 20;

    private static List<CandidateProfile> profiles(int count) {
        List<CandidateProfile> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CandidateProfile p = new CandidateProfile();
            p.setFullName("Candidate " + i);
            p.setProfileUrl("https://linkedin.com/in/candidate-" + i);
            list.add(p);
        }
        return list;
    }

    @Test
    void truncatesMoreThanCapToExactlyCapPreservingOrder() {
        List<CandidateProfile> input = profiles(DEFAULT_CAP + 5);

        List<CandidateProfile> result = ProfileScraperAgent.applyResultLimit(input);

        assertEquals(DEFAULT_CAP, result.size(), "should truncate to the cap");
        for (int i = 0; i < DEFAULT_CAP; i++) {
            assertEquals("Candidate " + i, result.get(i).getFullName(),
                    "order of the first " + DEFAULT_CAP + " profiles must be preserved");
        }
    }

    @Test
    void passesThroughFewerThanCapUnchanged() {
        List<CandidateProfile> input = profiles(7);

        List<CandidateProfile> result = ProfileScraperAgent.applyResultLimit(input);

        assertSame(input, result, "lists at or below the cap are returned unchanged");
        assertEquals(7, result.size());
    }

    @Test
    void passesThroughExactlyCapUnchanged() {
        List<CandidateProfile> input = profiles(DEFAULT_CAP);

        List<CandidateProfile> result = ProfileScraperAgent.applyResultLimit(input);

        assertSame(input, result, "a list of exactly the cap is returned unchanged");
        assertEquals(DEFAULT_CAP, result.size());
    }
}

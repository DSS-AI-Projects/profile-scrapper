package com.profilescraper;

import com.profilescraper.model.CandidateProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LocationFilter}, which enforces the UI's Location field against
 * whatever the AI and portal scrapers actually return.
 */
class LocationFilterTest {

    private static CandidateProfile at(String location) {
        CandidateProfile p = new CandidateProfile();
        p.setFullName("Candidate in " + location);
        p.setLocation(location);
        return p;
    }

    private static List<String> locationsOf(List<CandidateProfile> profiles) {
        List<String> out = new ArrayList<>();
        for (CandidateProfile p : profiles) out.add(p.getLocation());
        return out;
    }

    @Test
    @DisplayName("Candidates in other cities are dropped")
    void dropsOtherCities() {
        List<CandidateProfile> input = List.of(
                at("Mumbai, Maharashtra, India"),
                at("Bangalore, Karnataka, India"),
                at("Pune, Maharashtra, India"));

        List<CandidateProfile> result = LocationFilter.apply(input, "Mumbai");

        assertEquals(List.of("Mumbai, Maharashtra, India"), locationsOf(result));
    }

    @Test
    @DisplayName("Surrounding metro areas containing the city name are kept")
    void keepsMetroAreaVariants() {
        List<CandidateProfile> input = List.of(
                at("Navi Mumbai"),
                at("Mumbai"),
                at("Greater Mumbai Area"));

        List<CandidateProfile> result = LocationFilter.apply(input, "Mumbai");

        assertEquals(3, result.size(), "all three name Mumbai and must survive");
    }

    @Test
    @DisplayName("Candidates with no location survive — unknown is not a mismatch")
    void keepsBlankLocations() {
        List<CandidateProfile> input = List.of(at(""), at("Bangalore"));

        List<CandidateProfile> result = LocationFilter.apply(input, "Mumbai");

        assertEquals(List.of(""), locationsOf(result),
                "blank is kept, a known-different city is not");
    }

    @Test
    @DisplayName("A trailing country does not widen the filter to every city in it")
    void trailingCountryDoesNotWidenTheFilter() {
        List<CandidateProfile> input = List.of(
                at("Mumbai, India"),
                at("Bangalore, India"));

        List<CandidateProfile> result = LocationFilter.apply(input, "Mumbai, India");

        assertEquals(List.of("Mumbai, India"), locationsOf(result),
                "matching on the 'India' part would wrongly admit Bangalore");
    }

    @Test
    @DisplayName("Matching ignores case and punctuation")
    void matchingIsCaseAndPunctuationInsensitive() {
        List<CandidateProfile> input = List.of(at("MUMBAI (Maharashtra)"));

        assertEquals(1, LocationFilter.apply(input, "mumbai").size());
    }

    @Test
    @DisplayName("A blank requested location disables filtering entirely")
    void blankRequestedLocationReturnsInputUnchanged() {
        List<CandidateProfile> input = List.of(at("Mumbai"), at("Bangalore"));

        assertSame(input, LocationFilter.apply(input, ""),
                "no criterion means no filtering, and no copying");
        assertSame(input, LocationFilter.apply(input, null));
        assertSame(input, LocationFilter.apply(input, "   "));
    }

    @Test
    @DisplayName("Filtering everything out yields an empty list rather than failing")
    void allDroppedYieldsEmptyList() {
        List<CandidateProfile> result =
                LocationFilter.apply(List.of(at("Bangalore"), at("Delhi")), "Mumbai");

        assertTrue(result.isEmpty());
    }
}

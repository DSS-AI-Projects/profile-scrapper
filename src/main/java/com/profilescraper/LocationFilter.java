package com.profilescraper;

import com.profilescraper.model.CandidateProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the UI's Location field and drops candidates that do not match it.
 *
 * <p>Asking for a location means requiring one: a profile survives only if its location is
 * present and matches. This was lenient at first — blanks were kept on the grounds that
 * "unknown" is not proof of a mismatch — but in practice roughly half of
 * {@link com.profilescraper.scraper.SerpApiScraper}'s results carry no location, and keeping
 * them meant a search for Mumbai returned candidates from Canada, New Jersey and Australia.
 * An unverifiable profile is not a match; it is just a profile nothing is known about.
 *
 * <p>Static, and deliberately not a Spring bean, so it can be unit-tested without constructing
 * anything that builds an HTTP client.
 */
public final class LocationFilter {

    private LocationFilter() {
    }

    /**
     * The places named in the Location field, in the order given.
     *
     * <p>Semicolons separate alternatives, commas narrow a single place. So
     * {@code "Mumbai, Maharashtra"} is one place, while {@code "Mumbai; Thane"} is two — the
     * comma cannot mean both without {@code "Mumbai, India"} being read as two countries' worth
     * of candidates.
     *
     * <p>Callers must agree on this parse. The search query and the filter reading the field
     * differently is exactly how a search for {@code "Mumbai, India only"} ended up asking
     * Google for that entire string as a literal phrase while filtering on just "Mumbai".
     *
     * @return city names as typed (trimmed), or empty when nothing is named
     */
    public static List<String> cities(String requestedLocation) {
        if (requestedLocation == null || requestedLocation.isBlank()) return List.of();

        List<String> cities = new ArrayList<>();
        for (String alternative : requestedLocation.split(";")) {
            String city = alternative.split(",")[0].trim();
            if (!city.isEmpty()) cities.add(city);
        }
        return cities;
    }

    /** Keeps only candidates whose stated location matches one of the requested places. */
    public static List<CandidateProfile> apply(List<CandidateProfile> profiles,
                                               String requestedLocation) {
        if (profiles == null || profiles.isEmpty()) return profiles;

        List<String> wanted = new ArrayList<>();
        for (String city : cities(requestedLocation)) {
            String normalised = normalise(city);
            if (!normalised.isEmpty()) wanted.add(normalised);
        }
        if (wanted.isEmpty()) return profiles;

        List<CandidateProfile> kept = new ArrayList<>();
        for (CandidateProfile profile : profiles) {
            if (matches(profile, wanted)) kept.add(profile);
        }
        return kept;
    }

    /**
     * True when the profile states a location containing any requested city. Substring matching
     * is what lets "Navi Mumbai" and "Mumbai, Maharashtra, India" both satisfy "Mumbai".
     */
    static boolean matches(CandidateProfile profile, List<String> normalisedCities) {
        String actual = normalise(profile.getLocation());
        if (actual.isEmpty()) return false;

        for (String city : normalisedCities) {
            if (actual.contains(city)) return true;
        }
        return false;
    }

    private static String normalise(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}

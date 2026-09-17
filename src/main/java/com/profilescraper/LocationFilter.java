package com.profilescraper;

import com.profilescraper.model.CandidateProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Drops candidates whose location clearly contradicts the requested one.
 *
 * <p>Deliberately lenient: a profile is removed only when its location is both present
 * and demonstrably a different place. A profile with no location survives, because
 * "unknown" is not evidence of a mismatch — {@link com.profilescraper.scraper.SerpApiScraper}
 * scrapes location best-effort out of Google snippets and frequently finds none, so
 * dropping blanks would empty the grid rather than narrow it.
 *
 * <p>Static, and deliberately not a Spring bean, so it can be unit-tested without
 * constructing anything that builds an {@code HttpClient}.
 */
public final class LocationFilter {

    private LocationFilter() {
    }

    /**
     * Keeps only candidates consistent with {@code requestedLocation}.
     *
     * @param requestedLocation free text such as {@code "Mumbai"} or {@code "Mumbai, India"}.
     *                          Only the part before the first comma is matched on, so a
     *                          trailing country does not widen the filter to every city in
     *                          it ({@code "Mumbai, India"} must not admit {@code "Bangalore,
     *                          India"}). Blank disables filtering entirely.
     */
    public static List<CandidateProfile> apply(List<CandidateProfile> profiles,
                                               String requestedLocation) {
        if (profiles == null || profiles.isEmpty()) return profiles;

        String city = primaryCity(requestedLocation);
        if (city.isEmpty()) return profiles;

        List<CandidateProfile> kept = new ArrayList<>();
        for (CandidateProfile profile : profiles) {
            if (matches(profile, city)) kept.add(profile);
        }
        return kept;
    }

    /**
     * True when the profile does not contradict {@code city} — either it names no location
     * at all, or its location contains the requested city. Substring matching is what lets
     * "Navi Mumbai" and "Mumbai, Maharashtra, India" both satisfy a request for "Mumbai".
     */
    static boolean matches(CandidateProfile profile, String city) {
        String actual = normalise(profile.getLocation());
        return actual.isEmpty() || actual.contains(city);
    }

    /** Lower-cased, punctuation-stripped text before the first comma. */
    static String primaryCity(String requestedLocation) {
        if (requestedLocation == null) return "";
        return normalise(requestedLocation.split(",")[0]);
    }

    private static String normalise(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}

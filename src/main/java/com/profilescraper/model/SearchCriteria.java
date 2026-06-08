package com.profilescraper.model;

/**
 * Structured search criteria collected from the UI form.
 *
 * <p>Replaces the free-text job-description string with five distinct fields
 * that are composed into a natural-language query via {@link #toQueryString()}
 * before being handed to the portal scrapers or the Gemini AI agent.</p>
 */
public class SearchCriteria {

    /** Job title / role keywords — e.g. "Senior Java Developer". */
    private String keywords = "";

    /** Comma-separated technical or domain skills — e.g. "Spring Boot, Kafka, AWS". */
    private String skills = "";

    /** Minimum years of experience (inclusive). {@code null} = no lower bound. */
    private Integer minExperience;

    /** Maximum years of experience (inclusive). {@code null} = no upper bound. */
    private Integer maxExperience;

    /** Preferred candidate location — e.g. "Bangalore", "Mumbai, Pune". */
    private String location = "";

    /** Minimum annual salary in INR Lacs. {@code null} = no lower bound. */
    private Double minSalaryLacs;

    /** Maximum annual salary in INR Lacs. {@code null} = no upper bound. */
    private Double maxSalaryLacs;

    // ─── Getters ──────────────────────────────────────────────────────────────

    public String  getKeywords()      { return keywords; }
    public String  getSkills()        { return skills; }
    public Integer getMinExperience() { return minExperience; }
    public Integer getMaxExperience() { return maxExperience; }
    public String  getLocation()      { return location; }
    public Double  getMinSalaryLacs() { return minSalaryLacs; }
    public Double  getMaxSalaryLacs() { return maxSalaryLacs; }

    // ─── Setters ──────────────────────────────────────────────────────────────

    public void setKeywords(String v)       { this.keywords = v != null ? v : ""; }
    public void setSkills(String v)         { this.skills   = v != null ? v : ""; }
    public void setMinExperience(Integer v) { this.minExperience = v; }
    public void setMaxExperience(Integer v) { this.maxExperience = v; }
    public void setLocation(String v)       { this.location = v != null ? v : ""; }
    public void setMinSalaryLacs(Double v)  { this.minSalaryLacs = v; }
    public void setMaxSalaryLacs(Double v)  { this.maxSalaryLacs = v; }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when neither keywords nor skills have been provided.
     * The UI uses this to block an empty search.
     */
    public boolean isEmpty() {
        return (keywords == null || keywords.isBlank())
            && (skills   == null || skills.isBlank());
    }

    /**
     * Composes all filled criteria into a single natural-language query string
     * suitable for both keyword-based portal searches and the Gemini AI agent.
     *
     * <p>Example output:
     * {@code "Senior Java Developer, skills: Spring Boot, Kafka, Docker,
     * experience: 5-10 years, location: Bangalore, salary: 20-40 Lacs INR"}</p>
     */
    public String toQueryString() {
        StringBuilder sb = new StringBuilder();

        // 1. Keywords / role
        if (keywords != null && !keywords.isBlank()) {
            sb.append(keywords.trim());
        }

        // 2. Skills
        if (skills != null && !skills.isBlank()) {
            appendSep(sb);
            sb.append("skills: ").append(skills.trim());
        }

        // 3. Experience range
        if (minExperience != null || maxExperience != null) {
            appendSep(sb);
            sb.append("experience: ");
            if (minExperience != null && maxExperience != null) {
                sb.append(minExperience).append("-").append(maxExperience).append(" years");
            } else if (minExperience != null) {
                sb.append(minExperience).append("+ years");
            } else {
                sb.append("up to ").append(maxExperience).append(" years");
            }
        }

        // 4. Location
        if (location != null && !location.isBlank()) {
            appendSep(sb);
            sb.append("location: ").append(location.trim());
        }

        // 5. Salary range
        if (minSalaryLacs != null || maxSalaryLacs != null) {
            appendSep(sb);
            sb.append("salary: ");
            if (minSalaryLacs != null && maxSalaryLacs != null) {
                sb.append(formatLacs(minSalaryLacs))
                  .append("-").append(formatLacs(maxSalaryLacs)).append(" Lacs INR");
            } else if (minSalaryLacs != null) {
                sb.append(formatLacs(minSalaryLacs)).append("+ Lacs INR");
            } else {
                sb.append("up to ").append(formatLacs(maxSalaryLacs)).append(" Lacs INR");
            }
        }

        return sb.toString();
    }

    private static void appendSep(StringBuilder sb) {
        if (sb.length() > 0) sb.append(", ");
    }

    /** Format a Lacs value — drop the decimal point when it's a whole number. */
    private static String formatLacs(Double v) {
        return (v == Math.floor(v)) ? String.valueOf(v.intValue()) : String.valueOf(v);
    }

    @Override
    public String toString() {
        return "SearchCriteria{" + toQueryString() + "}";
    }
}

package com.profilescraper.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Represents a single candidate profile extracted from job portals.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CandidateProfile {

    public enum MatchScore {
        HIGH, MEDIUM, LOW, UNKNOWN;

        @JsonCreator
        public static MatchScore from(String value) {
            if (value == null || value.isBlank()) return UNKNOWN;
            return switch (value.trim().toUpperCase()) {
                case "HIGH"   -> HIGH;
                case "MEDIUM" -> MEDIUM;
                case "LOW"    -> LOW;
                default       -> UNKNOWN;
            };
        }

        @JsonValue
        public String toJson() {
            return name().charAt(0) + name().substring(1).toLowerCase();
        }

        public String display() {
            return this == UNKNOWN ? "" : toJson();
        }
    }

    @JsonProperty("fullName")
    private String fullName = "";

    @JsonProperty("currentTitle")
    private String currentTitle = "";

    @JsonProperty("currentCompany")
    private String currentCompany = "";

    @JsonProperty("location")
    private String location = "";

    @JsonProperty("yearsOfExperience")
    private String yearsOfExperience = "";

    @JsonProperty("keySkills")
    private String keySkills = "";

    @JsonProperty("profileUrl")
    private String profileUrl = "";

    @JsonProperty("email")
    private String email = "";

    @JsonProperty("phone")
    private String phone = "";

    @JsonProperty("summary")
    private String summary = "";

    @JsonProperty("status")
    private String status = "";

    @JsonProperty("lastUpdated")
    private String lastUpdated = "";

    @JsonProperty("matchScore")
    private MatchScore matchScore = MatchScore.UNKNOWN;

    // ─── Getters ───────────────────────────────────────────────────────────────

    public String getFullName()          { return safe(fullName); }
    public String getCurrentTitle()      { return safe(currentTitle); }
    public String getCurrentCompany()    { return safe(currentCompany); }
    public String getLocation()          { return safe(location); }
    public String getYearsOfExperience() { return safe(yearsOfExperience); }
    public String getKeySkills()         { return safe(keySkills); }
    public String getProfileUrl()        { return safe(profileUrl); }
    public String getEmail()             { return safe(email); }
    public String getPhone()             { return safe(phone); }
    public String getSummary()           { return safe(summary); }
    public String getStatus()            { return safe(status); }
    public String getLastUpdated()       { return safe(lastUpdated); }
    public MatchScore getMatchScore()    { return matchScore != null ? matchScore : MatchScore.UNKNOWN; }

    // ─── Setters ───────────────────────────────────────────────────────────────

    public void setFullName(String v)          { this.fullName = v; }
    public void setCurrentTitle(String v)      { this.currentTitle = v; }
    public void setCurrentCompany(String v)    { this.currentCompany = v; }
    public void setLocation(String v)          { this.location = v; }
    public void setYearsOfExperience(String v) { this.yearsOfExperience = v; }
    public void setKeySkills(String v)         { this.keySkills = v; }
    public void setProfileUrl(String v)        { this.profileUrl = v; }
    public void setEmail(String v)             { this.email = v; }
    public void setPhone(String v)             { this.phone = v; }
    public void setSummary(String v)           { this.summary = v; }
    public void setStatus(String v)            { this.status = v; }
    public void setLastUpdated(String v)       { this.lastUpdated = v; }
    public void setMatchScore(MatchScore v)    { this.matchScore = v; }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private String safe(String value) {
        return value != null ? value : "";
    }

    @Override
    public String toString() {
        return String.format("CandidateProfile{name='%s', title='%s', company='%s', location='%s', score='%s'}",
                fullName, currentTitle, currentCompany, location, matchScore);
    }
}

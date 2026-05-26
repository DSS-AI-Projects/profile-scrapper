package com.profilescraper;

import com.profilescraper.model.CandidateProfile;

import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Scanner;

/**
 * CLI entry point for the Profile Scraper Agent.
 *
 * Usage:
 *   java -jar profile-scraper-agent-fat.jar [--help]
 *   java -jar profile-scraper-agent-fat.jar "Senior React Developer, 5+ years, Mumbai"
 *
 * Required environment variable: GEMINI_API_KEY
 */
public class Main {

    public static void main(String[] args) throws Exception {

        if (args.length > 0 && (args[0].equals("--help") || args[0].equals("-h"))) {
            printHelp();
            return;
        }

        String jobDescription = args.length > 0
                ? String.join(" ", args)
                : promptForJobDescription();

        if (jobDescription.isBlank()) {
            System.err.println("Error: job description cannot be empty.");
            System.exit(1);
        }

        ProfileScraperAgent agent = new ProfileScraperAgent();
        List<CandidateProfile> profiles = agent.scrapeProfiles(jobDescription);

        if (profiles.isEmpty()) {
            System.err.println("No candidate profiles were found. " +
                    "Try a broader or more specific job description.");
            System.exit(0);
        }

        String fileName  = buildOutputFileName(jobDescription);
        String outputPath = Paths.get(System.getProperty("user.dir"), fileName).toString();

        new ExcelExporter().exportToExcel(profiles, outputPath);

        System.out.println("Excel file saved: " + outputPath);
        System.out.println();
        printSummaryTable(profiles);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private static void printHelp() {
        System.out.println("""
                Profile Scraper Agent — AI-Powered Recruitment Tool
                ─────────────────────────────────────────────────────
                Usage:
                  java -jar profile-scraper-agent-fat.jar
                      Launches interactive mode — you will be prompted for a job description.

                  java -jar profile-scraper-agent-fat.jar "job description"
                      Runs the agent with the provided job description directly.

                  java -jar profile-scraper-agent-fat.jar --help
                      Shows this help message.

                Environment variables:
                  GEMINI_API_KEY   Required. Your Google Gemini API key (free at aistudio.google.com/apikey).

                Output:
                  candidates_<slug>_<date>.xlsx — saved in the current working directory.

                Examples:
                  java -jar profile-scraper-agent-fat.jar "Senior React Developer, 5+ years, Mumbai"
                  java -jar profile-scraper-agent-fat.jar "Data Scientist Python ML Bangalore 3-7 years"
                """);
    }

    private static String promptForJobDescription() {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║           Profile Scraper Agent — AI Recruitment Tool            ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("This agent searches LinkedIn, Naukri, Indeed, and other portals");
        System.out.println("for candidate profiles matching your job requirement.");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  • Senior React Developer with 5+ years, Mumbai, fintech preferred");
        System.out.println("  • Data Scientist, Python/ML, Bangalore, 3-7 years, product companies");
        System.out.println("  • Java Backend Engineer, Spring Boot, AWS, Hyderabad or remote, 4+ years");
        System.out.println();
        System.out.print("Enter job description:\n> ");
        System.out.flush();

        Scanner scanner = new Scanner(System.in);
        return scanner.nextLine().trim();
    }

    private static String buildOutputFileName(String jobDescription) {
        String slug = jobDescription
                .replaceAll("[^a-zA-Z0-9 ]", " ")
                .trim()
                .replaceAll("\\s+", " ")
                .substring(0, Math.min(50, jobDescription.length()))
                .trim()
                .toLowerCase()
                .replaceAll("\\s+", "-");
        return "candidates_" + slug + "_" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".xlsx";
    }

    private static void printSummaryTable(List<CandidateProfile> profiles) {
        // Compute column widths dynamically from actual data
        int nameW    = Math.max(28, profiles.stream().mapToInt(p -> p.getFullName().length()).max().orElse(0));
        int companyW = Math.max(22, profiles.stream().mapToInt(p -> p.getCurrentCompany().length()).max().orElse(0));
        int locW     = Math.max(18, profiles.stream().mapToInt(p -> p.getLocation().length()).max().orElse(0));

        // Cap widths to keep table readable
        nameW    = Math.min(nameW, 36);
        companyW = Math.min(companyW, 30);
        locW     = Math.min(locW, 24);

        String fmt  = "%-4s %-" + nameW + "s %-" + companyW + "s %-" + locW + "s %-8s%n";
        int total   = 4 + 1 + nameW + 1 + companyW + 1 + locW + 1 + 8;

        System.out.printf(fmt, "#", "Name", "Company", "Location", "Score");
        System.out.println("-".repeat(total));

        int i = 1;
        for (CandidateProfile p : profiles) {
            System.out.printf(fmt,
                    i++,
                    truncate(p.getFullName(),       nameW),
                    truncate(p.getCurrentCompany(), companyW),
                    truncate(p.getLocation(),       locW),
                    p.getMatchScore().display());
        }

        System.out.println("-".repeat(total));
        System.out.println("Total: " + profiles.size() + " candidates");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.isEmpty()) return "-";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }
}
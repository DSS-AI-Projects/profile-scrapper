package com.profilescraper.scraper;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import com.profilescraper.model.CandidateProfile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Playwright-based Naukri.com resume / candidate-profile search scraper.
 *
 * <p>Works best with a <em>Naukri Recruiter</em> account that has access to the
 * resume database. A standard job-seeker account cannot search other candidates.</p>
 */
public class NaukriScraper extends AbstractPortalScraper {

    private static final String LOGIN_URL        = "https://www.naukri.com/nlogin/login";
    private static final String HOME_URL         = "https://www.naukri.com/";
    private static final String RECRUITER_SEARCH = "https://www.naukri.com/recruiter/resumesearchresults/";

    private static final String[] EMAIL_SELECTORS = {
        "#usernameField",
        "input[placeholder*='Email']",
        "input[placeholder*='email']",
        "input[name='username']",
        "input[type='email']"
    };
    private static final String[] PASS_SELECTORS = {
        "#passwordField",
        "input[placeholder*='Password']",
        "input[placeholder*='password']",
        "input[name='password']",
        "input[type='password']"
    };
    private static final String[] SUBMIT_SELECTORS = {
        "button[type='submit']",
        "input[type='submit']",
        "button:has-text('Login')",
        "button:has-text('Sign In')"
    };

    @Override
    public JobPortal getPortal() { return JobPortal.NAUKRI; }

    // ─── Public API ───────────────────────────────────────────────────────────────

    @Override
    public List<CandidateProfile> scrapeProfiles(String jobDescription,
                                                  String username,
                                                  String password) throws Exception {
        List<CandidateProfile> profiles = new ArrayList<>();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = createBrowser(playwright);
            BrowserContext context = createContext(browser);
            Page page = context.newPage();
            page.setDefaultTimeout(60_000);

            // ── 1. Login ──────────────────────────────────────────────────────────
            login(page, username, password);

            // ── 2. Route by account type ──────────────────────────────────────────
            String  keywords    = extractKeywords(jobDescription);
            boolean isRecruiter = page.url().contains("recruiter")
                                || page.url().contains("mnr")
                                || page.url().contains("rms");

            logger.info("Naukri: account type = {}", isRecruiter ? "recruiter" : "candidate");

            if (isRecruiter) {
                profiles.addAll(scrapeRecruiterSearch(page, keywords));
            } else {
                throw new Exception(
                        "Naukri candidate/resume search requires a Naukri Recruiter account. "
                        + "Your account appears to be a job-seeker account. "
                        + "Please log in with a recruiter/HR account to search candidate profiles.");
            }

            browser.close();
        }

        logger.info("Naukri scraping complete — {} profiles found.", profiles.size());
        return profiles;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────────

    private void login(Page page, String username, String password) throws Exception {
        logger.info("Naukri: navigating to login page");
        page.navigate(LOGIN_URL,
                new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(60_000));
        page.waitForTimeout(2_000);

        boolean filled = tryFillLoginForm(page, username, password);
        if (!filled) {
            // Fallback: home page → click Login link → try form again
            page.navigate(HOME_URL,
                    new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(60_000));
            page.waitForTimeout(1_500);
            try {
                page.click("a[href*='login'], button:has-text('Login')",
                        new Page.ClickOptions().setTimeout(5_000));
                page.waitForTimeout(1_500);
            } catch (Exception ignored) {}
            filled = tryFillLoginForm(page, username, password);
        }

        if (!filled) {
            throw new Exception(
                    "Naukri: could not locate the login form. "
                    + "The site may be temporarily unavailable or has updated its layout.");
        }

        // Wait for redirect off the login page
        try {
            page.waitForURL(url -> !url.contains("/login"),
                    new Page.WaitForURLOptions().setTimeout(20_000));
        } catch (Exception ex) {
            // Naukri sometimes stays on the same URL after login;
            // treat as OK unless there's a visible error message.
            String err = safePageText(page, ".errorMessage, .err-msg, [class*='error'], [class*='Error']");
            if (!err.isBlank() && err.length() < 300) {
                throw new Exception("Naukri login failed: " + err);
            }
        }

        logger.info("Naukri: login complete. URL = {}", page.url());
    }

    /** Try to find, fill, and submit the Naukri login form. Returns true if submitted. */
    private boolean tryFillLoginForm(Page page, String username, String password) {
        try {
            ElementHandle emailEl  = findVisible(page, EMAIL_SELECTORS,  15_000);
            ElementHandle passEl   = findVisible(page, PASS_SELECTORS,   5_000);
            if (emailEl == null || passEl == null) return false;

            emailEl.fill(username);
            passEl.fill(password);

            ElementHandle submit = findVisible(page, SUBMIT_SELECTORS, 5_000);
            if (submit != null) submit.click();

            page.waitForTimeout(2_000);
            return true;
        } catch (Exception e) {
            logger.warn("Naukri: login form fill error — {}", e.getMessage());
            return false;
        }
    }

    /** Return the first visible element matching any selector, waiting up to totalMs. */
    private ElementHandle findVisible(Page page, String[] selectors, int totalMs) {
        int perSel = Math.max(1_500, totalMs / selectors.length);
        for (String sel : selectors) {
            try {
                page.waitForSelector(sel,
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(perSel));
                ElementHandle el = page.querySelector(sel);
                if (el != null && el.isVisible()) return el;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private List<CandidateProfile> scrapeRecruiterSearch(Page page, String keywords) {
        List<CandidateProfile> results = new ArrayList<>();
        try {
            String searchUrl = RECRUITER_SEARCH + "?q="
                    + URLEncoder.encode(keywords, StandardCharsets.UTF_8);
            logger.info("Naukri: recruiter search → {}", searchUrl);
            page.navigate(searchUrl,
                    new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(60_000));
            page.waitForTimeout(3_000);

            List<ElementHandle> cards = page.querySelectorAll(
                    ".srp-jobtuple-wrapper, .resume-tuple, [class*='resumeCard'], "
                    + "[class*='resumeTuple'], [class*='candidateCard']");

            logger.debug("Naukri: {} recruiter result cards", cards.size());

            for (ElementHandle card : cards) {
                try {
                    CandidateProfile p = extractRecruiterProfile(card);
                    if (p != null && !p.getFullName().isBlank()) results.add(p);
                } catch (Exception e) {
                    logger.debug("Naukri: skipping card — {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("Naukri: recruiter search error — {}", e.getMessage());
        }
        return results;
    }

    private CandidateProfile extractRecruiterProfile(ElementHandle card) {
        CandidateProfile p = new CandidateProfile();
        p.setFullName(safeText(card,        ".name, [class*='name'], .candidate-name, [class*='candidateName']"));
        p.setCurrentTitle(safeText(card,    ".designation, [class*='designation'], [class*='title']"));
        p.setCurrentCompany(safeText(card,  ".company, [class*='company'], .org-name, [class*='orgName']"));
        p.setLocation(safeText(card,        ".location, [class*='location'], .loc, [class*='loc']"));
        p.setYearsOfExperience(safeText(card, ".experience, [class*='experience'], .exp, [class*='exp']"));
        p.setKeySkills(safeText(card,       ".skills, [class*='skills'], .key-skill, [class*='keySkill']"));

        String href = safeAttr(card, "a[href*='resume'], a[href*='profile']", "href");
        if (!href.isBlank()) {
            p.setProfileUrl(href.startsWith("http")
                    ? href : "https://www.naukri.com" + href);
        }
        p.setMatchScore(CandidateProfile.MatchScore.MEDIUM);
        return p;
    }
}

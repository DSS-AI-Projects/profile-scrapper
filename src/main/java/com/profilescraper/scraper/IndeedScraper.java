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
 * Playwright-based Indeed Resume search scraper.
 *
 * <p>Indeed Resume is available to <em>employer / recruiter</em> accounts only.
 * Personal job-seeker accounts cannot search candidate resumes.</p>
 */
public class IndeedScraper extends AbstractPortalScraper {

    private static final String LOGIN_URL     = "https://secure.indeed.com/account/login";
    private static final String RESUME_SEARCH = "https://resumes.indeed.com/search";

    private static final String[] EMAIL_SELECTORS = {
        "#login-email-input",
        "input[name='email']",
        "input[type='email']",
        "input[autocomplete='username']",
        "input[autocomplete='email']"
    };
    private static final String[] PASS_SELECTORS = {
        "#login-password-input",
        "input[name='password']",
        "input[type='password']",
        "input[autocomplete='current-password']"
    };

    @Override
    public JobPortal getPortal() { return JobPortal.INDEED; }

    // ─── Public API ───────────────────────────────────────────────────────────────

    @Override
    public List<CandidateProfile> scrapeProfiles(String jobDescription,
                                                  String location,
                                                  String username,
                                                  String password) throws Exception {
        // Fold the location into the search terms; filtering it out afterwards is too late.
        jobDescription = SearchTerms.withLocation(jobDescription, location);
        List<CandidateProfile> profiles = new ArrayList<>();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = createBrowser(playwright);
            BrowserContext context = createContext(browser);
            Page page = context.newPage();
            page.setDefaultTimeout(60_000);

            // ── 1. Login ──────────────────────────────────────────────────────────
            login(page, username, password);

            // ── 2. Navigate to Resume Search ──────────────────────────────────────
            String keywords  = extractKeywords(jobDescription);
            String searchUrl = RESUME_SEARCH + "?q="
                    + URLEncoder.encode(keywords, StandardCharsets.UTF_8);

            logger.info("Indeed: navigating to resume search — {}", searchUrl);
            page.navigate(searchUrl,
                    new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(60_000));
            page.waitForTimeout(3_000);

            // ── 3. Check account access ───────────────────────────────────────────
            String bodyLower = page.content().toLowerCase();
            if (bodyLower.contains("upgrade your plan")
                    || bodyLower.contains("post a job to get started")
                    || bodyLower.contains("employer plan")
                    || bodyLower.contains("subscription required")) {
                throw new Exception(
                        "Indeed Resume search requires an employer / recruiter account with an active plan. "
                        + "Please use an employer account, or try a different portal.");
            }

            // ── 4. Extract resume cards ───────────────────────────────────────────
            List<ElementHandle> cards = page.querySelectorAll(
                    ".sl-snippet-content, [data-tn-element='resume'], "
                    + ".resume-result, [class*='resumeResult'], [class*='ResumeResult']");

            logger.debug("Indeed: {} resume cards found", cards.size());

            for (ElementHandle card : cards) {
                try {
                    CandidateProfile p = extractProfile(card);
                    if (p != null && !p.getFullName().isBlank()) profiles.add(p);
                } catch (Exception e) {
                    logger.debug("Indeed: skipping card — {}", e.getMessage());
                }
            }

            browser.close();
        }

        logger.info("Indeed scraping complete — {} profiles found.", profiles.size());
        return profiles;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────────

    private void login(Page page, String username, String password) throws Exception {
        logger.info("Indeed: navigating to login page");
        page.navigate(LOGIN_URL,
                new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(60_000));
        page.waitForTimeout(2_000);

        // ── Email step ────────────────────────────────────────────────────────────
        ElementHandle emailField = findVisible(page, EMAIL_SELECTORS, 20_000);
        if (emailField == null) {
            throw new Exception(
                    "Indeed: could not find the email input on the login page. "
                    + "The login page layout may have changed.");
        }

        emailField.fill(username);

        // Click the "Continue" button (Indeed's login is sometimes two-step)
        ElementHandle continueBtn = findVisible(page,
                new String[]{"#login-submit-button", "button[type='submit']"}, 5_000);
        if (continueBtn != null) {
            continueBtn.click();
            page.waitForTimeout(2_000);
        }

        // ── Password step ─────────────────────────────────────────────────────────
        ElementHandle passField = findVisible(page, PASS_SELECTORS, 15_000);
        if (passField != null) {
            passField.fill(password);
            ElementHandle loginBtn = findVisible(page,
                    new String[]{"#login-submit-button", "button[type='submit']"}, 5_000);
            if (loginBtn != null) {
                loginBtn.click();
                page.waitForTimeout(2_500);
            }
        } else {
            throw new Exception(
                    "Indeed: password field not found after email step. "
                    + "The login flow may have changed.");
        }

        // ── Check for verification ────────────────────────────────────────────────
        String currentUrl = page.url();
        logger.info("Indeed: after-login URL = {}", currentUrl);

        if (currentUrl.contains("challenge") || currentUrl.contains("verify")
                || currentUrl.contains("captcha")) {
            throw new Exception(
                    "Indeed requires account verification. "
                    + "Please complete the verification in your browser first, then retry.");
        }

        // Check for inline error messages
        String errText = safePageText(page,
                "[class*='error'], [class*='Error'], .alert-danger, #error-container");
        if (!errText.isBlank() && errText.length() < 300) {
            throw new Exception("Indeed login failed: " + errText);
        }

        logger.info("Indeed: login appears successful");
    }

    /** Return the first visible element matching any selector within {@code totalMs}. */
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

    private CandidateProfile extractProfile(ElementHandle card) {
        CandidateProfile p = new CandidateProfile();
        p.setFullName(safeText(card,            "a.sl-header, [data-tn-component='resumeName'], h2 a, .applicant-name"));
        p.setCurrentTitle(safeText(card,        ".sl-headline, [data-tn-component='jobTitle'], .title, .headline"));
        p.setCurrentCompany(safeText(card,      ".sl-employer, [data-tn-component='company'], .company"));
        p.setLocation(safeText(card,            ".sl-city, [data-tn-component='location'], .location"));
        p.setYearsOfExperience(safeText(card,   ".sl-years, [data-tn-component='years'], .years-experience"));
        p.setKeySkills(safeText(card,           ".icl-Wrap-tight span, .skills-list, [class*='skills']"));

        String href = safeAttr(card, "a[href*='resume'], a[href*='applicant']", "href");
        if (!href.isBlank()) {
            p.setProfileUrl(href.startsWith("http")
                    ? href : "https://resumes.indeed.com" + href);
        }
        p.setMatchScore(CandidateProfile.MatchScore.MEDIUM);
        return p;
    }
}

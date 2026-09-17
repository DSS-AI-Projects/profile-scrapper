package com.profilescraper.scraper;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import com.profilescraper.model.CandidateProfile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Playwright-based LinkedIn people-search + profile-page scraper.
 *
 * <h3>Search strategy (ordered by reliability)</h3>
 * <ol>
 *   <li>Navigate directly to LinkedIn {@code /search/results/people/} with the
 *       {@code title=} filter (LinkedIn-native job-title filter, best for role searches).</li>
 *   <li>Fallback URLs in a ladder — broadening the query on each zero-result step.</li>
 *   <li>Last resort: type the query into LinkedIn's global search bar and click
 *       the "People" filter — more human-like, bypasses some bot-detection that
 *       applies to direct URL navigation.</li>
 * </ol>
 *
 * <h3>Extraction approach</h3>
 * Results are extracted via {@code page.evaluate()} (JS inside the browser) so
 * {@code link.href} always returns a fully-resolved absolute URL — immune to
 * relative-URL ambiguity and CSS class renames.
 *
 * <h3>Common failure modes detected</h3>
 * <ul>
 *   <li>LinkedIn "commercial use limit" — free-account weekly keyword-search cap
 *       (name searches still work).  Detected and surfaced as a clear error.</li>
 *   <li>Security challenge / CAPTCHA — detected by URL pattern.</li>
 *   <li>Auth-wall redirect — detected by URL pattern.</li>
 * </ul>
 */
public class LinkedInScraper extends AbstractPortalScraper {

    private static final String LOGIN_URL  = "https://www.linkedin.com/login";
    private static final String FEED_URL   = "https://www.linkedin.com/feed/";
    private static final String SEARCH_URL = "https://www.linkedin.com/search/results/people/";

    private static final int MAX_PROFILES_TO_ENRICH = 30;
    private static final int MAX_SEARCH_PAGES        = 5;   // 10 per page → up to 50 candidates

    private static final Path SESSION_DIR = Paths.get(
            System.getProperty("user.home"), ".profile-scraper", "linkedin-session");

    private static final String[] USERNAME_SELECTORS = {
        "#username", "input[name='session_key']",
        "input[autocomplete='username']", "input[type='email']"
    };
    private static final String[] PASSWORD_SELECTORS = {
        "#password", "input[name='session_password']",
        "input[autocomplete='current-password']", "input[type='password']"
    };


    @Override
    public JobPortal getPortal() { return JobPortal.LINKEDIN; }

    // ─── Public entry point ───────────────────────────────────────────────────────

    @Override
    public List<CandidateProfile> scrapeProfiles(String jobDescription,
                                                  String location,
                                                  String username,
                                                  String password) throws Exception {
        // Fold the location into the search terms; filtering it out afterwards is too late.
        jobDescription = SearchTerms.withLocation(jobDescription, location);
        Files.createDirectories(SESSION_DIR);
        List<CandidateProfile> profiles = new ArrayList<>();

        try (Playwright playwright = Playwright.create()) {
            BrowserContext context = playwright.chromium().launchPersistentContext(
                    SESSION_DIR,
                    new BrowserType.LaunchPersistentContextOptions()
                            .setHeadless(false)
                            .setSlowMo(60)
                            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                    + "Chrome/120.0.0.0 Safari/537.36")
                            .setViewportSize(1280, 800)
                            .setLocale("en-US")
                            .setArgs(List.of("--no-sandbox","--disable-setuid-sandbox",
                                    "--disable-blink-features=AutomationControlled",
                                    "--disable-infobars"))
            );

            Page page = context.newPage();
            page.setDefaultTimeout(60_000);

            // ── 1. Login / reuse session ──────────────────────────────────────────
            if (isLoggedIn(page)) {
                logger.info("LinkedIn: reusing saved session");
            } else {
                login(page, username, password);
            }

            // ── 2. Single search with the full job description as keywords ───────────
            String searchUrl = buildSearchUrl(jobDescription);
            logger.info("LinkedIn: searching → {}", searchUrl);

            List<CandidateProfile> raw = searchPeople(page, searchUrl);
            logger.info("LinkedIn: {} profile(s) found", raw.size());

            // ── 3. Surface commercial-use-limit as a user-facing error ────────────
            if (raw.isEmpty()) {
                String lastBlockReason = detectBlockedPage(page);
                if (lastBlockReason != null && lastBlockReason.contains("commercial-use-limit")) {
                    context.close();
                    throw new Exception(
                        "LinkedIn has reached the free-account weekly keyword-search limit for this account. "
                        + "Searching by a person's name still works. "
                        + "To search by job title / skills, please upgrade to LinkedIn Premium, "
                        + "or wait until the weekly limit resets (usually Monday).");
                }
                logger.warn("LinkedIn: all search strategies returned 0 profiles.");
                logPageDiagnostic(page);
            }

            // ── 5. Deduplicate ────────────────────────────────────────────────────
            List<CandidateProfile> unique = deduplicateByUrl(raw);
            logger.info("LinkedIn: {} unique profiles after deduplication", unique.size());

            // ── 6. Enrich top N profiles by visiting each profile page ───────────────
            // Profiles beyond MAX_PROFILES_TO_ENRICH still appear in results with the
            // basic data extracted from the search cards (name, title, company, location).
            int toEnrich = Math.min(MAX_PROFILES_TO_ENRICH, unique.size());
            for (int i = 0; i < toEnrich; i++) {
                visitProfilePage(page, unique.get(i));
                if (i < toEnrich - 1) page.waitForTimeout(1_200);
            }
            logger.info("LinkedIn: enriched {}/{} profiles", toEnrich, unique.size());

            profiles.addAll(unique);   // return ALL candidates, not just the enriched subset
            context.close();
        }

        logger.info("LinkedIn complete — {} profiles returned.", profiles.size());
        return profiles;
    }

    // ─── Search URL ───────────────────────────────────────────────────────────────

    /**
     * Build the LinkedIn People search URL from the job description.
     * The full (lightly cleaned) job description is passed as the {@code keywords}
     * parameter so LinkedIn's own relevance engine decides what matters.
     */
    private String buildSearchUrl(String jobDescription) {
        String keywords = jobDescription
                .replaceAll("[^a-zA-Z0-9 ]", " ")
                .trim()
                .replaceAll("\\s+", " ");
        if (keywords.length() > 200) keywords = keywords.substring(0, 200).trim();
        try {
            return SEARCH_URL + "?keywords="
                    + URLEncoder.encode(keywords, StandardCharsets.UTF_8)
                    + "&origin=GLOBAL_SEARCH_HEADER";
        } catch (Exception e) {
            return SEARCH_URL + "?keywords=" + keywords.replace(" ", "+")
                    + "&origin=GLOBAL_SEARCH_HEADER";
        }
    }

    // ─── Search execution ─────────────────────────────────────────────────────────

    private List<CandidateProfile> searchPeople(Page page, String searchUrl) {
        navigateAndWait(page, searchUrl);
        List<CandidateProfile> results = new ArrayList<>(extractResultsPage(page));
        logger.info("LinkedIn: page 1 → {} result(s)", results.size());

        // Paginate through subsequent pages until empty page or MAX_SEARCH_PAGES reached
        for (int pageNum = 2; pageNum <= MAX_SEARCH_PAGES; pageNum++) {
            if (results.isEmpty()) break;   // page 1 already empty — don't hammer more pages
            try {
                String sep     = searchUrl.contains("?") ? "&" : "?";
                String pageUrl = searchUrl + sep + "page=" + pageNum;
                navigateAndWait(page, pageUrl);
                List<CandidateProfile> pageResults = extractResultsPage(page);
                if (pageResults.isEmpty()) {
                    logger.info("LinkedIn: page {} returned 0 results — stopping pagination", pageNum);
                    break;
                }
                results.addAll(pageResults);
                logger.info("LinkedIn: page {} → {} result(s) (running total: {})",
                        pageNum, pageResults.size(), results.size());
            } catch (Exception e) {
                logger.debug("LinkedIn: pagination stopped at page {} — {}", pageNum, e.getMessage());
                break;
            }
        }
        return results;
    }

    // ─── Result extraction ────────────────────────────────────────────────────────

    private List<CandidateProfile> extractResultsPage(Page page) {
        logger.info("LinkedIn: extracting results — URL=[{}] title=[{}]",
                page.url(), page.title());

        // ── Guard: blocked / restricted page ─────────────────────────────────────
        String blockReason = detectBlockedPage(page);
        if (blockReason != null) {
            logger.warn("LinkedIn: page is blocked/restricted — reason=[{}]", blockReason);
            return new ArrayList<>();
        }

        // ── Dismiss any modal overlays ────────────────────────────────────────────
        dismissModals(page);

        // ── Scroll down to trigger lazy-loading, then back to top ────────────────
        // Scrolling down loads all lazy-rendered result cards.
        // Scrolling back to top afterwards keeps those cards in the DOM even when
        // LinkedIn uses a virtual/windowed list (removes off-viewport nodes).
        for (int i = 0; i < 6; i++) {
            try {
                page.evaluate("window.scrollBy(0, 500)");
                page.waitForTimeout(800);
            } catch (Exception ignored) {}
        }
        try { page.waitForTimeout(1_000); } catch (Exception ignored) {}
        try {
            page.evaluate("window.scrollTo(0, 0)");   // back to top
            page.waitForTimeout(1_000);
        } catch (Exception ignored) {}

        // ── Strategy 1–4: structural CSS selectors ────────────────────────────────
        List<ElementHandle> cards = page.querySelectorAll("li.reusable-search__result-container");
        if (!cards.isEmpty()) {
            logger.info("LinkedIn: strategy-1 (reusable-search__result-container) → {} cards", cards.size());
            return cardsToProfiles(cards);
        }

        cards = page.querySelectorAll(".entity-result");
        if (!cards.isEmpty()) {
            logger.info("LinkedIn: strategy-2 (entity-result) → {} cards", cards.size());
            return cardsToProfiles(cards);
        }

        cards = page.querySelectorAll("[data-chameleon-result-urn]");
        if (!cards.isEmpty()) {
            logger.info("LinkedIn: strategy-3 (chameleon-result-urn) → {} cards", cards.size());
            return cardsToProfiles(cards);
        }

        cards = page.querySelectorAll("div[class*='entity-result'], div[class*='search-result__info']");
        if (!cards.isEmpty()) {
            logger.info("LinkedIn: strategy-4 (entity-result div) → {} cards", cards.size());
            return cardsToProfiles(cards);
        }

        // ── Strategy 5: JavaScript /in/ link scan (most robust fallback) ──────────
        logger.info("LinkedIn: no structural selector matched — running JS extraction");
        List<CandidateProfile> jsResults = extractWithJavaScript(page);
        logger.info("LinkedIn: JS extraction → {} profiles", jsResults.size());

        if (jsResults.isEmpty()) logPageDiagnostic(page);
        return jsResults;
    }

    /**
     * Detect common LinkedIn restriction pages.
     *
     * @return a short description string when blocked, {@code null} when page looks normal.
     */
    private String detectBlockedPage(Page page) {
        try {
            String url = page.url().toLowerCase();
            if (url.contains("/login") || url.contains("/authwall") || url.contains("/uas/login")) {
                return "auth-wall: " + page.url();
            }
            if (url.contains("checkpoint") || url.contains("challenge") || url.contains("captcha")) {
                return "security-challenge: " + page.url();
            }
            String body = page.innerText("body").toLowerCase();
            if (body.contains("commercial use limit")
                    || body.contains("you've reached the weekly")
                    || body.contains("weekly search limit")
                    || body.contains("get more results with linkedin premium")
                    || body.contains("upgrade to premium to see")) {
                return "commercial-use-limit";
            }
            if (body.contains("join linkedin to see") || body.contains("sign up to see")) {
                return "sign-in-gate";
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Attempt to close any modal / toast overlay that might cover search results. */
    private void dismissModals(Page page) {
        String[] dismissSelectors = {
            "[aria-label='Dismiss']",
            "button.artdeco-modal__dismiss",
            "[data-test-modal-close-btn]",
            "button[aria-label='Close']",
            ".artdeco-toast-item__dismiss",
            "[aria-label='Dismiss upgrade nudge']"
        };
        for (String sel : dismissSelectors) {
            try {
                ElementHandle btn = page.querySelector(sel);
                if (btn != null && btn.isVisible()) {
                    btn.click();
                    page.waitForTimeout(400);
                }
            } catch (Exception ignored) {}
        }
    }

    private List<CandidateProfile> cardsToProfiles(List<ElementHandle> cards) {
        List<CandidateProfile> results = new ArrayList<>();
        for (ElementHandle card : cards) {
            try {
                CandidateProfile p = extractFromCard(card);
                if (p != null && !p.getFullName().isBlank()) results.add(p);
            } catch (Exception e) { logger.debug("LinkedIn: card skip — {}", e.getMessage()); }
        }
        return results;
    }

    private CandidateProfile extractFromCard(ElementHandle card) {
        CandidateProfile p = new CandidateProfile();
        ElementHandle link = card.querySelector(".entity-result__title-text a");
        if (link == null) link = card.querySelector("a.app-aware-link");
        if (link != null) {
            String href = link.getAttribute("href");
            if (href != null) p.setProfileUrl(href.split("\\?")[0].trim());
            ElementHandle span = link.querySelector("span[aria-hidden='true']");
            String name = span != null ? span.innerText().trim() : link.innerText().trim();
            p.setFullName(cleanName(name));
        }
        p.setMatchScore(CandidateProfile.MatchScore.MEDIUM);
        return p;
    }

    /**
     * JavaScript-based extraction — runs inside the browser so {@code link.href}
     * gives the fully-resolved absolute URL.
     *
     * <p>Returns a wrapper map {@code {results:[…], debug:{…}}} so the Java side
     * can log exactly how many links were found, how many passed each filter, and
     * why profiles were (or were not) skipped.</p>
     *
     * <p>Name extraction tries five sources in order:
     * <ol>
     *   <li>anchor {@code aria-label} ("View John Doe's profile")</li>
     *   <li>{@code span[aria-hidden="true"]} inside the anchor</li>
     *   <li>nearest {@code <img alt="…">} in the result card container</li>
     *   <li>full {@code textContent} of the anchor</li>
     *   <li>slug-derived name ("john-doe" → "John Doe") — last resort so the
     *       profile is never silently dropped; {@link #visitProfilePage} will
     *       overwrite it with the real name from the profile page.</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private List<CandidateProfile> extractWithJavaScript(Page page) {
        try {
            Map<String, Object> output = (Map<String, Object>) page.evaluate("""
                (() => {
                    const results = [];
                    const seen    = new Set();
                    const SKIP    = new Set([
                        'mynetwork','notifications','jobs','messaging','settings','learning',
                        'company','school','groups','events','login','signup','search',
                        'feed','home','add','share','post','article','directory',
                        'checkpoint','pulse','hashtag','in','followers','following',
                        'connections','premium','recruiter','talent','sales'
                    ]);
                    const debug = {
                        totalLinks: 0, inLinks: 0, dupSkipped: 0,
                        skipListFiltered: 0, nameFromAria: 0, nameFromSpan: 0,
                        nameFromImg: 0, nameFromText: 0, nameFromSlug: 0,
                        addedCount: 0
                    };

                    const allLinks = Array.from(document.querySelectorAll('a'));
                    debug.totalLinks = allLinks.length;

                    for (const link of allLinks) {
                        // link.href is always the fully-resolved absolute URL
                        const href = link.href || '';
                        if (!href.includes('linkedin.com')) continue;

                        // Extract profile slug from /in/<slug>
                        const m = href.match(/\\/in\\/([^\\/?#]+)/);
                        if (!m) continue;
                        debug.inLinks++;

                        const slug = m[1];
                        if (!slug || slug.length < 2) continue;
                        if (SKIP.has(slug.toLowerCase())) { debug.skipListFiltered++; continue; }

                        const profileUrl = 'https://www.linkedin.com/in/' + slug + '/';
                        if (seen.has(profileUrl)) { debug.dupSkipped++; continue; }
                        seen.add(profileUrl);

                        // ── Name: source 1 — anchor aria-label ────────────────────
                        let name = '';
                        const aria = link.getAttribute('aria-label') || '';
                        if (aria) {
                            const candidate = aria
                                .replace(/^view\\s+/i, '')
                                .replace(/'?s\\s+(linkedin\\s+)?profile.*/i, '')
                                .trim();
                            if (candidate.length >= 2) { name = candidate; debug.nameFromAria++; }
                        }

                        // ── Name: source 2 — span[aria-hidden] inside the link ─────
                        if (!name) {
                            const span = link.querySelector('span[aria-hidden="true"]');
                            if (span) {
                                const t = (span.textContent || '').trim().split('\\n')[0].trim();
                                if (t.length >= 2) { name = t; debug.nameFromSpan++; }
                            }
                        }

                        // ── Name: source 3 — <img alt="…"> in the result card ──────
                        if (!name) {
                            const container = link.closest('li')
                                           || link.closest('[class*="result"]')
                                           || link.parentElement;
                            if (container) {
                                for (const img of container.querySelectorAll('img[alt]')) {
                                    const alt = (img.getAttribute('alt') || '').trim();
                                    // Skip company logos / generic labels
                                    if (alt.length >= 2 && alt.length < 80
                                            && !alt.toLowerCase().includes('logo')
                                            && !alt.toLowerCase().includes('banner')) {
                                        name = alt
                                            .replace(/^(profile\\s+(photo|picture)\\s+of|photo\\s+of)\\s+/i, '')
                                            .trim();
                                        if (name.length >= 2) { debug.nameFromImg++; break; }
                                        else name = '';
                                    }
                                }
                            }
                        }

                        // ── Name: source 4 — link textContent ─────────────────────
                        if (!name) {
                            const t = (link.textContent || '').trim().split('\\n')[0].trim();
                            if (t.length >= 2) { name = t; debug.nameFromText++; }
                        }

                        // ── Name: source 5 — derive from URL slug (last resort) ────
                        // e.g. "john-doe-12345" → "John Doe 12345"
                        // visitProfilePage() will replace this with the real name.
                        if (!name || name.length < 2) {
                            name = slug
                                .replace(/-+/g, ' ')
                                .replace(/\\b\\w/g, c => c.toUpperCase())
                                .trim();
                            if (name.length >= 2) debug.nameFromSlug++;
                        }

                        if (!name || name.length < 2) continue; // truly unrecoverable

                        // Strip LinkedIn degree badge ("• 3rd+", "• 2nd", etc.)
                        name = name
                            .replace(/\\s*[•·]\\s*(1st|2nd|3rd\\+?|Following|You).*$/, '')
                            .trim();

                        // ── Title / location from the result-card container ────────
                        let title = '', location = '';
                        const container = link.closest('li')
                                       || link.closest('[class*="result"]')
                                       || link.parentElement;
                        if (container) {
                            const textNodes = [];
                            for (const el of container.querySelectorAll(
                                    'span[aria-hidden="true"], div, span')) {
                                if (el.children.length > 0) continue; // skip wrappers
                                const t = (el.textContent || '').trim();
                                if (t && t !== name && t.length > 1 && t.length < 120)
                                    textNodes.push(t);
                            }
                            if (textNodes.length > 0) title    = textNodes[0];
                            if (textNodes.length > 1) location = textNodes[1];
                        }

                        debug.addedCount++;
                        results.push({ name, profileUrl, title, location });
                    }

                    return { results, debug };
                })()
            """);

            List<CandidateProfile> profiles = new ArrayList<>();
            if (output == null) return profiles;

            // ── Log extraction counters ───────────────────────────────────────────
            @SuppressWarnings("unchecked")
            Map<String, Object> debug = (Map<String, Object>) output.get("debug");
            if (debug != null) {
                logger.info("LinkedIn JS extraction: totalLinks={}, /in/ links={}, "
                        + "skipList={}, dups={}, added={} "
                        + "(nameFromAria={} span={} img={} text={} slug={})",
                        debug.get("totalLinks"), debug.get("inLinks"),
                        debug.get("skipListFiltered"), debug.get("dupSkipped"),
                        debug.get("addedCount"),
                        debug.get("nameFromAria"), debug.get("nameFromSpan"),
                        debug.get("nameFromImg"), debug.get("nameFromText"),
                        debug.get("nameFromSlug"));
            }

            @SuppressWarnings("unchecked")
            List<Map<String, String>> extracted =
                    (List<Map<String, String>>) output.get("results");
            if (extracted == null) return profiles;

            for (Map<String, String> item : extracted) {
                CandidateProfile p = new CandidateProfile();
                p.setFullName(item.getOrDefault("name", "").trim());
                p.setProfileUrl(item.getOrDefault("profileUrl", "").trim());
                p.setCurrentTitle(cleanDegree(item.getOrDefault("title", "")));
                p.setLocation(item.getOrDefault("location", ""));
                p.setMatchScore(CandidateProfile.MatchScore.MEDIUM);
                if (!p.getFullName().isBlank()) profiles.add(p);
            }
            return profiles;

        } catch (Exception e) {
            logger.warn("LinkedIn: JS extraction error — {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // ─── Session / login ──────────────────────────────────────────────────────────

    private boolean isLoggedIn(Page page) {
        try {
            page.navigate(FEED_URL,
                    new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(30_000));
            page.waitForTimeout(2_000);
            String url = page.url();
            boolean ok = !url.contains("/login") && !url.contains("/uas/login")
                      && !url.contains("/authwall") && !url.contains("/checkpoint");
            logger.info("LinkedIn: session check → {} (url={})", ok ? "VALID" : "EXPIRED", url);
            return ok;
        } catch (Exception e) {
            logger.warn("LinkedIn: session check error — {}", e.getMessage());
            return false;
        }
    }

    private void login(Page page, String username, String password) throws Exception {
        page.navigate(LOGIN_URL,
                new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(60_000));
        page.waitForTimeout(1_500);

        ElementHandle usernameEl = findVisible(page, USERNAME_SELECTORS, 30_000);
        if (usernameEl == null) throw new Exception(
                "LinkedIn login form not found. The browser window is open — "
                + "check for a CAPTCHA or security challenge, complete it, then retry.");

        ElementHandle passEl = findVisible(page, PASSWORD_SELECTORS, 10_000);
        if (passEl == null) throw new Exception("LinkedIn login: password field not found.");

        usernameEl.fill(username);
        passEl.fill(password);
        page.click("button[type='submit']");

        try {
            page.waitForURL(
                    url -> !url.contains("/login") && !url.contains("/uas/login"),
                    new Page.WaitForURLOptions().setTimeout(30_000));
        } catch (Exception ex) {
            throw new Exception("LinkedIn login timed out — check the browser for a CAPTCHA, "
                    + "complete it, then retry.");
        }

        String afterUrl = page.url();
        if (afterUrl.contains("checkpoint") || afterUrl.contains("challenge")
                || afterUrl.contains("captcha") || afterUrl.contains("security")) {
            throw new Exception("LinkedIn needs additional verification. Complete it in the "
                    + "browser window (session is saved — only needed once).");
        }
        logger.info("LinkedIn: login successful. URL = {}", afterUrl);
    }

    // ─── Navigation ───────────────────────────────────────────────────────────────

    private void navigateAndWait(Page page, String url) {
        page.navigate(url,
                new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(60_000));
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(15_000));
        } catch (Exception e) {
            logger.debug("LinkedIn: networkidle timeout (continuing)");
        }
        // Extra dwell so React/XHR results finish rendering
        page.waitForTimeout(4_000);
    }

    // ─── Profile-page enrichment ──────────────────────────────────────────────────

    private void visitProfilePage(Page page, CandidateProfile profile) {
        String url = profile.getProfileUrl();
        if (url == null || url.isBlank()) return;
        try {
            logger.info("LinkedIn: enriching profile — {}", url);
            page.navigate(url,
                    new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(30_000));
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(8_000));
            } catch (Exception ignored) {}
            page.waitForTimeout(1_500);

            @SuppressWarnings("unchecked")
            Map<String, String> data = (Map<String, String>) page.evaluate("""
                (() => {
                    const r = { name:'', headline:'', location:'' };
                    const h1 = document.querySelector('h1');
                    if (h1) r.name = h1.innerText.trim().split('\\n')[0];

                    for (const sel of [
                            'div.text-body-medium.break-words',
                            '.pv-text-details__left-panel .text-body-medium',
                            'h1 + div', '[class*="top-card-layout"] [class*="headline"]']) {
                        const el = document.querySelector(sel);
                        if (el) {
                            const t = el.innerText.trim().split('\\n')[0];
                            if (t && t !== r.name && t.length < 200) { r.headline = t; break; }
                        }
                    }
                    for (const sel of [
                            'span.text-body-small.inline.t-black--light.break-words',
                            '.pv-text-details__left-panel .text-body-small.inline',
                            '[class*="top-card-layout"] [class*="location"]']) {
                        const el = document.querySelector(sel);
                        if (el) {
                            const t = el.innerText.trim().split('\\n')[0];
                            if (t && t.length < 100) { r.location = t; break; }
                        }
                    }
                    return r;
                })()
            """);

            if (data != null) {
                if (!data.getOrDefault("name","").isBlank())
                    profile.setFullName(cleanName(data.get("name")));
                String headline = data.getOrDefault("headline","");
                if (!headline.isBlank()) {
                    if (headline.contains(" at ")) {
                        String[] parts = headline.split(" at ", 2);
                        profile.setCurrentTitle(parts[0].trim());
                        profile.setCurrentCompany(parts[1].trim());
                    } else {
                        profile.setCurrentTitle(headline);
                    }
                }
                String loc = data.getOrDefault("location","");
                if (!loc.isBlank()) profile.setLocation(loc);
            }

            extractExperience(page, profile);
            extractSkills(page, profile);

        } catch (Exception e) {
            logger.warn("LinkedIn: profile visit failed ({}) — {}", url, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void extractExperience(Page page, CandidateProfile profile) {
        try {
            Map<String, String> exp = (Map<String, String>) page.evaluate("""
                (() => {
                    const r = { company:'', years:'' };

                    // Locate the Experience section
                    const sec = document.querySelector('#experience')
                             || Array.from(document.querySelectorAll('section'))
                                     .find(s => s.innerText.startsWith('Experience'));
                    if (!sec) return r;
                    const section = sec.closest('section') ?? sec;
                    const first   = section.querySelector('li');
                    if (!first) return r;

                    const spans = Array.from(first.querySelectorAll('span[aria-hidden="true"]'))
                                       .map(s => s.innerText.trim()).filter(Boolean);

                    // Content-based helpers — avoids relying on positional index
                    // LinkedIn has two layouts:
                    //   "single-role" : spans = [Title, Company, DateRange·Duration, Location]
                    //   "company-view": spans = [Company, TotalDuration, ...roles]
                    //                   (company-view has a nested <ul> for individual roles)
                    const isDuration  = t => /\\d+\\s*(yr|mos|month)/i.test(t);
                    const isDateRange = t => /\\d{4}\\s*[\\u2013\\-]/.test(t);
                    const stripDot    = t => t.split('\\u00b7')[0].split('·')[0].trim();

                    const hasNestedRoles = !!first.querySelector('ul');

                    if (hasNestedRoles) {
                        // Company-view: first non-duration, non-date span → company name
                        //               first duration span → total experience at company
                        for (const s of spans) {
                            if (!r.company && !isDuration(s) && !isDateRange(s) && s.length < 100) {
                                r.company = stripDot(s);
                            }
                            if (!r.years && isDuration(s)) {
                                const m = s.match(/(\\d+)\\s*yr/i);
                                if (m) r.years = m[1] + '+ yrs';
                            }
                            if (r.company && r.years) break;
                        }
                    } else {
                        // Single-role view: skip first span (job title), then:
                        //   next non-duration, non-date span → company name
                        //   first duration or date-range span → years
                        let titleSkipped = false;
                        for (const s of spans) {
                            if (!titleSkipped) { titleSkipped = true; continue; }
                            if (!r.company && !isDuration(s) && !isDateRange(s) && s.length < 100) {
                                r.company = stripDot(s);
                            }
                            if (!r.years && isDuration(s)) {
                                const m = s.match(/(\\d+)\\s*yr/i);
                                if (m) r.years = m[1] + '+ yrs';
                            }
                        }
                        // Fallback: compute years from date-range text (e.g. "2019 – Present")
                        if (!r.years) {
                            const dm = first.innerText.match(
                                /(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)?\\s*(\\d{4})\\s*[\\u2013\\-]\\s*(Present|\\d{4})/i);
                            if (dm) {
                                const start = parseInt(dm[2]);
                                const end   = dm[3].toLowerCase() === 'present'
                                            ? new Date().getFullYear() : parseInt(dm[3]);
                                const yrs   = end - start;
                                if (yrs >= 0 && yrs < 60) r.years = yrs + '+ yrs';
                            }
                        }
                    }
                    return r;
                })()
            """);
            if (exp != null) {
                if (profile.getCurrentCompany().isBlank() && !exp.getOrDefault("company","").isBlank())
                    profile.setCurrentCompany(exp.get("company"));
                if (profile.getYearsOfExperience().isBlank() && !exp.getOrDefault("years","").isBlank())
                    profile.setYearsOfExperience(exp.get("years"));
            }
        } catch (Exception e) { logger.debug("LinkedIn: experience extraction failed — {}", e.getMessage()); }
    }

    @SuppressWarnings("unchecked")
    private void extractSkills(Page page, CandidateProfile profile) {
        try {
            List<String> skills = (List<String>) page.evaluate("""
                (() => {
                    const res = []; const seen = new Set();
                    const sec = document.querySelector('#skills')
                             || Array.from(document.querySelectorAll('section'))
                                     .find(s => s.querySelector('h2')?.innerText?.includes('Skills'));
                    if (!sec) return res;
                    const section = sec.closest('section') ?? sec;
                    for (const el of section.querySelectorAll('li span[aria-hidden="true"]')) {
                        const t = el.innerText.trim();
                        if (t && t.length < 60 && !seen.has(t)) {
                            seen.add(t); res.push(t);
                            if (res.length >= 5) break;
                        }
                    }
                    return res;
                })()
            """);
            if (skills != null && !skills.isEmpty() && profile.getKeySkills().isBlank())
                profile.setKeySkills(String.join(", ", skills));
        } catch (Exception e) { logger.debug("LinkedIn: skills extraction failed — {}", e.getMessage()); }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private List<CandidateProfile> deduplicateByUrl(List<CandidateProfile> profiles) {
        Set<String> seen = new LinkedHashSet<>();
        List<CandidateProfile> result = new ArrayList<>();
        for (CandidateProfile p : profiles) {
            String key = p.getProfileUrl().toLowerCase().replaceAll("/$","");
            if (key.isBlank() || seen.add(key)) result.add(p);
        }
        return result;
    }

    private String cleanName(String raw) {
        if (raw == null) return "";
        return raw.split("\n")[0]
                  .replaceAll("\\s*[•·]\\s*(1st|2nd|3rd\\+?|Following|You).*$","")
                  .replaceAll("\\s{2,}"," ")
                  .trim();
    }

    /** Remove degree badges that may appear in title text extracted from search cards. */
    private String cleanDegree(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("\\s*[•·]\\s*(1st|2nd|3rd\\+?)\\s*$","").trim();
    }

    private void logPageDiagnostic(Page page) {
        try {
            long inLinks = page.querySelectorAll("a[href*='/in/']").size();
            long liCount = page.querySelectorAll("li").size();
            logger.warn("LinkedIn DIAGNOSTIC: URL=[{}], title=[{}], /in/ links={}, <li>s={}",
                    page.url(), page.title(), inLinks, liCount);
            String body = page.innerText("body");
            if (body.length() > 500) body = body.substring(0, 500) + "...";
            logger.warn("LinkedIn DIAGNOSTIC body snippet: {}", body.replaceAll("\\s+", " "));
        } catch (Exception e) {
            logger.warn("LinkedIn: diagnostic logging failed — {}", e.getMessage());
        }
        // Save a viewport screenshot so the developer can see exactly what LinkedIn rendered
        try {
            Path screenshotDir = Paths.get(System.getProperty("user.home"), ".profile-scraper");
            Files.createDirectories(screenshotDir);
            Path shot = screenshotDir.resolve("debug-screenshot-" + System.currentTimeMillis() + ".png");
            page.screenshot(new Page.ScreenshotOptions().setPath(shot).setFullPage(false));
            logger.warn("LinkedIn DIAGNOSTIC: screenshot saved → {}", shot);
        } catch (Exception ignored) {}
    }

    private ElementHandle findVisible(Page page, String[] selectors, int totalMs) {
        int perSel = Math.max(2_000, totalMs / selectors.length);
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
}

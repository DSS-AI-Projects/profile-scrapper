package com.profilescraper.scraper;

import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Base class that provides shared Playwright helpers for all portal scrapers.
 */
public abstract class AbstractPortalScraper implements PortalScraper {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    // ─── Playwright factory helpers ───────────────────────────────────────────────

    /** Launch a headless Chromium browser with common anti-detection flags. */
    protected Browser createBrowser(Playwright playwright) {
        return playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(List.of(
                        "--no-sandbox",
                        "--disable-setuid-sandbox",
                        "--disable-dev-shm-usage",
                        "--disable-blink-features=AutomationControlled"
                )));
    }

    /** Create a BrowserContext that mimics a real Chrome installation. */
    protected BrowserContext createContext(Browser browser) {
        return browser.newContext(new Browser.NewContextOptions()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .setViewportSize(1280, 800)
                .setLocale("en-US"));
    }

    // ─── DOM helpers ──────────────────────────────────────────────────────────────

    /**
     * Safely read the inner text of the first matching child element.
     * Returns {@code ""} if the selector matches nothing or throws.
     */
    protected String safeText(ElementHandle parent, String selector) {
        try {
            ElementHandle el = parent.querySelector(selector);
            return el != null ? el.innerText().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Safely read an attribute of the first matching child element.
     * Returns {@code ""} if not found.
     */
    protected String safeAttr(ElementHandle parent, String selector, String attr) {
        try {
            ElementHandle el = parent.querySelector(selector);
            if (el == null) return "";
            String val = el.getAttribute(attr);
            return val != null ? val.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Safely read text from a page-level selector (for status/error messages).
     * Returns {@code ""} if not found.
     */
    protected String safePageText(Page page, String selector) {
        try {
            ElementHandle el = page.querySelector(selector);
            return el != null ? el.innerText().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ─── Keyword extraction ───────────────────────────────────────────────────────

    /**
     * Strip punctuation from the job description and return the first 120 characters
     * as a clean keyword string suitable for portal search boxes.
     */
    protected String extractKeywords(String jobDescription) {
        String cleaned = jobDescription
                .replaceAll("[^a-zA-Z0-9+& ]", " ")
                .trim()
                .replaceAll("\\s+", " ");
        return cleaned.substring(0, Math.min(120, cleaned.length())).trim();
    }
}

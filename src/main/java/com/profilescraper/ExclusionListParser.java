package com.profilescraper;

import com.profilescraper.model.CandidateProfile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Parses a previously exported candidates Excel file (.xlsx) and builds a set
 * of unique keys that can be used to exclude those candidates from new search results.
 *
 * <h3>Excel layout expected (matches {@link ExcelExporter})</h3>
 * <pre>
 *   Row 0 — Title row        (merged)
 *   Row 1 — Subtitle row     (count summary, merged)
 *   Row 2 — Blank row
 *   Row 3 — Header row       (Full Name, Current Title, …)
 *   Row 4+ — Data rows
 *
 *   Col 0 — Full Name
 *   Col 2 — Current Company
 *   Col 8 — Profile URL      (may be a hyperlink or plain text)
 * </pre>
 *
 * <h3>Matching strategy (applied in order)</h3>
 * <ol>
 *   <li><b>Profile URL</b> — normalised (lowercase, trailing slash and query params removed).
 *       Most reliable uniqueness key.</li>
 *   <li><b>Full Name + Company</b> — normalised lowercase composite key used as a fallback
 *       when no URL is present in the row.</li>
 * </ol>
 */
public class ExclusionListParser {

    private static final Logger logger = LoggerFactory.getLogger(ExclusionListParser.class);

    /** Excel data starts at this row index (0-based). */
    private static final int DATA_START_ROW = 4;
    private static final int COL_NAME       = 0;
    private static final int COL_COMPANY    = 2;
    private static final int COL_URL        = 8;

    // Utility class — no instances
    private ExclusionListParser() {}

    /**
     * Parses the uploaded Excel stream and returns a set of normalised
     * exclusion keys (profile URLs and name+company composites).
     *
     * @param inputStream the .xlsx file content
     * @return set of exclusion keys; never {@code null}, may be empty
     */
    public static Set<String> parse(InputStream inputStream) throws IOException {
        Set<String> keys = new HashSet<>();

        try (XSSFWorkbook workbook = new XSSFWorkbook(inputStream)) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            int lastRow = sheet.getLastRowNum();
            logger.info("ExclusionListParser: reading sheet '{}', last row = {}",
                    sheet.getSheetName(), lastRow);

            int parsed = 0;
            for (int r = DATA_START_ROW; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                String name    = cellText(row, COL_NAME);
                String company = cellText(row, COL_COMPANY);
                String url     = cellUrl(row, COL_URL);

                if (name.isBlank() && url.isBlank()) continue; // completely empty row

                // Primary key: profile URL
                if (!url.isBlank()) {
                    keys.add(normalizeUrl(url));
                }

                // Fallback key: name + company
                if (!name.isBlank()) {
                    keys.add(normalizeNameKey(name, company));
                }
                parsed++;
            }
            logger.info("ExclusionListParser: {} candidates loaded ({} unique keys)",
                    parsed, keys.size());
        }
        return keys;
    }

    /**
     * Returns {@code true} when the candidate matches any key in the exclusion set.
     *
     * @param profile     candidate to test
     * @param excludedKeys set produced by {@link #parse}
     */
    public static boolean isExcluded(CandidateProfile profile, Set<String> excludedKeys) {
        if (excludedKeys == null || excludedKeys.isEmpty()) return false;

        // Test profile URL first
        String url = profile.getProfileUrl();
        if (url != null && !url.isBlank()) {
            if (excludedKeys.contains(normalizeUrl(url))) return true;
        }

        // Test name + company fallback
        String name = profile.getFullName();
        if (name != null && !name.isBlank()) {
            String company = profile.getCurrentCompany();
            if (excludedKeys.contains(normalizeNameKey(name, company))) return true;
        }

        return false;
    }

    // ─── Normalisers ──────────────────────────────────────────────────────────────

    static String normalizeUrl(String url) {
        return url.trim()
                  .toLowerCase()
                  .replaceAll("\\?.*$", "")   // strip query params
                  .replaceAll("/$", "");       // strip trailing slash
    }

    static String normalizeNameKey(String name, String company) {
        String n = (name    != null ? name    : "").trim().toLowerCase().replaceAll("\\s+", " ");
        String c = (company != null ? company : "").trim().toLowerCase().replaceAll("\\s+", " ");
        return n + "|" + c;
    }

    // ─── POI helpers ─────────────────────────────────────────────────────────────

    /** Read the plain text of a cell (works for STRING and NUMERIC types). */
    private static String cellText(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default      -> "";
        };
    }

    /**
     * Read the URL from a cell — checks the hyperlink address first (set by
     * {@link ExcelExporter#setHyperlinkCell}), then falls back to the cell text.
     */
    private static String cellUrl(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return "";

        // Prefer the actual hyperlink address (most reliable)
        Hyperlink link = cell.getHyperlink();
        if (link != null) {
            String addr = link.getAddress();
            if (addr != null && !addr.isBlank()) return addr.trim();
        }

        // Fall back to cell display value
        if (cell.getCellType() == CellType.STRING) {
            return cell.getStringCellValue().trim();
        }
        return "";
    }
}

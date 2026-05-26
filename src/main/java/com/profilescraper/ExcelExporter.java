package com.profilescraper;

import com.profilescraper.model.CandidateProfile;
import com.profilescraper.model.CandidateProfile.MatchScore;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Exports a list of CandidateProfile objects to a formatted Excel (.xlsx) file.
 *
 * Column layout:
 *   A  Full Name          B  Current Title       C  Current Company
 *   D  Location           E  Years of Experience  F  Key Skills
 *   G  Status             H  Last Updated        I  Profile URL (hyperlink)
 *   J  Email              K  Phone               L  Summary / Headline
 *   M  Match Score
 */
public class ExcelExporter {

    private static final Logger logger = LoggerFactory.getLogger(ExcelExporter.class);

    private static final String[] HEADERS = {
        "Full Name", "Current Title", "Current Company", "Location",
        "Years of Experience", "Key Skills", "Status", "Last Updated",
        "Profile URL", "Email", "Phone", "Summary / Headline", "Match Score"
    };

    private static final int[] COLUMN_WIDTHS = {
        5000,   // Full Name
        6000,   // Current Title
        6000,   // Current Company
        4000,   // Location
        4500,   // Years of Experience
        12000,  // Key Skills
        4500,   // Status
        4000,   // Last Updated
        10000,  // Profile URL (hyperlink)
        7000,   // Email
        4000,   // Phone
        18000,  // Summary
        3500    // Match Score
    };

    public void exportToExcel(List<CandidateProfile> profiles, String filePath) throws IOException {
        logger.info("Exporting {} profiles to {}", profiles.size(), filePath);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Candidates");

            // ── All styles created once — reused across every data row ───────────
            CellStyle titleStyle    = buildTitleStyle(workbook);
            CellStyle headerStyle   = buildHeaderStyle(workbook);
            CellStyle highStyle     = buildScoreStyle(workbook, new byte[]{(byte)198, (byte)239, (byte)206});
            CellStyle mediumStyle   = buildScoreStyle(workbook, new byte[]{(byte)255, (byte)235, (byte)156});
            CellStyle lowStyle      = buildScoreStyle(workbook, new byte[]{(byte)255, (byte)199, (byte)206});
            CellStyle defaultStyle  = buildDefaultDataStyle(workbook);
            CellStyle urlStyle      = buildUrlStyle(workbook);
            CellStyle wrapStyle     = buildWrapStyle(workbook);
            CellStyle subtitleStyle = buildSubtitleStyle(workbook);
            CellStyle openStyle     = buildOpenToWorkStyle(workbook);

            int rowIdx = 0;

            // ── Title row ────────────────────────────────────────────────────────
            Row titleRow = sheet.createRow(rowIdx++);
            titleRow.setHeightInPoints(28);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("Profile Scraper Agent — Candidate Results");
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, HEADERS.length - 1));

            // ── Subtitle row (count summary) ─────────────────────────────────────
            Row subtitleRow = sheet.createRow(rowIdx++);
            subtitleRow.setHeightInPoints(18);
            Cell subtitleCell = subtitleRow.createCell(0);
            subtitleCell.setCellValue("Total: " + profiles.size()
                    + "   |   High: "   + countScore(profiles, MatchScore.HIGH)
                    + "   |   Medium: " + countScore(profiles, MatchScore.MEDIUM)
                    + "   |   Low: "    + countScore(profiles, MatchScore.LOW));
            subtitleCell.setCellStyle(subtitleStyle);
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, HEADERS.length - 1));

            rowIdx++; // blank row

            // ── Header row ───────────────────────────────────────────────────────
            Row headerRow = sheet.createRow(rowIdx++);
            headerRow.setHeightInPoints(20);
            for (int col = 0; col < HEADERS.length; col++) {
                Cell cell = headerRow.createCell(col);
                cell.setCellValue(HEADERS[col]);
                cell.setCellStyle(headerStyle);
            }

            // ── Data rows ────────────────────────────────────────────────────────
            for (CandidateProfile profile : profiles) {
                Row row = sheet.createRow(rowIdx++);
                row.setHeightInPoints(40);

                MatchScore score = profile.getMatchScore();
                CellStyle scoreStyle = switch (score) {
                    case HIGH    -> highStyle;
                    case MEDIUM  -> mediumStyle;
                    case LOW     -> lowStyle;
                    default      -> defaultStyle;
                };

                String status = profile.getStatus();

                setCell(row, 0,  profile.getFullName(),          defaultStyle);
                setCell(row, 1,  profile.getCurrentTitle(),      defaultStyle);
                setCell(row, 2,  profile.getCurrentCompany(),    defaultStyle);
                setCell(row, 3,  profile.getLocation(),          defaultStyle);
                setCell(row, 4,  profile.getYearsOfExperience(), defaultStyle);
                setCell(row, 5,  profile.getKeySkills(),         wrapStyle);
                setCell(row, 6,  status, "Open to Work".equalsIgnoreCase(status)
                                         ? openStyle : defaultStyle);
                setCell(row, 7,  profile.getLastUpdated(),       defaultStyle);
                setHyperlinkCell(workbook, row, 8, profile.getProfileUrl(), urlStyle);
                setCell(row, 9,  profile.getEmail(),             defaultStyle);
                setCell(row, 10, profile.getPhone(),             defaultStyle);
                setCell(row, 11, profile.getSummary(),           wrapStyle);
                setCell(row, 12, score.display(),                scoreStyle);
            }

            // ── Column widths & freeze ───────────────────────────────────────────
            for (int col = 0; col < COLUMN_WIDTHS.length; col++) {
                sheet.setColumnWidth(col, COLUMN_WIDTHS[col]);
            }
            sheet.createFreezePane(0, 4);

            // ── Auto-filter on header row ────────────────────────────────────────
            sheet.setAutoFilter(new CellRangeAddress(3, rowIdx - 1, 0, HEADERS.length - 1));

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                workbook.write(fos);
            }

            logger.info("Excel file saved: {}", filePath);
        }
    }

    // ─── Style builders ─────────────────────────────────────────────────────────

    private CellStyle buildTitleStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 16);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private CellStyle buildSubtitleStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setItalic(true);
        font.setFontHeightInPoints((short) 11);
        style.setFont(font);
        return style;
    }

    private CellStyle buildHeaderStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        applyBorders(style);
        return style;
    }

    private CellStyle buildScoreStyle(XSSFWorkbook wb, byte[] rgb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(new XSSFColor(rgb, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        applyBorders(style);
        return style;
    }

    private CellStyle buildDefaultDataStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        applyBorders(style);
        return style;
    }

    private CellStyle buildUrlStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setColor(IndexedColors.BLUE.getIndex());
        font.setUnderline(FontUnderline.SINGLE);
        style.setFont(font);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        applyBorders(style);
        return style;
    }

    private CellStyle buildWrapStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setVerticalAlignment(VerticalAlignment.TOP);
        style.setWrapText(true);
        applyBorders(style);
        return style;
    }

    private void applyBorders(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setTopBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setLeftBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
    }

    /** Green-badge style for "Open to Work" status cells. */
    private CellStyle buildOpenToWorkStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setBold(true);
        font.setColor(new XSSFColor(new byte[]{(byte) 39, (byte) 98, (byte) 33}, null));
        style.setFont(font);
        style.setFillForegroundColor(
                new XSSFColor(new byte[]{(byte) 198, (byte) 239, (byte) 206}, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        applyBorders(style);
        return style;
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private void setCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        if (style != null) cell.setCellStyle(style);
    }

    /**
     * Writes a URL string into a cell and attaches a real Excel hyperlink so
     * that clicking the cell opens the URL in the default browser.
     */
    private void setHyperlinkCell(XSSFWorkbook wb, Row row, int col,
                                   String url, CellStyle style) {
        Cell cell = row.createCell(col);
        if (url == null || url.isBlank()) {
            cell.setCellValue("");
            if (style != null) cell.setCellStyle(style);
            return;
        }
        cell.setCellValue(url);
        if (style != null) cell.setCellStyle(style);
        try {
            Hyperlink hyperlink = wb.getCreationHelper()
                    .createHyperlink(HyperlinkType.URL);
            hyperlink.setAddress(url);
            cell.setHyperlink(hyperlink);
        } catch (Exception e) {
            logger.warn("Could not attach Excel hyperlink for '{}': {}", url, e.getMessage());
        }
    }

    private long countScore(List<CandidateProfile> profiles, MatchScore score) {
        return profiles.stream().filter(p -> p.getMatchScore() == score).count();
    }
}
package com.profilescraper.view;

import com.profilescraper.ExcelExporter;
import com.profilescraper.model.CandidateProfile;
import com.profilescraper.model.CandidateProfile.MatchScore;
import com.profilescraper.scraper.JobPortal;
import com.profilescraper.service.ScraperService;

// ── Vaadin Flow components ────────────────────────────────────────────────────
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

// ── JMIX Flow UI ─────────────────────────────────────────────────────────────
import io.jmix.flowui.Notifications;
import io.jmix.flowui.download.DownloadFormat;
import io.jmix.flowui.download.Downloader;
import io.jmix.flowui.view.*;

// ── Standard Java ─────────────────────────────────────────────────────────────
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@ViewController("MainView")
@ViewDescriptor("main_view.xml")
@Route(value = "")
@AnonymousAllowed
public class MainView extends StandardView {

    @Override
    public String getPageTitle() {
        return "Profile Scraper Agent";
    }

    private static final Logger logger = LoggerFactory.getLogger(MainView.class);

    /**
     * Error notifications default to staying put until dismissed, and the overlay swallows
     * clicks on the controls beneath it — including the Search button, so a failed search could
     * not be retried. Give them a close button and an expiry.
     */
    private static final int ERROR_NOTIFICATION_DURATION_MS = 12_000;

    // ── Spring services ───────────────────────────────────────────────────────
    @Autowired private ScraperService scraperService;
    @Autowired private Notifications  notifications;
    @Autowired private Downloader     downloader;
    @Autowired @Qualifier("scraperExecutor") private Executor scraperExecutor;

    // ── View components injected from main_view.xml ──────────────────────────
    @ViewComponent private TextArea        jobDescriptionField;
    @ViewComponent private TextField       locationField;
    @ViewComponent private VerticalLayout  portalSelectionSection;   // mount point
    @ViewComponent private Button          searchBtn;
    @ViewComponent private Button          cancelBtn;
    @ViewComponent private Button          clearBtn;
    @ViewComponent private Button          exportBtn;
    @ViewComponent private ProgressBar     searchProgress;
    @ViewComponent private Span            statusLabel;
    @ViewComponent private Span            exportHint;
    @ViewComponent private Span            summaryBadge;
    @ViewComponent private H3              resultsPanelTitle;
    @ViewComponent private VerticalLayout  gridContainer;

    // ── Components added programmatically ────────────────────────────────────
    private ComboBox<JobPortal>  portalComboBox;
    private VerticalLayout       credentialsSection;
    private TextField            usernameField;
    private PasswordField        passwordField;

    // ── Runtime state ─────────────────────────────────────────────────────────
    private Grid<CandidateProfile>                 candidatesGrid;
    private List<CandidateProfile>                 currentProfiles = new ArrayList<>();
    private CompletableFuture<List<CandidateProfile>> currentSearch;

    // ─────────────────────────────────────────────────────────────────────────
    //  View lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Subscribe
    public void onInit(InitEvent event) {
        initPortalSection();
        initGrid();
        initButtons();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Initialisation helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void initPortalSection() {

        // ── Portal ComboBox ───────────────────────────────────────────────────
        portalComboBox = new ComboBox<>("Job Portal (optional)");
        portalComboBox.setItems(JobPortal.values());
        portalComboBox.setItemLabelGenerator(JobPortal::getDisplayName);
        portalComboBox.setPlaceholder(
                "Leave blank to use AI Search (no login required)");
        portalComboBox.setWidth("100%");
        portalComboBox.setClearButtonVisible(true);
        portalComboBox.setHelperText(
                "Select a portal to log in with your credentials and search directly on that site.");

        // ── Credentials section (hidden until a portal is chosen) ─────────────
        credentialsSection = new VerticalLayout();
        credentialsSection.setPadding(false);
        credentialsSection.setSpacing(true);
        credentialsSection.setVisible(false);
        credentialsSection.getStyle().set("margin-top", "4px");

        usernameField = new TextField("Username / Email");
        usernameField.setWidth("100%");
        usernameField.setPlaceholder("Enter your " + "portal username or email address");
        usernameField.setClearButtonVisible(true);

        passwordField = new PasswordField("Password");
        passwordField.setWidth("100%");
        passwordField.setPlaceholder("Enter your portal password");

        credentialsSection.add(usernameField, passwordField);

        // ── Show/hide credentials when portal selection changes ───────────────
        portalComboBox.addValueChangeListener(e -> {
            JobPortal selected = e.getValue();
            boolean hasPortal = selected != null;
            credentialsSection.setVisible(hasPortal);

            if (hasPortal) {
                if (selected.isApiKeyOnly()) {
                    // SERP API — only an API key is needed, no password
                    usernameField.setLabel("SerpAPI Key");
                    usernameField.setPlaceholder("Enter your SerpAPI key (serpapi.com)");
                    usernameField.setHelperText("Free key: serpapi.com/manage-api-key · 100 searches/month on free plan");
                    passwordField.setVisible(false);
                    passwordField.clear();
                } else {
                    usernameField.setLabel(selected.getDisplayName() + " Username / Email");
                    usernameField.setPlaceholder("Enter your " + selected.getDisplayName() + " username or email address");
                    usernameField.setHelperText("");
                    passwordField.setVisible(true);
                    passwordField.setLabel(selected.getDisplayName() + " Password");
                }
            } else {
                usernameField.clear();
                usernameField.setHelperText("");
                passwordField.clear();
                passwordField.setVisible(true);
            }
        });

        portalSelectionSection.add(portalComboBox, credentialsSection);
    }

    private void initGrid() {
        candidatesGrid = new Grid<>(CandidateProfile.class, false);
        candidatesGrid.setWidth("100%");
        candidatesGrid.setHeight("480px");
        candidatesGrid.addThemeVariants(
                GridVariant.LUMO_ROW_STRIPES,
                GridVariant.LUMO_COLUMN_BORDERS,
                GridVariant.LUMO_COMPACT
        );

        candidatesGrid.addColumn(CandidateProfile::getFullName)
                .setHeader("Full Name").setWidth("170px").setResizable(true).setSortable(true);

        candidatesGrid.addColumn(CandidateProfile::getCurrentTitle)
                .setHeader("Current Title").setWidth("180px").setResizable(true).setSortable(true);

        candidatesGrid.addColumn(CandidateProfile::getCurrentCompany)
                .setHeader("Company").setWidth("160px").setResizable(true).setSortable(true);

        candidatesGrid.addColumn(CandidateProfile::getLocation)
                .setHeader("Location").setWidth("130px").setResizable(true).setSortable(true);

        candidatesGrid.addColumn(CandidateProfile::getYearsOfExperience)
                .setHeader("Exp (Yrs)").setWidth("90px").setResizable(true).setSortable(true);

        candidatesGrid.addColumn(CandidateProfile::getKeySkills)
                .setHeader("Key Skills").setWidth("220px").setResizable(true);

        // Status: "Open to Work" green badge, or other statuses
        candidatesGrid.addColumn(new ComponentRenderer<>(profile -> {
            String status = profile.getStatus();
            if (status == null || status.isBlank()) return new Span("—");
            Span badge = new Span(status);
            badge.getStyle()
                    .set("font-size", "11px")
                    .set("font-weight", "600")
                    .set("padding", "2px 8px")
                    .set("border-radius", "4px")
                    .set("white-space", "nowrap");
            if ("Open to Work".equalsIgnoreCase(status)) {
                badge.getStyle()
                        .set("background", "#C6EFCE")
                        .set("color", "#276221");
            } else if ("Hiring".equalsIgnoreCase(status)) {
                badge.getStyle()
                        .set("background", "#DBEAFE")
                        .set("color", "#1E3A8A");
            } else {
                badge.getStyle()
                        .set("background", "#F3F4F6")
                        .set("color", "#374151");
            }
            return badge;
        })).setHeader("Status").setWidth("130px").setResizable(true)
                .setComparator(Comparator.comparing(CandidateProfile::getStatus));

        candidatesGrid.addColumn(CandidateProfile::getLastUpdated)
                .setHeader("Last Updated").setWidth("115px").setResizable(true).setSortable(true);

        // Match score: colour-coded badge
        candidatesGrid.addColumn(new ComponentRenderer<>(profile -> {
            Span badge = new Span(profile.getMatchScore().display());
            badge.getStyle()
                    .set("font-weight", "600")
                    .set("padding", "2px 8px")
                    .set("border-radius", "4px");
            switch (profile.getMatchScore()) {
                case HIGH   -> badge.getStyle()
                        .set("background", "#C6EFCE").set("color", "#276221");
                case MEDIUM -> badge.getStyle()
                        .set("background", "#FFEB9C").set("color", "#7D6608");
                case LOW    -> badge.getStyle()
                        .set("background", "#FFC7CE").set("color", "#9C0006");
                default     -> badge.getStyle()
                        .set("background", "#E0E0E0").set("color", "#555");
            }
            return badge;
        })).setHeader("Match Score").setWidth("110px").setResizable(true)
                .setComparator((a, b) -> a.getMatchScore().compareTo(b.getMatchScore()));

        candidatesGrid.addColumn(CandidateProfile::getEmail)
                .setHeader("Email").setWidth("170px").setResizable(true);

        // Profile Link: clickable anchor — opens the LinkedIn profile in a new browser tab
        candidatesGrid.addColumn(new ComponentRenderer<>(profile -> {
            String url = profile.getProfileUrl();
            if (url == null || url.isBlank()) return new Span("—");
            Anchor link = new Anchor(url, "🔗 Open Profile");
            link.setTarget("_blank");
            link.getStyle()
                    .set("color", "#1D4ED8")
                    .set("font-weight", "600")
                    .set("font-size", "12px")
                    .set("text-decoration", "underline")
                    .set("white-space", "nowrap");
            return link;
        })).setHeader("Profile Link").setWidth("140px").setResizable(true);

        gridContainer.add(candidatesGrid);
    }

    private void initButtons() {
        searchBtn.addClickListener(e -> onSearch());
        cancelBtn.addClickListener(e -> onCancel());
        clearBtn.addClickListener(e -> onClear());
        exportBtn.addClickListener(e -> onExport());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Button handlers
    // ─────────────────────────────────────────────────────────────────────────

    private void onSearch() {
        String jobDescription = jobDescriptionField.getValue();

        if (jobDescription == null || jobDescription.isBlank()) {
            jobDescriptionField.setInvalid(true);
            jobDescriptionField.setErrorMessage(
                    "Please enter a job description before searching.");
            return;
        }
        jobDescriptionField.setInvalid(false);

        JobPortal portal   = portalComboBox.getValue();
        String    uname    = usernameField.getValue();
        String    pass     = passwordField.getValue();
        final String location = locationField.getValue() == null
                ? "" : locationField.getValue().trim();

        // Validate credentials when a portal is selected
        if (portal != null) {
            if (uname == null || uname.isBlank()) {
                usernameField.setInvalid(true);
                usernameField.setErrorMessage(portal.isApiKeyOnly()
                        ? "Please enter your SerpAPI key."
                        : "Please enter your " + portal.getDisplayName() + " username.");
                return;
            }
            if (!portal.isApiKeyOnly() && (pass == null || pass.isBlank())) {
                passwordField.setInvalid(true);
                passwordField.setErrorMessage("Please enter your " + portal.getDisplayName() + " password.");
                return;
            }
            usernameField.setInvalid(false);
            passwordField.setInvalid(false);
        }

        setSearchInProgress(true);

        if (portal != null) {
            String portalMsg = portal == JobPortal.LINKEDIN
                    ? "Opening a browser window to log into LinkedIn… "
                      + "If a security challenge appears, complete it in the browser window. "
                      + "The session will be saved so you will not be asked again."
                    : portal == JobPortal.SERP_API
                    ? "Searching Google via SerpAPI for matching LinkedIn profiles… This is usually fast (5–10 seconds)."
                    : "Logging into " + portal.getDisplayName()
                      + " and searching for candidates… This may take a few minutes.";
            setStatus(portalMsg);
            logger.info("Starting portal search on {} for: {}", portal, jobDescription);

            final String finalUname = uname;
            final String finalPass  = pass;
            final JobPortal finalPortal = portal;

            currentSearch = CompletableFuture.supplyAsync(() -> {
                try {
                    return scraperService.scrapeProfiles(
                            jobDescription, location, finalPortal, finalUname, finalPass);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, scraperExecutor);

        } else {
            setStatus("Searching for candidates using AI… This may take a few minutes.");
            logger.info("Starting AI search for: {}", jobDescription);

            currentSearch = CompletableFuture.supplyAsync(() -> {
                try {
                    return scraperService.scrapeProfiles(jobDescription, location);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, scraperExecutor);
        }

        // ── Common completion handler ─────────────────────────────────────────
        currentSearch.thenAccept(profiles ->
                getUI().ifPresent(ui -> ui.access(() -> {
                    currentProfiles = profiles;
                    candidatesGrid.setItems(currentProfiles);
                    resultsPanelTitle.setText(
                            String.format("Results (%d candidates found)", profiles.size()));
                    updateSummaryBadge(profiles);
                    setSearchInProgress(false);
                    setStatus(String.format("Done — found %d candidate(s).", profiles.size()));

                    boolean hasResults = !profiles.isEmpty();
                    exportBtn.setEnabled(hasResults);
                    exportHint.setVisible(!hasResults);

                    if (!hasResults) {
                        notifications.create(
                                "No candidates matched your criteria. Try broadening the description.")
                                .withType(Notifications.Type.DEFAULT).show();
                    } else {
                        notifications.create(String.format(
                                "Found %d candidate(s)! Click 'Export to Excel' to download.",
                                profiles.size()))
                                .withType(Notifications.Type.SUCCESS).show();
                    }
                }))
        ).exceptionally(ex -> {
            if (ex.getCause() instanceof java.util.concurrent.CancellationException) return null;
            logger.error("Error during candidate scraping", ex);
            getUI().ifPresent(ui -> ui.access(() -> {
                setSearchInProgress(false);
                String msg = ex.getCause() != null
                        ? ex.getCause().getMessage() : ex.getMessage();
                notifications.create("Search failed: " + msg)
                        .withType(Notifications.Type.ERROR)
                        .withCloseable(true)
                        .withDuration(ERROR_NOTIFICATION_DURATION_MS)
                        .show();
                setStatus("Error: " + msg);
            }));
            return null;
        });
    }

    private void onCancel() {
        if (currentSearch != null && !currentSearch.isDone()) {
            currentSearch.cancel(true);
        }
        setSearchInProgress(false);
        setStatus("Search cancelled.");
        notifications.create("Search cancelled.")
                .withType(Notifications.Type.DEFAULT).show();
    }

    private void onClear() {
        jobDescriptionField.setValue("");
        jobDescriptionField.setInvalid(false);
        portalComboBox.clear();
        usernameField.clear();
        passwordField.clear();
        usernameField.setInvalid(false);
        passwordField.setInvalid(false);
        currentProfiles = new ArrayList<>();
        candidatesGrid.setItems(currentProfiles);
        resultsPanelTitle.setText("Results (0 candidates found)");
        summaryBadge.setVisible(false);
        exportBtn.setEnabled(false);
        exportHint.setVisible(true);
        setStatus("Ready to search. Enter a job description and click 'Search Candidates'.");
    }

    private void onExport() {
        if (currentProfiles == null || currentProfiles.isEmpty()) {
            notifications.create("No candidates to export. Run a search first.")
                    .withType(Notifications.Type.WARNING).show();
            return;
        }

        try {
            String fileName = buildOutputFileName();
            String filePath = Paths.get(System.getProperty("java.io.tmpdir"), fileName).toString();

            logger.info("Exporting {} candidates to {}", currentProfiles.size(), filePath);
            new ExcelExporter().exportToExcel(currentProfiles, filePath);

            byte[] fileBytes = Files.readAllBytes(Paths.get(filePath));
            downloader.download(
                    fileBytes,
                    fileName,
                    new DownloadFormat(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "xlsx")
            );

            notifications.create("Export complete — " + fileName)
                    .withType(Notifications.Type.SUCCESS).show();

        } catch (IOException ex) {
            logger.error("Excel export failed", ex);
            notifications.create("Export failed: " + ex.getMessage())
                    .withType(Notifications.Type.ERROR)
                    .withCloseable(true)
                    .withDuration(ERROR_NOTIFICATION_DURATION_MS)
                    .show();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UI state helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void setSearchInProgress(boolean inProgress) {
        searchBtn.setEnabled(!inProgress);
        clearBtn.setEnabled(!inProgress);
        cancelBtn.setVisible(inProgress);
        searchProgress.setVisible(inProgress);
        portalComboBox.setEnabled(!inProgress);
        usernameField.setEnabled(!inProgress);
        passwordField.setEnabled(!inProgress);
        if (!inProgress) exportBtn.setEnabled(!currentProfiles.isEmpty());
    }

    private void setStatus(String message) {
        statusLabel.setText(message);
    }

    private void updateSummaryBadge(List<CandidateProfile> profiles) {
        if (profiles.isEmpty()) {
            summaryBadge.setVisible(false);
            return;
        }
        long high   = profiles.stream()
                .filter(p -> p.getMatchScore() == MatchScore.HIGH).count();
        long medium = profiles.stream()
                .filter(p -> p.getMatchScore() == MatchScore.MEDIUM).count();
        long low    = profiles.stream()
                .filter(p -> p.getMatchScore() == MatchScore.LOW).count();
        summaryBadge.setText(String.format(
                "Total: %d  |  High: %d  |  Medium: %d  |  Low: %d",
                profiles.size(), high, medium, low));
        summaryBadge.setVisible(true);
    }

    private String buildOutputFileName() {
        String raw     = jobDescriptionField.getValue();
        String cleaned = raw
                .replaceAll("[^a-zA-Z0-9 ]", " ")
                .trim()
                .replaceAll("\\s+", " ");
        String slug = cleaned
                .substring(0, Math.min(50, cleaned.length()))  // use cleaned length, not raw length
                .trim()
                .toLowerCase()
                .replaceAll("\\s+", "-");
        String date = LocalDate.now().format(DateTimeFormatter.ISO_DATE);

        // Optionally include portal name in file name
        JobPortal portal = portalComboBox.getValue();
        String portalSuffix = portal != null
                ? "_" + portal.name().toLowerCase()
                : "_ai";

        return String.format("candidates_%s%s_%s.xlsx", slug, portalSuffix, date);
    }
}

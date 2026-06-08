package com.profilescraper.view;

import com.profilescraper.ExcelExporter;
import com.profilescraper.ExclusionListParser;
import com.profilescraper.model.CandidateProfile;
import com.profilescraper.model.CandidateProfile.MatchScore;
import com.profilescraper.model.SearchCriteria;
import com.profilescraper.scraper.JobPortal;
import com.profilescraper.service.ScraperService;

// ── Vaadin Flow components ────────────────────────────────────────────────────
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MultiFileMemoryBuffer;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.PasswordField;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

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

    // ── Spring services ───────────────────────────────────────────────────────
    @Autowired private ScraperService scraperService;
    @Autowired private Notifications  notifications;
    @Autowired private Downloader     downloader;
    @Autowired @Qualifier("scraperExecutor") private Executor scraperExecutor;

    // ── View components injected from main_view.xml ──────────────────────────
    @ViewComponent private VerticalLayout  criteriaSection;          // mount point for search fields
    @ViewComponent private VerticalLayout  exclusionSection;         // mount point for exclusion upload
    @ViewComponent private VerticalLayout  portalSelectionSection;   // mount point for portal/credentials
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

    // ── Criteria fields (added programmatically into criteriaSection) ────────
    private TextField    keywordsField;
    private TextField    skillsField;
    private IntegerField minExpField;
    private IntegerField maxExpField;
    private TextField    locationField;
    private NumberField  minSalaryField;
    private NumberField  maxSalaryField;

    // ── Portal / credentials (added programmatically) ─────────────────────
    private ComboBox<JobPortal>  portalComboBox;
    private VerticalLayout       credentialsSection;
    private TextField            usernameField;
    private PasswordField        passwordField;

    // ── Exclusion list state ──────────────────────────────────────────────────
    private Set<String> excludedKeys   = new HashSet<>();
    private Span        exclusionBadge;   // shows "X candidates excluded"
    private Button      clearExclusionBtn;

    // ── Runtime state ─────────────────────────────────────────────────────────
    private Grid<CandidateProfile>                 candidatesGrid;
    private List<CandidateProfile>                 currentProfiles = new ArrayList<>();
    private CompletableFuture<List<CandidateProfile>> currentSearch;

    // ─────────────────────────────────────────────────────────────────────────
    //  View lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Subscribe
    public void onInit(InitEvent event) {
        initCriteriaSection();
        initExclusionSection();
        initPortalSection();
        initGrid();
        initButtons();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Initialisation helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void initCriteriaSection() {

        // ── Row 1: Keywords ───────────────────────────────────────────────────
        keywordsField = new TextField("Keywords / Role");
        keywordsField.setWidth("100%");
        keywordsField.setPlaceholder("e.g., Senior Java Developer, React Frontend, Data Scientist");
        keywordsField.setClearButtonVisible(true);
        keywordsField.setHelperText("Job title or role — used as the primary search term");

        // ── Row 2: Skills ─────────────────────────────────────────────────────
        skillsField = new TextField("Skills");
        skillsField.setWidth("100%");
        skillsField.setPlaceholder("e.g., Spring Boot, Kafka, Docker, AWS, React");
        skillsField.setClearButtonVisible(true);
        skillsField.setHelperText("Comma-separated technical or domain skills");

        // ── Row 3: Experience (min + max) + Location ──────────────────────────
        minExpField = new IntegerField("Min Experience (Yrs)");
        minExpField.setMin(0);
        minExpField.setMax(50);
        minExpField.setStepButtonsVisible(true);
        minExpField.setPlaceholder("0");
        minExpField.setWidth("180px");

        maxExpField = new IntegerField("Max Experience (Yrs)");
        maxExpField.setMin(0);
        maxExpField.setMax(50);
        maxExpField.setStepButtonsVisible(true);
        maxExpField.setPlaceholder("Any");
        maxExpField.setWidth("180px");

        locationField = new TextField("Current Location");
        locationField.setPlaceholder("e.g., Bangalore, Mumbai, Delhi NCR");
        locationField.setClearButtonVisible(true);
        locationField.setWidth("100%");

        HorizontalLayout expRow = new HorizontalLayout(minExpField, maxExpField, locationField);
        expRow.setWidth("100%");
        expRow.setAlignItems(FlexComponent.Alignment.END);
        expRow.expand(locationField);

        // ── Row 4: Salary range ───────────────────────────────────────────────
        minSalaryField = new NumberField("Min Salary (Lacs INR)");
        minSalaryField.setMin(0);
        minSalaryField.setStep(0.5);
        minSalaryField.setStepButtonsVisible(true);
        minSalaryField.setPlaceholder("e.g., 10");
        minSalaryField.setWidth("220px");
        minSalaryField.setSuffixComponent(new Span("L"));

        maxSalaryField = new NumberField("Max Salary (Lacs INR)");
        maxSalaryField.setMin(0);
        maxSalaryField.setStep(0.5);
        maxSalaryField.setStepButtonsVisible(true);
        maxSalaryField.setPlaceholder("e.g., 30");
        maxSalaryField.setWidth("220px");
        maxSalaryField.setSuffixComponent(new Span("L"));

        HorizontalLayout salaryRow = new HorizontalLayout(minSalaryField, maxSalaryField);
        salaryRow.setWidth("100%");
        salaryRow.setAlignItems(FlexComponent.Alignment.END);

        criteriaSection.add(keywordsField, skillsField, expRow, salaryRow);
    }

    private void initExclusionSection() {

        // ── Divider ───────────────────────────────────────────────────────────
        Hr divider = new Hr();
        divider.getStyle().set("margin", "8px 0 4px 0").set("border-color", "#E5E7EB");

        // ── Label ─────────────────────────────────────────────────────────────
        Span sectionLabel = new Span("Exclude Previously Found Candidates (Optional)");
        sectionLabel.getStyle()
                .set("font-size", "13px")
                .set("font-weight", "600")
                .set("color", "#374151");

        Span helperText = new Span(
                "Upload a previous search export (.xlsx) — candidates already in that list "
                + "will be skipped so you only see fresh results.");
        helperText.getStyle()
                .set("font-size", "12px")
                .set("color", "#6B7280")
                .set("display", "block")
                .set("margin-bottom", "6px");

        // ── Upload component ──────────────────────────────────────────────────
        MultiFileMemoryBuffer buffer = new MultiFileMemoryBuffer();
        Upload upload = new Upload(buffer);
        upload.setAcceptedFileTypes(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ".xlsx");
        upload.setMaxFiles(1);
        upload.setMaxFileSize(20 * 1024 * 1024);   // 20 MB limit
        upload.setDropAllowed(true);
        upload.getStyle().set("max-width", "480px");

        upload.setUploadButton(new Button("Upload Exclusion List (.xlsx)"));
        upload.setDropLabel(new Span("or drag & drop here"));

        // ── Status badge ──────────────────────────────────────────────────────
        exclusionBadge = new Span();
        exclusionBadge.setVisible(false);
        exclusionBadge.getStyle()
                .set("font-size", "12px")
                .set("font-weight", "600")
                .set("padding", "3px 10px")
                .set("border-radius", "12px")
                .set("background", "#FEF3C7")
                .set("color", "#92400E");

        // ── Clear exclusion list button ────────────────────────────────────────
        clearExclusionBtn = new Button("Clear Exclusion List", e -> {
            excludedKeys.clear();
            upload.clearFileList();
            exclusionBadge.setVisible(false);
            clearExclusionBtn.setVisible(false);
            notifications.create("Exclusion list cleared.")
                    .withType(Notifications.Type.DEFAULT).show();
        });
        clearExclusionBtn.getThemeNames().add("tertiary error small");
        clearExclusionBtn.setVisible(false);

        HorizontalLayout statusRow = new HorizontalLayout(exclusionBadge, clearExclusionBtn);
        statusRow.setAlignItems(FlexComponent.Alignment.CENTER);
        statusRow.setSpacing(true);

        // ── Upload success handler ────────────────────────────────────────────
        upload.addSucceededListener(event -> {
            try {
                Set<String> keys = ExclusionListParser.parse(
                        buffer.getInputStream(event.getFileName()));
                excludedKeys = keys;
                int count = countExcludedCandidates(keys);
                exclusionBadge.setText("⛔  " + count + " candidate(s) will be excluded");
                exclusionBadge.setVisible(true);
                clearExclusionBtn.setVisible(true);
                logger.info("Exclusion list loaded: {} unique keys from '{}'",
                        keys.size(), event.getFileName());
                notifications.create(
                        "Exclusion list loaded — " + count + " candidate(s) will be skipped.")
                        .withType(Notifications.Type.SUCCESS).show();
            } catch (Exception ex) {
                logger.error("Failed to parse exclusion list", ex);
                notifications.create(
                        "Could not read the file: " + ex.getMessage()
                        + ". Make sure it is a valid exported .xlsx file.")
                        .withType(Notifications.Type.ERROR).show();
            }
        });

        upload.addFailedListener(event ->
            notifications.create("Upload failed: " + event.getReason().getMessage())
                    .withType(Notifications.Type.ERROR).show()
        );

        exclusionSection.add(divider, sectionLabel, helperText, upload, statusRow);
    }

    /** Approximate candidate count from key set size (each candidate adds up to 2 keys). */
    private int countExcludedCandidates(Set<String> keys) {
        // Each candidate contributes at most 2 keys (URL + name|company).
        // Divide by 2 and round up as a rough count.
        return (int) Math.ceil(keys.size() / 2.0);
    }

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

        // ── Build and validate SearchCriteria ─────────────────────────────────
        SearchCriteria criteria = buildCriteria();

        if (criteria.isEmpty()) {
            keywordsField.setInvalid(true);
            keywordsField.setErrorMessage("Please enter at least a keyword/role or skill.");
            return;
        }
        keywordsField.setInvalid(false);

        // Validate experience range
        Integer minExp = criteria.getMinExperience();
        Integer maxExp = criteria.getMaxExperience();
        if (minExp != null && maxExp != null && minExp > maxExp) {
            minExpField.setInvalid(true);
            minExpField.setErrorMessage("Min experience cannot exceed Max experience.");
            return;
        }
        minExpField.setInvalid(false);

        // Validate salary range
        Double minSal = criteria.getMinSalaryLacs();
        Double maxSal = criteria.getMaxSalaryLacs();
        if (minSal != null && maxSal != null && minSal > maxSal) {
            minSalaryField.setInvalid(true);
            minSalaryField.setErrorMessage("Min salary cannot exceed Max salary.");
            return;
        }
        minSalaryField.setInvalid(false);

        JobPortal portal = portalComboBox.getValue();
        String    uname  = usernameField.getValue();
        String    pass   = passwordField.getValue();

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

        String query = criteria.toQueryString();
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
            logger.info("Starting portal search on {} for: {}", portal, query);

            final String    finalUname  = uname;
            final String    finalPass   = pass;
            final JobPortal finalPortal = portal;

            currentSearch = CompletableFuture.supplyAsync(() -> {
                try {
                    return scraperService.scrapeProfiles(criteria, finalPortal, finalUname, finalPass);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, scraperExecutor);

        } else {
            setStatus("Searching for candidates using AI… This may take a few minutes.");
            logger.info("Starting AI search for: {}", query);

            currentSearch = CompletableFuture.supplyAsync(() -> {
                try {
                    return scraperService.scrapeProfiles(criteria);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, scraperExecutor);
        }

        // ── Common completion handler ─────────────────────────────────────────
        currentSearch.thenAccept(profiles ->
                getUI().ifPresent(ui -> ui.access(() -> {

                    // Apply exclusion list filter
                    int rawCount = profiles.size();
                    List<CandidateProfile> filtered = profiles.stream()
                            .filter(p -> !ExclusionListParser.isExcluded(p, excludedKeys))
                            .collect(Collectors.toList());
                    int excludedCount = rawCount - filtered.size();

                    currentProfiles = filtered;
                    candidatesGrid.setItems(currentProfiles);
                    resultsPanelTitle.setText(
                            String.format("Results (%d candidates found)", filtered.size()));
                    updateSummaryBadge(filtered);
                    setSearchInProgress(false);

                    String statusMsg = excludedCount > 0
                            ? String.format("Done — %d new candidate(s) found (%d excluded as previously seen).",
                                    filtered.size(), excludedCount)
                            : String.format("Done — found %d candidate(s).", filtered.size());
                    setStatus(statusMsg);

                    boolean hasResults = !filtered.isEmpty();
                    exportBtn.setEnabled(hasResults);
                    exportHint.setVisible(!hasResults);

                    if (!hasResults) {
                        String msg = excludedCount > 0
                                ? "All " + excludedCount + " result(s) were already in your exclusion list. "
                                  + "Try different criteria or clear the exclusion list."
                                : "No candidates matched your criteria. Try broadening the search.";
                        notifications.create(msg)
                                .withType(Notifications.Type.DEFAULT).show();
                    } else {
                        String msg = excludedCount > 0
                                ? String.format("Found %d new candidate(s) (%d excluded). Click 'Export to Excel' to download.",
                                        filtered.size(), excludedCount)
                                : String.format("Found %d candidate(s)! Click 'Export to Excel' to download.",
                                        filtered.size());
                        notifications.create(msg)
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
                        .withType(Notifications.Type.ERROR).show();
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
        // Criteria fields
        keywordsField.clear();    keywordsField.setInvalid(false);
        skillsField.clear();
        minExpField.clear();      minExpField.setInvalid(false);
        maxExpField.clear();
        locationField.clear();
        minSalaryField.clear();   minSalaryField.setInvalid(false);
        maxSalaryField.clear();
        // Exclusion list — intentionally kept across clears so the user
        // doesn't have to re-upload on every new search. Use the dedicated
        // "Clear Exclusion List" button to reset it.
        // Portal fields
        portalComboBox.clear();
        usernameField.clear();    usernameField.setInvalid(false);
        passwordField.clear();    passwordField.setInvalid(false);
        // Results
        currentProfiles = new ArrayList<>();
        candidatesGrid.setItems(currentProfiles);
        resultsPanelTitle.setText("Results (0 candidates found)");
        summaryBadge.setVisible(false);
        exportBtn.setEnabled(false);
        exportHint.setVisible(true);
        setStatus("Ready to search. Fill in the criteria above and click 'Search Candidates'.");
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
                    .withType(Notifications.Type.ERROR).show();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UI state helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void setSearchInProgress(boolean inProgress) {
        // Criteria fields
        keywordsField.setEnabled(!inProgress);
        skillsField.setEnabled(!inProgress);
        minExpField.setEnabled(!inProgress);
        maxExpField.setEnabled(!inProgress);
        locationField.setEnabled(!inProgress);
        minSalaryField.setEnabled(!inProgress);
        maxSalaryField.setEnabled(!inProgress);
        // Portal / action
        portalComboBox.setEnabled(!inProgress);
        usernameField.setEnabled(!inProgress);
        passwordField.setEnabled(!inProgress);
        searchBtn.setEnabled(!inProgress);
        clearBtn.setEnabled(!inProgress);
        cancelBtn.setVisible(inProgress);
        searchProgress.setVisible(inProgress);
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

    /** Collect all criteria fields into a {@link SearchCriteria} object. */
    private SearchCriteria buildCriteria() {
        SearchCriteria c = new SearchCriteria();
        c.setKeywords(keywordsField.getValue());
        c.setSkills(skillsField.getValue());
        c.setMinExperience(minExpField.getValue());
        c.setMaxExperience(maxExpField.getValue());
        c.setLocation(locationField.getValue());
        c.setMinSalaryLacs(minSalaryField.getValue());
        c.setMaxSalaryLacs(maxSalaryField.getValue());
        return c;
    }

    private String buildOutputFileName() {
        // Use keywords (role) as the filename slug; fall back to "search" if empty
        String raw = keywordsField.getValue();
        if (raw == null || raw.isBlank()) raw = "search";

        String cleaned = raw
                .replaceAll("[^a-zA-Z0-9 ]", " ")
                .trim()
                .replaceAll("\\s+", " ");
        String slug = cleaned
                .substring(0, Math.min(50, cleaned.length()))
                .trim()
                .toLowerCase()
                .replaceAll("\\s+", "-");
        String date = LocalDate.now().format(DateTimeFormatter.ISO_DATE);

        JobPortal portal = portalComboBox.getValue();
        String portalSuffix = portal != null
                ? "_" + portal.name().toLowerCase()
                : "_ai";

        return String.format("candidates_%s%s_%s.xlsx", slug, portalSuffix, date);
    }
}

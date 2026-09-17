# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

### Changed

- **KAN-27**: Limit SerpAPI-based LinkedIn profile fetch to 20 results (previously
  up to 30) to reduce payload size and improve load times. The same 20-result cap
  now also applies to the Gemini AI-search path. The cap is overridable via the
  optional `SERP_API_MAX_RESULTS` environment variable. Other portal sources
  (LinkedIn/Naukri/Indeed via Playwright) are unaffected.

### Added

- Optional **Location** field in the search panel. When set, it is passed to Gemini as a
  mandatory filter and then enforced locally by `LocationFilter`, so a location named in
  the criteria actually constrains the results instead of merely influencing a
  `matchScore` that nothing acted on. Enforcement is lenient by design: a candidate is
  dropped only when its location is present and clearly a different place, so profiles
  with no location — common on the SerpAPI path, which scrapes location best-effort from
  Google snippets — are kept rather than silently emptying the grid.

### Fixed

- Raise the Gemini request timeout from 180s to 300s. Grounded Google Search with
  a 20–30 profile prompt routinely ran past three minutes, surfacing in the UI as
  `Search failed: java.net.http.HttpTimeoutException: request timed out`. 300s
  matches the 2–5 minute search duration the README already documents.
- Lower the Gemini `temperature` from 1.0 to 0.4. Finding candidates against stated
  requirements is constraint-following extraction, not creative writing; at 1.0 the model
  drifted off criteria such as location.

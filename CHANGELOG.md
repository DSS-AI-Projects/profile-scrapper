# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

### Changed

- **KAN-27**: Limit SerpAPI-based LinkedIn profile fetch to 20 results (previously
  up to 30) to reduce payload size and improve load times. The same 20-result cap
  now also applies to the Gemini AI-search path. The cap is overridable via the
  optional `SERP_API_MAX_RESULTS` environment variable. Other portal sources
  (LinkedIn/Naukri/Indeed via Playwright) are unaffected.

### Fixed

- Raise the Gemini request timeout from 180s to 300s. Grounded Google Search with
  a 20–30 profile prompt routinely ran past three minutes, surfacing in the UI as
  `Search failed: java.net.http.HttpTimeoutException: request timed out`. 300s
  matches the 2–5 minute search duration the README already documents.

# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

### Changed

- **KAN-27**: Limit SerpAPI-based LinkedIn profile fetch to 10 results (previously
  up to 30) to reduce payload size and improve load times. The same 10-result cap
  now also applies to the Gemini AI-search path. The cap is overridable via the
  optional `SERP_API_MAX_RESULTS` environment variable. Other portal sources
  (LinkedIn/Naukri/Indeed via Playwright) are unaffected.

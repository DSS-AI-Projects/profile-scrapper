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

- Accept several cities in the Location field, separated by `;` — `Mumbai; Thane; Pune`
  keeps a candidate in any of them and asks Google for `("Mumbai" OR "Thane" OR "Pune")`.
  Commas keep their existing meaning of narrowing one place (`Mumbai, Maharashtra`), because
  one separator cannot mean both without `Mumbai, India` reading as two places.

### Fixed

- Send the Location field to Google as the parsed city rather than its raw text. `Mumbai,
  India only` was being quoted whole, so Google was asked for that literal phrase — matching
  almost nothing — while the filter matched on just `Mumbai`. The query and the filter now
  share one parse (`LocationFilter.cities`).
- Stop dropping whole searches on a slow SerpAPI page: the 30s read timeout was under its
  observed spread (1s to 11s for the same query shape, occasionally more) and a page-2 fetch
  overran it, failing the search with `SocketTimeoutException: Read timed out`. Now 60s.
- Make error notifications dismissable. They had no close button and no expiry, so a failed
  search left an overlay that swallowed clicks on the controls beneath it — including the
  Search button, so the search could not be retried.
- Reject text that is not a place when parsing a candidate's location. "Starts with a capital
  and contains a comma" also matches job-title lists and prose, so profiles were recorded at
  locations like `Filing, Answering phones` and `Author, Celebrity Biographer, Animation
  Historian`. A wrong location defeats location filtering while looking authoritative.
- Search portals for the requested location instead of only filtering on it afterwards. The
  Location field never reached the query, so a search for an IT recruiter in Mumbai asked
  Google for IT recruiters worldwide and discarded the remainder — and kept any whose city
  the snippet did not state.

- Update the Gemini model from `gemini-2.0-flash`, which Google has retired, to
  `gemini-3.6-flash` — the replacement its 404 response names. Searches were failing with
  `Gemini API returned 404: This model ... is no longer available`.
- Replace `java.net.http.HttpClient` with `HttpURLConnection` (`com.profilescraper.Http`)
  for the Gemini and SerpAPI calls. Constructing an `HttpClient` opens an NIO selector whose
  wakeup pipe the JDK builds from a Unix-domain-socket loopback connect; where a host's
  network stack rejects that, every search failed with `UncheckedIOException: Unable to
  establish loopback connection` before a request was sent, and no system property disables
  it. These are one-shot request/response calls that used nothing `HttpClient` offers over
  `HttpURLConnection`.
- Add an opt-in `server.tomcat.nio2=true` property that serves over Tomcat's NIO2 connector
  (IOCP on Windows) instead of the selector-based default, so the app can start on hosts
  affected by the same problem. Does nothing unless set.

- Raise the Gemini request timeout from 180s to 300s. Grounded Google Search with
  a 20–30 profile prompt routinely ran past three minutes, surfacing in the UI as
  `Search failed: java.net.http.HttpTimeoutException: request timed out`. 300s
  matches the 2–5 minute search duration the README already documents.
- Lower the Gemini `temperature` from 1.0 to 0.4. Finding candidates against stated
  requirements is constraint-following extraction, not creative writing; at 1.0 the model
  drifted off criteria such as location.

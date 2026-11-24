# API changes

## Proposed: locale-aware public content

Public case fields such as `title`, `story`, update text, and ledger labels need to be available in both English and French, but the current contract exposes only one string for each field.

The phase-one client sends `Accept-Language: en|fr` with every request. The real API should use that header to return localized content (falling back to English); this preserves the existing response shapes and does not require any component changes.

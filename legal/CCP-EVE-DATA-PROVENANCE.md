# Embedded CCP/EVE data provenance

Resource: `src/main/resources/sovereignty.json`

- Purpose: deterministic offline fallback and presentation fixture for Pack startup.
- Authorship: project-authored JSON structure and scenario text; it is not a verbatim ESI response or bulk data export.
- First introduced: 2026-08-25 in local commit `65df88ffeefb30b7261b30ad5bbd561e1ecd911d`.
- Real EVE universe facts: system IDs `30004759` and `30004712`; alliance IDs `1354830081` and `99003581`; alliance display names current when the fixture was updated.
- Real-name/ID update: 2026-08-27 in local commit `61f3556c2a5a591ef2210606b61bac0236a785ce`.
- Synthetic project fields: `corporationName` values and every `sovereigntyStatus` string.
- Limit: the fixture is not evidence of live or current sovereignty ownership.

The real identifiers and names are EVE/CCP data and remain under the current CCP Developer License Agreement. They are expressly excluded from any future license grant for the Pack's original source.

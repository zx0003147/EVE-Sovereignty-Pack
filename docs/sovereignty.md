# Sovereignty Pack behavior

This document is the implementation reference for the external first-party EVE Sovereignty Pack. Core documentation
defines the Feature Pack platform; this repository owns the behavior described below.

## PUBLIC_ESI acquisition

Production uses the internal `PUBLIC_ESI` source mode. It performs only anonymous public operations:

1. `GET /sovereignty/systems?datasource=tranquility`
2. `POST /universe/names?datasource=tranquility` for alliance and optional corporation name resolution

The implementation sends its Pack version in the User-Agent and uses the accepted ESI compatibility date. It does not
request OAuth scopes, open SSO, use a character identity/token, or call Character ESI.

Remote conversion is all-or-nothing. Malformed data, duplicate solar-system IDs, invalid ownership shapes, unresolved
names, unexpected name categories, or conflicting owner categories invalidate the remote result. Valid faction and
unclaimed entries are accepted from the ESI payload but do not become alliance ownership records in the canonical
snapshot.

## Canonical snapshot

Each retained record contains:

- solar-system ID
- positive `allianceId`
- resolved alliance name
- optional resolved corporation name
- Sovereignty status

`allianceId` is the stable ownership identity. Names are display values and may change; new snapshots do not group or
color owners by name when an alliance ID is available.

The repository validates records and ignores invalid or duplicate fixture/provider records where its boundary allows
partial input. PUBLIC_ESI acquisition is stricter: a bad remote record invalidates the complete remote snapshot before
it can replace the LKG.

## LKG v1 and v2

The Pack stores a versioned canonical Last Known Good snapshot through Pack-scoped `PackStorage` at the cache-relative
path `public-esi-lkg.json`. This is a validated domain snapshot, not a raw HTTP cache, database, or copy of Core data.
Writes use a complete temporary file followed by replacement; a failed write does not discard the previous LKG.

- LKG v2 is the current write format and includes positive `allianceId` values.
- Structurally and semantically valid v1 files remain readable for backward compatibility.
- LKG v1 has no alliance IDs, so the Pack logs the legacy identity fallback and derives deterministic name-based
  presentation identity only until a later successful startup refresh writes v2.
- Missing identity is never invented or represented as a real ESI alliance ID.
- Unknown cache versions, extra/missing fields, malformed JSON, invalid records, or a wrong source marker are unusable
  and are never presented as fallback data.

The cache belongs only to this Pack. It does not write Sovereignty state into `static.db` or `user.db`.

## Startup freshness and offline fallback

Snapshot selection happens synchronously once during Pack startup:

- A valid LKG whose successful file modification time is at most one hour old is fresh and avoids ESI entirely.
- The exact one-hour boundary is fresh. A future timestamp caused by a local clock adjustment is also treated as fresh.
- A stale valid LKG triggers exactly one remote refresh attempt.
- A fully valid remote snapshot becomes the session snapshot. When persistence succeeds it replaces the LKG.
- If persistence fails, the valid remote snapshot is still used in memory for that session and the old LKG is retained.
- If ESI is unavailable or invalid, a stale valid LKG remains the fallback and is not touched.
- A missing or unusable cache triggers one startup attempt. If no valid remote or cached snapshot exists, providers
  remain registered with empty data and the failure is logged.
- Production never silently substitutes the embedded test fixture for failed PUBLIC_ESI acquisition.

The one-hour threshold is Pack product policy, not a CCP freshness guarantee.

## Restart semantics

The selected snapshot is immutable for the Pack session. After the Overlay and System Info providers register, there
is no polling, retry loop, timer, scheduler, background worker, live snapshot replacement, or Overlay invalidation.
Disabling/re-enabling the Pack or restarting the application starts a new selection. Live refresh requires future
platform lifecycle/invalidation capabilities and is intentionally outside v1.

## Overlay and System Info providers

The Pack registers one low-priority `Sovereignty` Overlay layer and one `Sovereignty` System Info provider. Overlay
entries project alliance name, ownership status, stable owner identity, color metadata, and optional emblem metadata
through Feature API's display-neutral contracts. System Info exposes the owner and status for a selected claimed
system.

The Pack owns provider/layer identifiers and Sovereignty metadata. Core owns aggregation, visibility, territory
geometry construction, emblem image loading, legend rendering, drawing order, and failure isolation. There is no
Sovereignty production implementation or domain model inside Core.

## Alliance visual identity and territory metadata

For v2/PUBLIC_ESI data, alliance identity is keyed by `allianceId`, so rename events do not change grouping or emblem
identity. The Pack emits a deterministic, map-readable color hint. Explicit Unknown/Unclaimed ownership uses neutral
presentation; the backwards-compatible v1 path uses a deterministic legacy name key and does not pretend to know an
alliance ID.

Territory is presented as a low-priority background contribution. The Pack supplies ownership seeds and generic
presentation metadata; Core's generic renderer derives supported territory, shared borders, labels, legend entries,
and compositing. Unsupported space remains transparent rather than being synthesized as an owner. Routes, selected
systems, saved markers, hover state, nodes, and labels remain Core visuals above the territory layer.

This is a behavior contract, not a record of temporary geometry-tuning constants.

## Emblem metadata

When a claimed owner has an `allianceId`, the Pack emits a stable emblem key plus this image-service reference shape:

```text
https://images.evetech.net/alliances/<allianceId>/logo?size=256
```

Unknown/unclaimed owners and v1 records without an alliance ID do not emit remote emblem metadata. Core decides
whether a territory component can safely display an emblem, loads qualifying images asynchronously, clips and sizes
them through generic presentation, and treats image failure as non-fatal. Ownership color and territory geometry do
not depend on logo availability.

## Preferences integration

The Host exposes Sovereignty-specific preference controls only when the installed Overlay/provider advertises the
recognized Sovereignty capability. The accepted preference adjusts the zoom emphasis of generic emblem presentation;
it does not change ownership data, invalidate the selected snapshot, or rebuild territory geometry. Preference
storage and UI remain Host-owned, while the Pack supplies the metadata that makes the behavior meaningful.

## Storage and dependency boundary

The Pack uses only paths mediated by its Feature API `PackStorage` and never reaches into Core databases or services.
Its committed build declares `dev.evestaticmapplanner:feature-api:1.0.0` as `compileOnly` and test input. It has no
Gradle project dependency on Feature API, permanent composite include, sibling path, or Core source dependency.
Optional developer composite substitution remains command-line-only.

## Testing boundary

Tests use injected HTTP senders, fake clocks, temporary Pack storage, embedded fixtures, and deterministic local
snapshots. They cover PUBLIC_ESI validation, LKG v1/v2 compatibility, freshness boundaries, offline fallback,
repository/provider behavior, identity metadata, and canonical standalone JAR packaging without live Internet.

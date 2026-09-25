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

## LKG v1, v2, and v3

The Pack stores a versioned canonical Last Known Good snapshot through Pack-scoped `PackStorage` at the cache-relative
path `public-esi-lkg.json`. This is a validated domain snapshot, not a raw HTTP cache, database, or copy of Core data.
Writes use a complete temporary file followed by replacement; a failed write does not discard the previous LKG.

- LKG v3 is the current write format and includes positive `allianceId` values plus optional positive
  `corporationId` values.
- Structurally and semantically valid v2 files with Alliance IDs but no Corporation IDs remain readable for backward
  compatibility.
- Structurally and semantically valid v1 files remain readable for backward compatibility.
- LKG v1 has no alliance IDs, so the Pack logs the legacy identity fallback and derives deterministic name-based
  presentation identity only until a later successful background refresh writes v2.
- Missing identity is never invented or represented as a real ESI alliance ID.
- Unknown cache versions, extra/missing fields, malformed JSON, invalid records, or a wrong source marker are unusable
  and are never presented as fallback data.

The cache belongs only to this Pack. It does not write Sovereignty state into `static.db` or `user.db`.

## Startup freshness and offline fallback

Pack startup performs only local cache work and provider registration:

- A valid LKG whose successful file modification time is at most one hour old is fresh and avoids ESI entirely.
- The exact one-hour boundary is fresh. A future timestamp caused by a local clock adjustment is also treated as fresh.
- A stale valid LKG is published immediately and triggers exactly one background refresh attempt.
- A missing or unusable cache publishes an empty provider state and triggers one background recovery attempt.
- A fully valid remote snapshot atomically replaces the in-memory session snapshot. When persistence succeeds it also
  replaces the LKG.
- If persistence fails, the valid remote snapshot is still used in memory for that session and the old LKG is retained.
- If ESI is unavailable or invalid, a stale valid LKG remains the fallback and is not touched.
- If no valid remote or cached snapshot exists, providers remain registered with empty data and the failure is logged.
- Production never silently substitutes the embedded test fixture for failed PUBLIC_ESI acquisition.

The one-hour threshold is Pack product policy, not a CCP freshness guarantee.

## Refresh and lifecycle semantics

On a current Host, the typed Sovereignty provider accepts the Host's post-first-frame refresh request and the Pack owns
one bounded worker for the remote callback. On an older Host, Feature API 2's Dynamic Overlay capability supplies the
same lifecycle trigger. The Pack accepts at most one required refresh per activation; it has no polling loop or timer.
A successful callback validates the complete remote snapshot, writes a complete temporary cache file followed by
atomic/replace move, swaps the Pack repository state, and republishes typed Sovereignty. The legacy path invalidates
its Overlay and System Info snapshots as well.

Disable or application shutdown closes the Pack session. The session first closes its refresh commit gate and HTTP
client, then unregisters providers. An in-flight callback may finish transport cleanup, but cannot write cache, publish
state, or reactivate a closed provider. Re-enabling the Pack or restarting the application creates a new activation and
may make a new refresh attempt.

## Legacy Overlay and System Info compatibility

When the Host exposes Feature API 2.5's typed Sovereignty capability, the Pack does not register its old Overlay or
System Info providers. Planner map and desktop System Info consume the typed Core Sovereignty snapshot instead.

When the Host lacks typed Sovereignty, the reflective compatibility bridge leaves the Pack linkable and the Pack
registers one low-priority `Sovereignty` Overlay layer plus one `Sovereignty` System Info provider. This fallback keeps
older Planner releases usable; it is not a second business-data path in current Planner. Pack-owned legacy IDs and
presentation metadata exist only inside that compatibility path.

## Typed Sovereignty provider

On a Host that exposes Feature API 2.5's optional Sovereignty capability, the Pack publishes the current immutable
in-memory system ownership observation. `snapshot()` performs no HTTP or cache I/O. Stable alliance and corporation
IDs are carried separately from optional names; legacy name-only records remain `UNKNOWN` rather than inventing an
ID. Successful refresh publishes `AVAILABLE`; refresh failure retains last-good records as `STALE`; missing data is
`UNAVAILABLE`. `requestRefresh()` only schedules Pack-owned asynchronous work when startup determined a refresh is
required; it never performs network or cache I/O on the Host thread. Closing the Pack registration removes the Core
publication immediately. A reflective compatibility bridge keeps the same Pack linkable on Feature API 2.0-2.4
Hosts, where Overlay and System Info continue to work.

## Alliance Directory provider

On a Host that exposes Feature API 2.4's optional Alliance Directory capability, the Pack publishes only the distinct
positive alliance IDs observed in its current Sovereignty snapshot. The directory is available immediately from the
local Sovereignty LKG. Alliance names come from that snapshot until public alliance metadata has been validated; ESI
`GET /alliances/{alliance_id}` then enriches the same stable ID with the authoritative display name and ticker.

Alliance metadata uses a separate `alliance-metadata-lkg.json` cache with its own HTTP validators and freshness. A
metadata request failure retains its last-good record and never blocks or replaces Sovereignty Overlay/System Info
data. The Pack does not enumerate all EVE alliances and does not request details for IDs absent from its current
Sovereignty snapshot. Removing an ID from the snapshot removes it from the published directory without rewriting the
historical metadata cache.

Hosts without the optional capability, including Feature API 2.0 and 2.3 runtimes, skip this registration through the
isolated compatibility bridge. Their existing Overlay and System Info contributions continue to work.

## Alliance visual identity and territory presentation

For v2/PUBLIC_ESI data, alliance identity is keyed by `allianceId`, so rename events do not change grouping or emblem
identity. Current Planner derives its deterministic, map-readable colors and emblem references in its presentation
layer; those fields are not added to Core `SystemOwnership`. Explicit Unknown/Unclaimed ownership uses neutral
presentation. The old-Host fallback retains the Pack's former deterministic color/emblem metadata so existing Hosts
remain compatible; v1 records use a legacy name key and never pretend to know an alliance ID.

Current Planner converts typed ownership records into low-priority territory seeds and reuses its existing territory,
shared-border, label, legend, emblem, and compositing algorithms in both 2D and 3D. Unsupported space remains
transparent rather than being synthesized as an owner. Routes, selected systems, saved markers, hover state, nodes,
and labels remain Core visuals above the territory layer.

This is a behavior contract, not a record of temporary geometry-tuning constants.

## Emblem metadata

When a claimed owner has an `allianceId`, current Planner derives a stable emblem key plus this image-service reference
shape:

```text
https://images.evetech.net/alliances/<allianceId>/logo?size=256
```

Unknown/unclaimed owners and v1 records without an alliance ID do not receive remote emblem metadata. Planner decides
whether a territory component can safely display an emblem, loads qualifying images asynchronously, clips and sizes
them through generic presentation, and treats image failure as non-fatal. Ownership color and territory geometry do
not depend on logo availability. The legacy Pack Overlay emits the same shape only for old-Host compatibility.

## Preferences integration

The Host exposes Sovereignty-specific preference controls only while a typed Sovereignty provider is registered. The
accepted preference adjusts the zoom emphasis of generic emblem presentation; it does not change ownership data,
invalidate the selected snapshot, or rebuild territory geometry. Preference storage and UI remain Host-owned.

## Storage and dependency boundary

The Pack uses only paths mediated by its Feature API `PackStorage` and never reaches into Core databases or services.
Its committed build declares `dev.evestaticmapplanner:feature-api:2.5.0` as `compileOnly` and test input. It has no
Gradle project dependency on Feature API, permanent composite include, sibling path, or Core source dependency.
Optional developer composite substitution remains command-line-only.

## Testing boundary

Tests use injected HTTP senders, fake clocks, temporary Pack storage, embedded fixtures, and deterministic local
snapshots. They cover PUBLIC_ESI validation, LKG v1/v2/v3 compatibility, freshness boundaries, offline fallback,
background replacement, duplicate invalidation, cancellation/disable races, cache atomicity, repository/provider
behavior, identity metadata, typed System Ownership, Alliance Directory ID consistency and metadata enrichment,
new-Host retirement of legacy presentation registration, old-Host compatibility, and
canonical standalone JAR packaging without live Internet.

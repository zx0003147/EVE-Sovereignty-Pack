# EVE Sovereignty Pack 1.0.0

This major release publishes stable Alliance Directory and typed Sovereignty data for EVE Static Map Planner 2.0.0
while retaining a gated legacy presentation path for older Feature API family-2 Hosts.

## Highlights

- Publishes an Alliance Directory for positive Alliance IDs observed in the current Sovereignty snapshot.
- Adds an independent public-ESI Alliance metadata cache for authoritative display names and tickers.
- Publishes typed, immutable Sovereignty snapshots with stable Alliance and Corporation identity fields.
- Lets current Planner Core own map, System Info, Preferences, AI, and MCP presentation from one business-data source.

## Major changes

- Registers typed Sovereignty on Feature API 2.5 Hosts and gates the former Overlay/System Info providers off when the
  typed capability is available.
- Keeps the legacy low-priority Overlay and System Info providers for older family-2 Hosts through reflective bridges.
- Separates Sovereignty LKG data from Alliance metadata freshness and failure handling.
- Advances the Sovereignty snapshot cache write format to v3 for optional Corporation IDs while preserving readable
  v1 and v2 caches. Alliance metadata uses its own version-1 cache.

## Fixes

- Keeps last-good Sovereignty and Alliance metadata available when enrichment or refresh fails.
- Prevents late refresh completion from publishing or writing after Pack disable or Host shutdown.
- Avoids duplicate current-Host presentation by using typed Core integration as the sole active path.

## Removed features

- No data capability is removed. Pack-owned current-Host presentation is retired in favor of Core presentation;
  legacy presentation remains available only for compatible older Hosts.

## Compatibility and migration

- Pack version: `1.0.0`.
- Compiled Feature API artifact remains `2.5.0`; manifest runtime compatibility family remains `2`.
- EVE Static Map Planner 2.0.0 is the recommended Host and enables typed Sovereignty plus Alliance Directory.
- Feature API 2.0-2.4 Hosts remain linkable through the reflective compatibility path; capabilities absent from those
  Hosts are skipped and the legacy Overlay/System Info providers remain active.
- Existing valid v1/v2 Sovereignty caches are read without destructive migration and are replaced by v3 only after a
  successful cache write.

## Known issues

- Alliance metadata is limited to Alliance IDs present in the current Sovereignty snapshot and is not a complete EVE
  Alliance enumeration.
- Public ESI outages may leave the last-good snapshot marked stale; they do not invalidate that cached data.

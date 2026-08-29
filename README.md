# EVE Sovereignty Pack

## What it is

EVE Sovereignty Pack is the first-party external Sovereignty Feature Pack for EVE Static Map Planner. It contributes
Sovereignty overlays and structured System Info through Core's Feature API while keeping Sovereignty acquisition,
snapshot policy, and identity metadata outside the Core repository.

Core does not bundle this Pack. This repository builds the canonical standalone `pack.jar` installed by the Host.

## Requirements

- JDK 25
- Feature API runtime contract: `2` (frozen)
- Build artifact dependency: `dev.evestaticmapplanner:feature-api:2.0.0`
- Pack version: `0.2.0`
- A Maven repository containing the Feature API artifact
- A corresponding EVE Static Map Planner release that hosts Feature API runtime contract `2`

The Pack consumes Feature API as a Maven coordinate. It has no Core source or Gradle project dependency.

## Functionality

- Anonymous Public ESI Sovereignty acquisition; no OAuth, SSO, character token, or Character ESI access.
- Validated Last Known Good (LKG) fallback for offline or unavailable startup.
- A low-priority territory Overlay contribution and Sovereignty section in System Info.
- Stable alliance visual identity based on `allianceId`.
- Alliance color, territory, and emblem presentation metadata for Core's generic renderer.
- Sovereignty Preferences integration through Host behavior when the installed provider is available.

The Pack selects one ownership snapshot during startup. It does not poll or live-refresh ownership data during the
session; restart the Pack or application to perform another startup selection.

See `docs/sovereignty.md` for the accepted data, cache, lifecycle, and presentation behavior.

## Build

For local development, pass a Maven repository containing Feature API `2.0.0`. The value is an artifact repository,
not a Core checkout or project dependency.

```powershell
.\gradlew.bat --no-daemon -PfeatureApiRepository="C:\path\to\maven-repository" clean test
.\gradlew.bat --no-daemon -PfeatureApiRepository="C:\path\to\maven-repository" check
.\gradlew.bat --no-daemon -PfeatureApiRepository="C:\path\to\maven-repository" packageExternalFeaturePack
```

An optional command-line composite can substitute the same Maven coordinate during local co-development. It remains
developer opt-in and is never a committed build dependency:

```powershell
.\gradlew.bat --include-build "C:\path\to\compatible-build" test
```

## Output

The canonical thin Pack artifact is:

```text
build/external-feature-pack/sovereignty.pack/pack.jar
```

The package verification rejects bundled Feature API, Core, Kotlin runtime, Compose, SQLite, or other Host-owned
classes.

## Installation

Install the canonical directory below the Host application's external Feature Pack root. On Windows, the resulting
layout is:

```text
%LOCALAPPDATA%\EVE Static Map Planner\feature-packs\sovereignty.pack\pack.jar
```

The Pack is discovered and compatibility-checked by Core before Core creates its isolated ClassLoader. Newly
discovered Packs follow the Host's normal disabled-by-default management flow.

## Testing

The automated test suite uses injected, fake, embedded, or local collaborators. Tests do not require live Internet,
OAuth credentials, ESI tokens, user AppData, or a real LKG cache.

Core owns the authoritative local cross-repository acceptance runner at
`scripts/acceptance-feature-pack.ps1` in the Core repository. It verifies this canonical JAR against Core from clean
worktrees without remote publication.

## Architecture

Production code depends only on Feature API plus JDK APIs; it does not depend on Core source, Core projects, Compose,
SQLite, or MCP. Feature API is `compileOnly`, so the Host supplies the single runtime contract identity. The Pack owns
its PUBLIC_ESI composition, canonical snapshot, LKG policy, Overlay/System Info providers, and presentation metadata.
Core owns lifecycle hosting, compatibility, storage path mediation, aggregation, rendering, Pack management, and
Preferences UI behavior.

## CI foundation

`.github/workflows/sovereignty-ci.yml` contains the final standalone coordinate-consumption build shape, but is
manual-only until Feature API `2.0.0` is published to an authorized production Maven repository. Before dispatch, the
repository variable `FEATURE_API_MAVEN_REPOSITORY_URL` must identify that repository and package read permissions must
be configured. The workflow deliberately fails its prerequisite check when the variable is absent; it never checks
out Core source and does not use a permanent composite build.

No publication, release, tag, or GitHub repository is created by this CI foundation.

## Publication status

No public repository URL, published package, release, or source-code license is claimed. `NOTICE.md` and
`THIRD-PARTY-NOTICES.md` contain factual third-party, data-provenance, and trademark notices; neither is a source
license. The repository may remain private and a compliant binary may be shared under the applicable third-party
terms without choosing a license for the original source. Public source publication remains blocked until a source
license is explicitly chosen.

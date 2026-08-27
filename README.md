# EVE Sovereignty Pack

EVE Sovereignty Pack is a standalone external Feature Pack for EVE Static Map Planner. It supplies sovereignty overlays and System Info data through Feature API contract `1`.

## Build requirements

- JDK 25
- Feature API artifact `dev.evestaticmapplanner:feature-api:1.0.0`
- A Maven repository containing that artifact

The Pack version is `0.1.0`. Its single version authority is the `packVersion` property in `gradle.properties`.

For local development, pass the Maven repository explicitly. The path is an artifact repository; it is not a Core source or Gradle project dependency.

```powershell
.\gradlew.bat --no-daemon -PfeatureApiRepository="C:\path\to\maven-repository" clean test
.\gradlew.bat --no-daemon -PfeatureApiRepository="C:\path\to\maven-repository" check
.\gradlew.bat --no-daemon -PfeatureApiRepository="C:\path\to\maven-repository" packageExternalFeaturePack
```

Gradle's optional command-line composite mode can also substitute matching Maven coordinates for development without adding a committed source path:

```powershell
.\gradlew.bat --include-build "C:\path\to\compatible-build" test
```

Production repository configuration can later be supplied with `featureApiGitHubPackagesRepository`. If that repository requires authentication, use `githubPackagesUsername` / `githubPackagesToken` Gradle properties or `GITHUB_ACTOR` / `GITHUB_TOKEN` environment variables. No GitHub Packages URL or credentials are required for the local Maven-repository build.

## Output and installation

The canonical thin artifact is:

```text
build/external-feature-pack/sovereignty.pack/pack.jar
```

Install the `sovereignty.pack` directory beneath the Host application's configured external Feature Pack root so the final layout remains `sovereignty.pack/pack.jar`.

The automated test suite uses injected, fake, or local ESI collaborators and does not require live Internet access.

No public repository, published package, release, or source-code license is claimed by this project. See `NOTICE.md` for trademark notices; public publication remains blocked on a license decision.

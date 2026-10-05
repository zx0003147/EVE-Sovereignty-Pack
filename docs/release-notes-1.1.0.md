# EVE Sovereignty Pack 1.1.0

## Alliance Capital

- Builds against Feature API 2.6.0 while retaining runtime contract family 2.
- Parses ESI `is_capital_system` from the existing `/sovereignty/systems` response.
- Publishes the optional Alliance Capital capability from the same in-memory publication state, refresh request, and
  Last Known Good cache as typed Sovereignty.
- Preserves multiple capital observations for the same alliance so the Host can expose ambiguity instead of silently
  choosing a system.
- Advances the Sovereignty cache to v4. Valid v1-v3 caches remain readable for Sovereignty, while Alliance Capital
  remains unavailable until a successful refresh supplies the missing observation.

No UI, route, map-rendering, SSO, OAuth, or Character ESI behavior is added by this release.

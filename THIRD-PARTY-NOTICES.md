# Third-Party Notices

This file is not a license for the EVE Sovereignty Pack's original source.

The canonical `pack.jar` is thin: it contains Pack-owned bytecode and resources only. Feature API `2.0.0` and Kotlin stdlib `2.3.0` are compile-only and are not bundled. The Pack contains no Core, Compose, SQLite, MCP, OAuth, or other third-party library bytecode.

The embedded `sovereignty.json` fallback is a small project-authored fixture containing selected EVE universe and alliance identifiers/names plus synthetic scenario fields. Its exact provenance and classification are recorded in `legal/CCP-EVE-DATA-PROVENANCE.md`. It is not a captured ESI response and is not represented as a current sovereignty snapshot.

Runtime public ESI responses, EVE universe facts, and Image Server URLs remain subject to the current CCP Developer License Agreement and service terms. They are not licensed as project source. The distributor must have accepted and must comply with the then-current CCP Developer License Agreement before sharing a binary.

The Pack is unofficial and is not affiliated with or endorsed by CCP hf. See `NOTICE.md` for the proprietary/trademark notice.

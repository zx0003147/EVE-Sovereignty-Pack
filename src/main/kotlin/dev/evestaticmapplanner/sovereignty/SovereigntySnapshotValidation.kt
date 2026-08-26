package dev.evestaticmapplanner.sovereignty

internal object SovereigntySnapshotValidation {
    fun validatePublicEsi(snapshot: SovereigntySnapshot): String? {
        snapshot.metadata.failureMessage?.let { return "snapshot contains failure metadata" }
        if (snapshot.metadata.ignoredRecordCount != 0) return "snapshot contains ignored records"
        if (snapshot.records.isEmpty()) return "snapshot contains no sovereignty records"

        val seenSystemIds = mutableSetOf<Int>()
        snapshot.records.forEachIndexed { index, record ->
            val context = "records[$index]"
            if (record.systemId !in NEW_EDEN_SYSTEM_ID_RANGE) {
                return "$context has invalid systemId ${record.systemId}"
            }
            if (!seenSystemIds.add(record.systemId)) {
                return "snapshot contains duplicate systemId ${record.systemId}"
            }
            if (!record.allianceName.isCanonicalText()) {
                return "$context has an empty or invalid allianceName"
            }
            if (record.corporationName?.isCanonicalText() == false) {
                return "$context has an empty or invalid corporationName"
            }
            if (record.sovereigntyStatus != PUBLIC_ESI_CLAIMED_STATUS) {
                return "$context has unsupported sovereigntyStatus '${record.sovereigntyStatus}'"
            }
        }
        return null
    }
}

private fun String.isCanonicalText(): Boolean =
    isNotBlank() && this == trim() && none(Char::isISOControl)

internal const val PUBLIC_ESI_CLAIMED_STATUS = "Claimed"
internal val NEW_EDEN_SYSTEM_ID_RANGE = 30_000_000..30_999_999

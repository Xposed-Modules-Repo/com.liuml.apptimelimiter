package com.liuml.apptimelimiter.nonroot

data class PersistentIncidentClaim(
    val handledIncidentIds: List<String>,
    val isNewIncident: Boolean,
)

/**
 * Keeps a bounded, insertion-ordered history for events that must survive an accessibility
 * service or manager-process restart.
 */
object PersistentIncidentPolicy {
    private const val MAX_INCIDENT_ID_LENGTH = 1_024

    fun claim(
        existingIncidentIds: Collection<String>,
        incidentId: String,
        maxEntries: Int,
    ): PersistentIncidentClaim {
        val boundedSize = maxEntries.coerceAtLeast(1)
        val normalized = existingIncidentIds
            .asSequence()
            .filter(::isValid)
            .distinct()
            .toList()
            .takeLast(boundedSize)
        if (!isValid(incidentId) || incidentId in normalized) {
            return PersistentIncidentClaim(
                handledIncidentIds = normalized,
                isNewIncident = false,
            )
        }
        return PersistentIncidentClaim(
            handledIncidentIds = (normalized + incidentId).takeLast(boundedSize),
            isNewIncident = true,
        )
    }

    private fun isValid(incidentId: String): Boolean =
        incidentId.isNotBlank() &&
            incidentId.length <= MAX_INCIDENT_ID_LENGTH &&
            '\n' !in incidentId &&
            '\r' !in incidentId
}

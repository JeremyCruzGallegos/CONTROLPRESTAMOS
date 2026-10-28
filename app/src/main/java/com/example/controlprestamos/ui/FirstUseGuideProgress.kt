package com.example.controlprestamos.ui

import org.json.JSONArray
import org.json.JSONObject

internal enum class FirstUseTopic { HOME, LOANS, PAYMENTS, CREATE_LOAN, CASH, HISTORY, DETAIL, SETTINGS }

/** Eligibility is decided once from a loaded catalog, then survives the first loan and app restarts. */
internal data class FirstUseGuideProgress(val eligible: Boolean? = null,
    val seen: Set<FirstUseTopic> = emptySet(), val skipped: Boolean = false) {
    fun assess(clientBook: Boolean, catalogLoaded: Boolean, hasLoans: Boolean): FirstUseGuideProgress =
        if (eligible != null || !catalogLoaded) this else copy(eligible = clientBook && !hasLoans)

    fun pending(topic: FirstUseTopic?, interactive: Boolean): FirstUseTopic? =
        topic?.takeIf { interactive && eligible == true && !skipped && it !in seen }

    fun acknowledge(topic: FirstUseTopic) = copy(seen = seen + topic)
    fun skipAll() = copy(skipped = true)
    fun encode(): String = JSONObject().put("eligible", eligible ?: JSONObject.NULL)
        .put("skipped", skipped).put("seen", JSONArray(seen.map { it.name }.sorted())).toString()

    companion object {
        fun decode(value: String?): FirstUseGuideProgress {
            if (value == null) return FirstUseGuideProgress()
            return runCatching {
                val json = JSONObject(value)
                val names = json.optJSONArray("seen") ?: JSONArray()
                FirstUseGuideProgress(if (json.isNull("eligible")) null else json.getBoolean("eligible"),
                    (0 until names.length()).mapNotNull { i -> FirstUseTopic.entries.firstOrNull { it.name == names.optString(i) } }.toSet(),
                    json.optBoolean("skipped", false))
            }.getOrElse { FirstUseGuideProgress(eligible = false) }
        }
    }
}

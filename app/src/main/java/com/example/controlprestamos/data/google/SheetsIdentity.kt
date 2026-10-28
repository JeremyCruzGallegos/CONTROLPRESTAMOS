package com.example.controlprestamos.data.google

/** Matches numeric original IDs and prefixed IDs in carried rows; never use display year as origin. */
object SheetsIdentity {
    const val PRODUCTION_ID = "EXAMPLE_LEGACY_SPREADSHEET_ID_0001"
    const val EMPTY_TEST_ID = "EXAMPLE_TEST_SPREADSHEET_ID_000001"
    const val CLIENT_ID = "YOUR_SPREADSHEET_ID_REPLACE_ME"
    fun isSimpleBook(id: String): Boolean = id == EMPTY_TEST_ID || id == CLIENT_ID
    fun isDirectBook(id: String): Boolean = id == PRODUCTION_ID || isSimpleBook(id)
    fun canonical(year: Int, rawId: String): String {
        val id = rawId.trim()
        if (id.matches(Regex("\\d{4}-\\d{3,}"))) return id
        require(id.matches(Regex("\\d+"))) { "ID de préstamo no reconocido" }
        val number = id.toInt()
        require(number > 0 && year in 2026..2028)
        return "$year-${number.toString().padStart(3, '0')}"
    }
    fun paidFraction(raw: String): Pair<Int, Int> {
        val parts = raw.trim().split('/')
        require(parts.size == 2) { "Fracción de cuotas inválida" }
        val paid = parts[0].toInt(); val total = parts[1].toInt()
        require(total > 0 && paid in 0..total) { "El conteo de cuotas necesita revisión" }
        return paid to total
    }
    fun requireCopy(id: String) {
        require(id.matches(Regex("[A-Za-z0-9_-]{20,}")) && id != PRODUCTION_ID) { "La integración preparada solo admite una copia de pruebas" }
    }
    fun requireBook(id: String) {
        require(id.matches(Regex("[A-Za-z0-9_-]{20,}"))) { "ID de libro inválido" }
    }
}

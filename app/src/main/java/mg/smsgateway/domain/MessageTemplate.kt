package mg.smsgateway.domain

import java.text.Normalizer
import java.util.Locale

data class SpreadsheetVariable(
    val name: String,
    val columnIndex: Int,
    val label: String,
)

object MessageTemplate {
    private val tokenPattern = Regex("#([A-Za-z0-9_]+)")

    fun variables(document: SpreadsheetDocument, headerLine: Int?): List<SpreadsheetVariable> {
        val width = document.rows.maxOfOrNull { it.cells.size } ?: return emptyList()
        val header = headerLine?.let { line -> document.rows.firstOrNull { it.line == line } }
        val result = LinkedHashMap<String, SpreadsheetVariable>()
        for (index in 0 until width) {
            val name = columnName(index)
            val headerValue = header?.cells?.getOrNull(index)?.value?.trim().orEmpty()
            val label = headerValue.ifEmpty { "Colonne $name" }
            result[name] = SpreadsheetVariable(name, index, label)
        }
        val headerNames = (0 until width).mapNotNull { index ->
            val value = header?.cells?.getOrNull(index)?.value?.trim().orEmpty()
            normalizeName(value)?.let { it to index }
        }
        headerNames.groupBy { it.first }.forEach { (name, matches) ->
            if (matches.size == 1) {
                val index = matches.single().second
                val existing = result[name]
                if (existing == null || existing.columnIndex == index) {
                    result[name] = SpreadsheetVariable(name, index, header?.cells?.getOrNull(index)?.value?.trim().orEmpty())
                }
            }
        }
        return result.values.sortedWith(compareBy<SpreadsheetVariable> { it.columnIndex }.thenBy { it.name })
    }

    fun valuesFor(row: SpreadsheetRow, variables: List<SpreadsheetVariable>): Map<String, String> =
        variables.associate { variable ->
            variable.name to row.cells.getOrNull(variable.columnIndex)?.value.orEmpty()
        }

    fun tokens(message: String): Set<String> =
        tokenPattern.findAll(message).map { it.groupValues[1].uppercase(Locale.ROOT) }.toSet()

    fun render(message: String, values: Map<String, String>): String =
        tokenPattern.replace(message) { match ->
            values[match.groupValues[1].uppercase(Locale.ROOT)] ?: match.value
        }

    private fun normalizeName(value: String): String? {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9]+"), "_")
            .trim('_')
            .takeIf(String::isNotEmpty)
            ?: return null
        return if (normalized.first().isDigit()) "_$normalized" else normalized
    }

    private fun columnName(index: Int): String {
        var value = index + 1
        val result = StringBuilder()
        while (value > 0) {
            val remainder = (value - 1) % 26
            result.insert(0, ('A'.code + remainder).toChar())
            value = (value - 1) / 26
        }
        return result.toString()
    }
}

package mg.smsgateway.domain

import android.content.ContentResolver
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class SpreadsheetCell(val value: String, val numeric: Boolean = false)

data class SpreadsheetRow(val line: Int, val cells: List<SpreadsheetCell>)

data class SpreadsheetDocument(val name: String, val rows: List<SpreadsheetRow>)

data class PhoneColumnCandidate(
    val index: Int,
    val header: String?,
    val nonEmpty: Int,
    val valid: Int,
    val invalid: Int,
    val confidence: Int,
    val headerRow: Int?,
)

class SpreadsheetImporter(private val contentResolver: ContentResolver) {
    fun read(uri: Uri, displayName: String): SpreadsheetDocument {
        val input = contentResolver.openInputStream(uri) ?: error("Impossible d'ouvrir le fichier sélectionné.")
        input.use {
            return when (displayName.substringAfterLast('.', "").lowercase()) {
                "csv" -> readCsv(it, displayName)
                "xlsx" -> readXlsx(it, displayName)
                "xls" -> error("Le format .xls ancien n'est pas pris en charge. Enregistrez le classeur en .xlsx ou .csv.")
                else -> error("Format non pris en charge. Choisissez un fichier .xlsx ou .csv.")
            }
        }
    }

    private fun readCsv(input: InputStream, name: String): SpreadsheetDocument {
        val text = input.readBounded(MAX_FILE_BYTES).toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val delimiter = detectDelimiter(text)
        val values = parseDelimited(text, delimiter)
        if (values.size > MAX_ROWS || values.any { it.second.size > MAX_COLUMNS }) {
            error("Le fichier dépasse la limite de $MAX_ROWS lignes ou $MAX_COLUMNS colonnes.")
        }
        val rows = values.map { (line, values) ->
            SpreadsheetRow(line, values.map { SpreadsheetCell(it) })
        }
        if (rows.none { row -> row.cells.any { it.value.isNotBlank() } }) error("Le fichier CSV est vide.")
        return SpreadsheetDocument(name, rows)
    }

    private fun readXlsx(input: InputStream, name: String): SpreadsheetDocument {
        val entries = HashMap<String, ByteArray>()
        var expandedBytes = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val data = zip.readBounded(MAX_XLSX_UNCOMPRESSED_BYTES)
                    expandedBytes += data.size
                    if (expandedBytes > MAX_XLSX_UNCOMPRESSED_BYTES) error("Le classeur décompressé dépasse la taille maximale autorisée.")
                    entries[entry.name] = data
                }
                zip.closeEntry()
            }
        }
        val strings = entries["xl/sharedStrings.xml"]?.let(::sharedStrings).orEmpty()
        val formats = entries["xl/styles.xml"]?.let(::zeroFormats).orEmpty()
        val sheetPath = firstSheetPath(entries)
        val sheet = entries[sheetPath] ?: error("Le classeur ne contient pas de première feuille lisible.")
        val rows = sheetRows(sheet, strings, formats)
        if (rows.none { row -> row.cells.any { it.value.isNotBlank() } }) error("La première feuille du classeur est vide.")
        return SpreadsheetDocument(name, rows)
    }

    private fun sharedStrings(xml: ByteArray): List<String> {
        val parser = parser(xml)
        val result = ArrayList<String>()
        var current: StringBuilder? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> current = StringBuilder()
                    "t" -> if (current != null) current?.append(parser.nextText())
                }
                XmlPullParser.END_TAG -> if (parser.name == "si") {
                    result += current?.toString().orEmpty()
                    current = null
                }
            }
        }
        return result
    }

    private fun zeroFormats(xml: ByteArray): Map<Int, String> {
        val parser = parser(xml)
        val custom = HashMap<Int, String>()
        val styleFormatIds = ArrayList<Int>()
        var inCellXfs = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "numFmt" -> {
                        val id = parser.getAttributeValue(null, "numFmtId")?.toIntOrNull()
                        val code = parser.getAttributeValue(null, "formatCode")
                        if (id != null && code != null) custom[id] = code
                    }
                    "cellXfs" -> inCellXfs = true
                    "xf" -> if (inCellXfs) {
                        styleFormatIds += parser.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: 0
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "cellXfs") inCellXfs = false
            }
        }
        return styleFormatIds.mapIndexedNotNull { style, formatId ->
            val code = custom[formatId]
            if (code != null && code.matches(Regex("0{2,}"))) style to code.length else null
        }.associate { it.first to it.second.toString() }
    }

    private fun firstSheetPath(entries: Map<String, ByteArray>): String {
        val workbook = entries["xl/workbook.xml"] ?: return "xl/worksheets/sheet1.xml"
        val workbookParser = parser(workbook)
        var activeIndex = 0
        var sheetIndex = 0
        var activeSheetIndex = 0
        val sheetIds = ArrayList<String>()
        while (workbookParser.next() != XmlPullParser.END_DOCUMENT) {
            if (workbookParser.eventType == XmlPullParser.START_TAG) {
                if (workbookParser.name == "workbookView") {
                    activeIndex = workbookParser.getAttributeValue(null, "activeTab")?.toIntOrNull() ?: 0
                } else if (workbookParser.name == "sheet") {
                    if (sheetIndex == activeIndex) activeSheetIndex = sheetIndex
                    sheetIds += workbookParser.getAttributeValue(REL_NS, "id")
                        ?: workbookParser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                        ?: ""
                    sheetIndex++
                }
            }
        }
        val relationId = sheetIds.getOrNull(activeSheetIndex).orEmpty()
        val relationships = entries["xl/_rels/workbook.xml.rels"] ?: return "xl/worksheets/sheet1.xml"
        val relParser = parser(relationships)
        var target: String? = null
        while (relParser.next() != XmlPullParser.END_DOCUMENT) {
            if (relParser.eventType == XmlPullParser.START_TAG && relParser.name == "Relationship" &&
                relParser.getAttributeValue(null, "Id") == relationId
            ) {
                target = relParser.getAttributeValue(null, "Target")
            }
        }
        val normalized = target?.removePrefix("/")?.let {
            if (it.startsWith("xl/")) it else "xl/$it"
        }
        return normalized ?: "xl/worksheets/sheet1.xml"
    }

    private fun sheetRows(xml: ByteArray, sharedStrings: List<String>, zeroFormats: Map<Int, String>): List<SpreadsheetRow> {
        val parser = parser(xml)
        val rows = ArrayList<SpreadsheetRow>()
        var rowNumber = 0
        var cells = sortedMapOf<Int, SpreadsheetCell>()
        var cellIndex = -1
        var cellType = ""
        var styleIndex = 0
        var value = ""
        var inlineValue = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        rowNumber = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: (rows.size + 1)
                        cells = sortedMapOf()
                    }
                    "c" -> {
                        cellIndex = columnIndex(parser.getAttributeValue(null, "r").orEmpty())
                        cellType = parser.getAttributeValue(null, "t").orEmpty()
                        styleIndex = parser.getAttributeValue(null, "s")?.toIntOrNull() ?: 0
                        value = ""
                        inlineValue = ""
                    }
                    "v" -> value = parser.nextText()
                    "t" -> if (cellType == "inlineStr") inlineValue += parser.nextText()
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "c" -> if (cellIndex >= 0) {
                        val numeric = cellType.isEmpty()
                        val raw = when (cellType) {
                            "s" -> sharedStrings.getOrNull(value.toIntOrNull() ?: -1).orEmpty()
                            "inlineStr" -> inlineValue
                            else -> value
                        }
                        val width = zeroFormats[styleIndex]?.toIntOrNull()
                        val formatted = if (numeric && width != null) raw.toLongOrNull()?.toString()?.padStart(width, '0') ?: raw else raw
                        cells[cellIndex] = SpreadsheetCell(formatted, numeric && width == null)
                    }
                    "row" -> {
                        if (rows.size >= MAX_ROWS) error("Le fichier dépasse la limite de $MAX_ROWS lignes.")
                        rows += SpreadsheetRow(rowNumber, cells.values.toList())
                        val width = (cells.keys.maxOrNull() ?: -1) + 1
                        if (width > MAX_COLUMNS) error("Le fichier dépasse la limite de $MAX_COLUMNS colonnes.")
                        if (rows.last().cells.size != width) {
                            val dense = List(width) { cells[it] ?: SpreadsheetCell("") }
                            rows[rows.lastIndex] = SpreadsheetRow(rowNumber, dense)
                        }
                    }
                }
            }
        }
        return rows
    }

    private fun parser(xml: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        setInput(xml.inputStream(), "UTF-8")
    }

    private fun columnIndex(reference: String): Int {
        var result = 0
        for (char in reference.takeWhile { it.isLetter() }) {
            result = result * 26 + (char.uppercaseChar() - 'A' + 1)
        }
        return result - 1
    }

    private fun detectDelimiter(text: String): Char {
        val firstLine = text.lineSequence().firstOrNull(String::isNotBlank).orEmpty()
        return listOf(',', ';', '\t').maxBy { delimiter ->
            var quoted = false
            var count = 0
            var index = 0
            while (index < firstLine.length) {
                when (firstLine[index]) {
                    '"' -> if (quoted && firstLine.getOrNull(index + 1) == '"') index++ else quoted = !quoted
                    delimiter -> if (!quoted) count++
                }
                index++
            }
            count
        }
    }

    private fun parseDelimited(text: String, delimiter: Char): List<Pair<Int, List<String>>> {
        val rows = ArrayList<Pair<Int, List<String>>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var lineNumber = 1
        var rowStartLine = 1
        var hasPendingData = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && text.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    hasPendingData = true
                    index++
                }
                char == '"' -> {
                    quoted = !quoted
                    hasPendingData = true
                }
                char == delimiter && !quoted -> {
                    row += field.toString()
                    field.clear()
                    hasPendingData = true
                }
                char == '\n' || char == '\r' -> {
                    if (quoted) {
                        field.append('\n')
                    } else {
                        row += field.toString()
                        rows += rowStartLine to row
                        field.clear()
                        row = ArrayList()
                        rowStartLine = lineNumber + 1
                        hasPendingData = false
                    }
                    lineNumber++
                    if (char == '\r' && text.getOrNull(index + 1) == '\n') index++
                }
                else -> {
                    field.append(char)
                    hasPendingData = true
                }
            }
            index++
        }
        if (quoted) error("Le fichier CSV contient un champ entre guillemets non terminé.")
        if (hasPendingData || field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += rowStartLine to row
        }
        return rows
    }

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) error("Le fichier dépasse la taille maximale autorisée.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        private const val MAX_FILE_BYTES = 16 * 1024 * 1024
        private const val MAX_XLSX_UNCOMPRESSED_BYTES = 32 * 1024 * 1024
        private const val MAX_ROWS = 20_000
        private const val MAX_COLUMNS = 256
        private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    }
}

class PhoneColumnDetector(private val validator: PhoneNumberValidator) {
    fun detect(document: SpreadsheetDocument, region: String): List<PhoneColumnCandidate> {
        val width = document.rows.maxOfOrNull { it.cells.size } ?: return emptyList()
        val headerRow = document.rows.firstOrNull { row ->
            row.cells.any { cell -> HEADER.matches(cell.value.trim().lowercase()) }
        }
        return (0 until width).mapNotNull { column ->
            val headerValue = headerRow?.cells?.getOrNull(column)?.value?.trim()?.takeIf(String::isNotEmpty)
            val values = document.rows.filter { it.line != headerRow?.line }
                .mapNotNull { row -> row.cells.getOrNull(column)?.value?.trim()?.takeIf(String::isNotEmpty) }
            if (values.isEmpty()) return@mapNotNull null
            val valid = values.count { looksLikePhone(it) && validator.validate(it, region) is PhoneResult.Valid }
            val ratio = valid.toFloat() / values.size
            val confidence = (
                ratio * 65 + minOf(valid, 5) * 5 +
                    if (headerValue != null) 20 else if (valid < 3) -10 else 0
                ).toInt().coerceAtMost(100)
            if (valid == 0) null else PhoneColumnCandidate(
                index = column,
                header = headerValue,
                nonEmpty = values.size,
                valid = valid,
                invalid = values.size - valid,
                confidence = confidence,
                headerRow = headerRow?.line,
            )
        }.sortedWith(compareByDescending<PhoneColumnCandidate> { it.confidence }.thenByDescending { it.valid })
    }

    fun values(document: SpreadsheetDocument, candidate: PhoneColumnCandidate, includeHeader: Boolean = false): List<Pair<Int, SpreadsheetCell>> =
        document.rows.mapNotNull { row ->
            if (!includeHeader && row.line == candidate.headerRow) return@mapNotNull null
            val cell = row.cells.getOrNull(candidate.index) ?: return@mapNotNull null
            cell.value.trim().takeIf(String::isNotEmpty)?.let { row.line to cell.copy(value = it) }
        }

    private fun looksLikePhone(value: String): Boolean =
        value.matches(Regex("[+0-9\\s().-]{8,24}")) &&
            value.count(Char::isDigit) in 8..15

    private companion object {
        val HEADER = Regex(".*(phone|tel|t[eé]l[eé]phone|mobile|num[eé]ro|number).*")
    }
}

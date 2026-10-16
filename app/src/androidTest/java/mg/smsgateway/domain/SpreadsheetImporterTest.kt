package mg.smsgateway.domain

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpreadsheetImporterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val importer = SpreadsheetImporter(context.contentResolver)

    @Test
    fun readsCsvWithQuotedSeparatorsAndRealSourceLines() {
        val file = tempFile("contacts.csv", "name;phone;note\nAlice;\"034 12 345 67\";\"office;mobile\"\n\nBob;+261341234568;ok")
        try {
            val document = importer.read(Uri.fromFile(file), file.name)

            assertEquals(4, document.rows.size)
            assertEquals(1, document.rows[0].line)
            assertEquals("office;mobile", document.rows[1].cells[2].value)
            assertEquals(4, document.rows[3].line)
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsXlsxAndRestoresZeroesFromExcelNumberFormat() {
        val file = xlsxFile()
        try {
            val document = importer.read(Uri.fromFile(file), file.name)

            assertEquals("Téléphone", document.rows[0].cells[0].value)
            assertEquals("0341234567", document.rows[1].cells[0].value)
            assertFalse(document.rows[1].cells[0].numeric)
            assertTrue(document.rows[1].cells[1].numeric)
        } finally {
            file.delete()
        }
    }

    @Test
    fun reportsEmptyAndDamagedFilesInsteadOfReturningEmptyRecipients() {
        val empty = tempFile("empty.csv", "\n\r\n")
        val damaged = tempFile("damaged.xlsx", "not a zip archive")
        try {
            assertThrows(IllegalStateException::class.java) {
                importer.read(Uri.fromFile(empty), empty.name)
            }
            assertThrows(Exception::class.java) {
                importer.read(Uri.fromFile(damaged), damaged.name)
            }
        } finally {
            empty.delete()
            damaged.delete()
        }
    }

    private fun tempFile(name: String, contents: String): File =
        File(context.cacheDir, name).apply { writeText(contents, Charsets.UTF_8) }

    private fun xlsxFile(): File = File(context.cacheDir, "contacts.xlsx").apply {
        ZipOutputStream(outputStream()).use { zip ->
            entry(zip, "xl/workbook.xml", """
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <bookViews><workbookView activeTab="0"/></bookViews>
                  <sheets><sheet name="Contacts" sheetId="1" r:id="rId1"/></sheets>
                </workbook>
            """.trimIndent())
            entry(zip, "xl/_rels/workbook.xml.rels", """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Target="worksheets/sheet1.xml"/>
                </Relationships>
            """.trimIndent())
            entry(zip, "xl/sharedStrings.xml", """
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <si><t>Téléphone</t></si>
                </sst>
            """.trimIndent())
            entry(zip, "xl/styles.xml", """
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <numFmts count="1"><numFmt numFmtId="164" formatCode="0000000000"/></numFmts>
                  <cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="164"/></cellXfs>
                </styleSheet>
            """.trimIndent())
            entry(zip, "xl/worksheets/sheet1.xml", """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1"><c r="A1" t="s"><v>0</v></c></row>
                    <row r="2"><c r="A2" s="1"><v>341234567</v></c><c r="B2"><v>261341234568</v></c></row>
                  </sheetData>
                </worksheet>
            """.trimIndent())
        }
    }

    private fun entry(zip: ZipOutputStream, name: String, xml: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(xml.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}

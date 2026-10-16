package mg.smsgateway.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneColumnDetectorTest {
    private val detector = PhoneColumnDetector(PhoneNumberValidator(listOf(MadagascarRule)))

    @Test
    fun choosesPhoneColumnOverIdentifiersAndKeepsSourceLines() {
        val document = SpreadsheetDocument(
            name = "contacts.csv",
            rows = listOf(
                row(1, "Référence", "Téléphone", "Statut"),
                row(2, "100001", "034 12 345 67", "Actif"),
                row(3, "100002", "+261 34 123 45 68", "Actif"),
                row(4, "100003", "0321234569", "Inactif"),
            ),
        )

        val candidates = detector.detect(document, "MG")

        assertEquals(1, candidates.size)
        assertEquals(1, candidates.single().index)
        assertEquals(1, candidates.single().headerRow)
        assertEquals(3, candidates.single().valid)
        assertEquals(0, candidates.single().invalid)
        assertTrue(candidates.single().confidence >= 70)
        assertEquals(listOf(2, 3, 4), detector.values(document, candidates.single()).map { it.first })
    }

    @Test
    fun returnsMultiplePhoneColumnsForUserSelection() {
        val document = SpreadsheetDocument(
            name = "contacts.xlsx",
            rows = listOf(
                row(1, "Mobile", "Téléphone secondaire"),
                row(2, "0341234567", "+261341234568"),
                row(3, "0321234569", "+261321234570"),
            ),
        )

        val candidates = detector.detect(document, "MG")

        assertEquals(listOf(0, 1), candidates.map { it.index })
        assertTrue(candidates.all { it.valid == 2 && it.confidence >= 70 })
    }

    @Test
    fun oneHeaderlessValueIsNotConfidentEnoughForAutomaticSelection() {
        val document = SpreadsheetDocument(
            name = "small.csv",
            rows = listOf(row(7, "0341234567")),
        )

        val candidate = detector.detect(document, "MG").single()

        assertTrue(candidate.confidence < 70)
        assertEquals(null, candidate.headerRow)
    }

    private fun row(line: Int, vararg values: String) =
        SpreadsheetRow(line, values.map { SpreadsheetCell(it) })
}

package mg.smsgateway.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTemplateTest {
    @Test
    fun exposesHeaderNamesAndExcelColumnLettersForEachRow() {
        val document = SpreadsheetDocument(
            "contacts.csv",
            listOf(
                SpreadsheetRow(1, listOf(SpreadsheetCell("Téléphone"), SpreadsheetCell("Prénom"), SpreadsheetCell("Classe"))),
                SpreadsheetRow(2, listOf(SpreadsheetCell("0341234567"), SpreadsheetCell("Élodie"), SpreadsheetCell("5A"))),
            ),
        )
        val variables = MessageTemplate.variables(document, headerLine = 1)
        val rowValues = MessageTemplate.valuesFor(document.rows[1], variables)

        assertTrue(variables.any { it.name == "PRENOM" && it.columnIndex == 1 })
        assertTrue(variables.any { it.name == "B" && it.columnIndex == 1 })
        assertEquals("Élodie", rowValues["PRENOM"])
        assertEquals("Élodie", rowValues["B"])
        assertEquals("Bonjour Élodie, classe 5A", MessageTemplate.render("Bonjour #PRENOM, classe #C", rowValues))
    }

    @Test
    fun ignoresAmbiguousHeadersAndUsesExcelAliasesWithoutAHeader() {
        val document = SpreadsheetDocument(
            "contacts.csv",
            listOf(
                SpreadsheetRow(1, listOf(SpreadsheetCell("Nom"), SpreadsheetCell("Nom"))),
                SpreadsheetRow(2, listOf(SpreadsheetCell("Alice"), SpreadsheetCell("Bob"))),
            ),
        )

        val headerVariables = MessageTemplate.variables(document, headerLine = 1)
        val noHeaderVariables = MessageTemplate.variables(document, headerLine = null)

        assertFalse(headerVariables.any { it.name == "NOM" })
        assertEquals(setOf("A", "B"), noHeaderVariables.map { it.name }.toSet())
    }

    @Test
    fun returnsTemplateTokensInOrderOfAppearanceAndLeavesUnknownValuesVisible() {
        assertEquals(setOf("NOM", "CLASSE"), MessageTemplate.tokens("#Nom - #CLASSE #Nom"))
        assertEquals("Bonjour #NOM", MessageTemplate.render("Bonjour #NOM", emptyMap()))
    }
}

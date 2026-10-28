package com.example.controlprestamos.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ExcelBackupTest {
    @Test fun workbookPreservesAmountsAndLiteralTextWithoutFormulas() {
        val output=ByteArrayOutputStream()
        ExcelBackup.write(output,listOf(ExcelBackup.Sheet("Cuotas y pagos",listOf(
            listOf("Cliente","Importe","Nota"),listOf("José & <Ana>",BigDecimal("123.45"),"=HYPERLINK(\"ejemplo\")")))))
        val entries=mutableMapOf<String,ByteArray>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while(true){val entry=zip.nextEntry?:break;entries[entry.name]=zip.readBytes()}
        }
        entries.values.forEach{DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(it))}
        val worksheet=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(entries.getValue("xl/worksheets/sheet1.xml")))
        assertEquals("123.45",worksheet.getElementsByTagName("v").item(0).textContent)
        assertTrue(worksheet.documentElement.textContent.contains("José & <Ana>"))
        assertTrue(worksheet.documentElement.textContent.contains("=HYPERLINK(\"ejemplo\")"))
        assertEquals(0,worksheet.getElementsByTagName("f").length)
        assertTrue(entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("xl/_rels/workbook.xml.rels"))
    }
}

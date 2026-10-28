package com.example.controlprestamos.data

import java.io.OutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small, dependency-free OOXML writer. Text is always literal, never a formula. */
internal object ExcelBackup {
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    data class Sheet(val name: String, val rows: List<List<Any?>>)

    fun write(output: OutputStream, sheets: List<Sheet>) {
        require(sheets.isNotEmpty())
        require(sheets.map { it.name }.distinct().size == sheets.size)
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>${sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" }}</Types>""")
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            entry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>${sheets.mapIndexed { index, sheet -> "<sheet name=\"${xml(sheet.name)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>" }.joinToString("")}</sheets></workbook>""")
            entry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" }}</Relationships>""")
            sheets.forEachIndexed { index, sheet ->
                require(sheet.rows.size <= 1_048_576)
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet${index + 1}.xml"))
                val writer = zip.writer(Charsets.UTF_8)
                writer.write("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><sheetData>""")
                sheet.rows.forEachIndexed { rowIndex, row ->
                    writer.write("<row r=\"${rowIndex + 1}\">")
                    row.forEachIndexed { col, value ->
                        val address = "${column(col + 1)}${rowIndex + 1}"
                        if (value is Number) {
                            writer.write("<c r=\"$address\"><v>${BigDecimal(value.toString()).toPlainString()}</v></c>")
                        } else {
                            val text = value?.toString().orEmpty()
                            require(text.length <= 32767) { "Texto demasiado largo para una celda de Excel" }
                            writer.write("<c r=\"$address\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(text)}</t></is></c>")
                        }
                    }
                    writer.write("</row>")
                }
                writer.write("</sheetData></worksheet>"); writer.flush(); zip.closeEntry()
            }
        }
    }
    private fun column(index: Int): String {
        var n=index; var result=""
        while(n>0){n--;result=('A'+n%26)+result;n/=26}
        return result
    }
    private fun xml(text: String) = buildString {
        text.forEach { char -> when(char) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&apos;")
            else -> if(char=='\t' || char=='\n' || char=='\r' || char >= ' ' && char != '\uFFFE' && char != '\uFFFF') append(char)
        } }
    }
}

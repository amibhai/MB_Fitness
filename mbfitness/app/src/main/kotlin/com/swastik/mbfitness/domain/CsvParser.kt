package com.swastik.mbfitness.domain

data class CsvRecord(val line: Int, val fields: List<String>)

/** RFC-4180: quoted fields, doubled quotes, commas and newlines inside quotes, any line ending. */
object CsvParser {
    private const val BOM = '\uFEFF'

    fun parse(text: String): List<CsvRecord> {
        val input = text.removePrefix(BOM.toString())
        val records = mutableListOf<CsvRecord>()
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var line = 1
        var recordLine = 1
        var index = 0

        fun endField() {
            fields += field.toString()
            field.clear()
        }

        fun endRecord() {
            endField()
            val isBlank = fields.size == 1 && fields[0].isBlank()
            if (!isBlank) records += CsvRecord(recordLine, fields.toList())
            fields.clear()
            recordLine = line
        }

        while (index < input.length) {
            val char = input[index]
            when {
                inQuotes && char == '"' && input.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> inQuotes = !inQuotes
                inQuotes -> {
                    if (char == '\n') line++
                    field.append(char)
                }
                char == ',' -> endField()
                char == '\r' || char == '\n' -> {
                    if (char == '\r' && input.getOrNull(index + 1) == '\n') index++
                    line++
                    endRecord()
                }
                else -> field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || fields.isNotEmpty()) endRecord()
        return records
    }
}

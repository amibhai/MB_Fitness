package com.swastik.mbfitness.domain

data class ImportRow(
    val line: Int,
    val name: String,
    val phone: String,
    val planMonths: Int,
    val expiry: Long,
    val balanceDue: Long,
    val dob: Long?,
    val notes: String,
    val aadhaar: Aadhaar?,
)

data class ImportError(val line: Int, val name: String, val reasons: List<String>)

data class ImportDuplicate(val line: Int, val name: String, val phone: String, val existingName: String?)

data class ImportPreview(
    val rows: List<ImportRow>,
    val errors: List<ImportError>,
    val duplicates: List<ImportDuplicate>,
    val hasAadhaarColumn: Boolean,
    val fileProblem: String?,
)

/** The member import screen's rules (SPEC 7.9). Nothing here touches the database. */
object MemberImport {
    const val SAMPLE_FILE_NAME = "mbfitness_import_sample.csv"

    /** The Aadhaar column is blank on purpose, so the file never models a real number. */
    const val SAMPLE =
        "name,phone,plan_months,expiry_date,balance_due,dob,notes,aadhaar\r\n" +
            "Sample Member One,9000000001,1,31-12-2026,0,15-08-1995,Morning batch,\r\n" +
            "\"Member, Sample Two\",9000000002,3,15-01-2027,700,,,\r\n"

    const val PHONE_ERROR = "Number must be 10 digits"
    const val AADHAAR_ERROR = "Aadhaar number is not valid. Please check the digits."
    const val AADHAAR_EXCEL_ERROR =
        "Excel damaged this Aadhaar number. Format the column as Text, retype it and export again."
    const val PHONE_EXCEL_ERROR =
        "Excel damaged this phone number. Format the column as Text, retype it and export again."

    private val REQUIRED = listOf("name", "phone", "plan_months", "expiry_date")
    private val SCIENTIFIC = Regex("""^\d+(\.\d+)?[eE]\+?\d+$""")
    private val RUPEES = Regex("""^(?:₹|Rs\.?)?\s*([\d,]+)(?:\.0+)?$""", RegexOption.IGNORE_CASE)
    private const val MIN_PLAN_MONTHS = 1
    private const val MAX_PLAN_MONTHS = 24

    /**
     * Checks every row. [useAadhaar] reflects bhaiya's separate tick: without it the column is
     * ignored completely, so a bad value there can't block the import (SPEC 7.9). [planMonths]
     * lists the active plan lengths: a row needing another one is refused rather than given an
     * invented price.
     */
    fun preview(
        text: String,
        existingPhones: Map<String, String>,
        useAadhaar: Boolean,
        today: Long,
        planMonths: Set<Int>? = null,
    ): ImportPreview {
        val records = CsvParser.parse(text)
        val header = records.firstOrNull()?.fields?.map(::headerName).orEmpty()
        val missing = REQUIRED.filter { it !in header }
        if (records.isEmpty() || missing.isNotEmpty()) {
            val problem = if (records.isEmpty()) "This file is empty." else "Missing columns: ${missing.joinToString(", ")}"
            return ImportPreview(emptyList(), emptyList(), emptyList(), "aadhaar" in header, problem)
        }

        val rows = mutableListOf<ImportRow>()
        val errors = mutableListOf<ImportError>()
        val duplicates = mutableListOf<ImportDuplicate>()
        val phonesInFile = mutableMapOf<String, String>()
        val aadhaarInFile = mutableMapOf<String, Int>()

        for (record in records.drop(1)) {
            fun cell(name: String): String =
                header.indexOf(name).takeIf { it >= 0 }?.let { record.fields.getOrNull(it) }?.trim().orEmpty()

            val name = cell("name")
            val reasons = mutableListOf<String>()
            if (name.isEmpty()) reasons += "Name is missing"

            val rawPhone = cell("phone")
            val phone = PhoneNumbers.normalize(rawPhone)
            if (phone == null) reasons += if (SCIENTIFIC.matches(rawPhone)) PHONE_EXCEL_ERROR else PHONE_ERROR

            val months = cell("plan_months").toIntOrNull()?.takeIf { it in MIN_PLAN_MONTHS..MAX_PLAN_MONTHS }
            if (months == null) {
                reasons += "Plan must be 1 to 24 months"
            } else if (planMonths != null && months !in planMonths) {
                reasons += "No $months-month plan. Add it in Plans first."
            }

            val expiry = Dates.parseCsvDate(cell("expiry_date"))
            if (expiry == null) reasons += "Expiry date must be DD-MM-YYYY"

            val balance = parseRupees(cell("balance_due"))
            if (balance == null) reasons += "Balance must be whole rupees"

            val rawDob = cell("dob")
            val dob = if (rawDob.isEmpty()) null else Dates.parseCsvDate(rawDob)
            if (rawDob.isNotEmpty() && (dob == null || dob > today)) reasons += "Date of birth must be DD-MM-YYYY"

            var aadhaar: Aadhaar? = null
            val rawAadhaar = cell("aadhaar")
            if (useAadhaar && rawAadhaar.isNotEmpty()) {
                when (val parsed = Aadhaar.parse(rawAadhaar)) {
                    is Aadhaar.ParseResult.Valid -> {
                        aadhaar = parsed.aadhaar
                        val firstLine = aadhaarInFile.putIfAbsent(parsed.aadhaar.revealDigits(), record.line)
                        if (firstLine != null) reasons += "Same Aadhaar number as line $firstLine"
                    }
                    Aadhaar.ParseResult.DamagedByExcel -> reasons += AADHAAR_EXCEL_ERROR
                    Aadhaar.ParseResult.Invalid -> reasons += AADHAAR_ERROR
                }
            }

            when {
                reasons.isNotEmpty() -> errors += ImportError(record.line, name, reasons)
                phone != null && phone in existingPhones ->
                    duplicates += ImportDuplicate(record.line, name, phone, existingPhones[phone])
                phone != null && phone in phonesInFile ->
                    duplicates += ImportDuplicate(record.line, name, phone, phonesInFile[phone])
                else -> {
                    phonesInFile[phone!!] = name
                    rows += ImportRow(record.line, name, phone, months!!, expiry!!, balance!!, dob, cell("notes"), aadhaar)
                }
            }
        }
        return ImportPreview(rows, errors, duplicates, "aadhaar" in header, null)
    }

    /** The spec writes the header as "expiry_date(DD-MM-YYYY)", so that part is dropped. */
    private fun headerName(raw: String): String =
        raw.trim().lowercase().substringBefore('(').trim().replace(' ', '_')

    private fun parseRupees(text: String): Long? {
        if (text.isEmpty()) return 0
        val digits = RUPEES.find(text)?.groupValues?.get(1) ?: return null
        return digits.replace(",", "").toLongOrNull()
    }
}

package com.swastik.mbfitness.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvAndImportTest {
    private val today = Dates.of(2026, 11, 5)
    private val header = "name,phone,plan_months,expiry_date,balance_due,dob,notes,aadhaar\n"
    private val aadhaarOk = "99990000001".let { it + Aadhaar.checkDigitFor(it) }

    private fun fields(text: String) = CsvParser.parse(text).map { it.fields }

    private fun preview(rows: String, existing: Map<String, String> = emptyMap(), useAadhaar: Boolean = false) =
        MemberImport.preview(header + rows, existing, useAadhaar, today)

    @Test
    fun parsesPlainRows() {
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), fields("a,b\nc,d\n"))
    }

    @Test
    fun handlesEveryLineEnding() {
        val expected = listOf(listOf("a"), listOf("b"), listOf("c"))
        assertEquals(expected, fields("a\r\nb\r\nc\r\n"))
        assertEquals(expected, fields("a\rb\rc"))
        assertEquals(expected, fields("a\nb\nc"))
    }

    @Test
    fun handlesQuotesCommasAndNewlinesInsideFields() {
        assertEquals(listOf(listOf("Kumar, Anil", "x")), fields("\"Kumar, Anil\",x"))
        assertEquals(listOf(listOf("say \"hi\"", "x")), fields("\"say \"\"hi\"\"\",x"))
        assertEquals(listOf(listOf("two\nlines", "x")), fields("\"two\nlines\",x"))
    }

    @Test
    fun ignoresAByteOrderMarkFromExcel() {
        assertEquals(listOf(listOf("name", "phone")), fields("﻿name,phone\n"))
    }

    @Test
    fun keepsEmptyFieldsAndSkipsBlankLines() {
        assertEquals(listOf(listOf("a", "", "c"), listOf("d", "")), fields("a,,c\n\n   \nd,\n"))
    }

    @Test
    fun reportsTheLineARecordStartsOn() {
        val records = CsvParser.parse("a\n\"two\nlines\"\nc\n")
        assertEquals(listOf(1, 2, 4), records.map { it.line })
    }

    @Test
    fun theSampleFileImportsCleanly() {
        val result = MemberImport.preview(MemberImport.SAMPLE, emptyMap(), useAadhaar = true, today = today)
        assertNull(result.fileProblem)
        assertTrue(result.errors.isEmpty())
        assertEquals(2, result.rows.size)
        assertEquals("Member, Sample Two", result.rows[1].name)
        assertEquals(700L, result.rows[1].balanceDue)
        assertTrue(result.hasAadhaarColumn)
        assertTrue(result.rows.all { it.aadhaar == null })
    }

    @Test
    fun readsAGoodRow() {
        val row = preview("Rahul Sharma,98765 43210,3,31-12-2026,700,15-08-1995,Morning,\n").rows.single()
        assertEquals("Rahul Sharma", row.name)
        assertEquals("+919876543210", row.phone)
        assertEquals(3, row.planMonths)
        assertEquals(Dates.of(2026, 12, 31), row.expiry)
        assertEquals(700L, row.balanceDue)
        assertEquals(Dates.of(1995, 8, 15), row.dob)
        assertEquals("Morning", row.notes)
    }

    @Test
    fun acceptsTheHeaderExactlyAsTheSpecWritesIt() {
        val text = "name,phone,plan_months,expiry_date(DD-MM-YYYY),balance_due,\"dob(DD-MM-YYYY, optional)\",notes\n" +
            "Rahul,9876543210,1,31-12-2026,0,15-08-1995,\n"
        val result = MemberImport.preview(text, emptyMap(), false, today)
        assertEquals(1, result.rows.size)
        assertEquals(Dates.of(1995, 8, 15), result.rows[0].dob)
        assertFalse(result.hasAadhaarColumn)
    }

    @Test
    fun aMissingColumnStopsTheWholeFile() {
        val result = MemberImport.preview("name,phone\nRahul,9876543210\n", emptyMap(), false, today)
        assertEquals("Missing columns: plan_months, expiry_date", result.fileProblem)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun anEmptyFileSaysSo() {
        assertEquals("This file is empty.", MemberImport.preview("", emptyMap(), false, today).fileProblem)
    }

    @Test
    fun aBadRowListsEveryReasonAndKeepsItsLineNumber() {
        val result = preview("A,9876543210,1,31-12-2026,0,,,\n,123,99,soon,abc,1995,,\n")
        assertEquals(1, result.rows.size)
        val error = result.errors.single()
        assertEquals(3, error.line)
        assertEquals(
            listOf(
                "Name is missing",
                MemberImport.PHONE_ERROR,
                "Plan must be 1 to 24 months",
                "Expiry date must be DD-MM-YYYY",
                "Balance must be whole rupees",
                "Date of birth must be DD-MM-YYYY",
            ),
            error.reasons,
        )
    }

    @Test
    fun acceptsTheWaysPeopleWriteMoney() {
        fun balanceOf(cell: String) = preview("A,9876543210,1,31-12-2026,$cell,,,\n").rows.single().balanceDue
        assertEquals(0L, balanceOf(""))
        assertEquals(1200L, balanceOf("1200"))
        assertEquals(1200L, balanceOf("\"1,200\""))
        assertEquals(1200L, balanceOf("₹1200"))
        assertEquals(1200L, balanceOf("Rs 1200"))
        assertEquals(1200L, balanceOf("1200.00"))
    }

    @Test
    fun refusesMoneyThatIsntWholeRupees() {
        listOf("12.50", "-100", "free").forEach {
            assertEquals(it, 1, preview("A,9876543210,1,31-12-2026,$it,,,\n").errors.size)
        }
    }

    @Test
    fun aBirthDateInTheFutureIsRefused() {
        assertEquals(1, preview("A,9876543210,1,31-12-2026,0,15-08-2030,,\n").errors.size)
    }

    @Test
    fun duplicatesAreListedAndSkipped() {
        val existing = mapOf("+919876543210" to "Old Rahul")
        val result = preview(
            "Rahul,9876543210,1,31-12-2026,0,,,\nAnil,9123456780,1,31-12-2026,0,,,\nAnil Again,09123456780,1,31-12-2026,0,,,\n",
            existing,
        )
        assertEquals(listOf("Anil"), result.rows.map { it.name })
        assertEquals(
            listOf(
                ImportDuplicate(2, "Rahul", "+919876543210", "Old Rahul"),
                ImportDuplicate(4, "Anil Again", "+919123456780", "Anil"),
            ),
            result.duplicates,
        )
    }

    @Test
    fun explainsWhenExcelHasDamagedAPhoneNumber() {
        val error = preview("A,9.87654E+09,1,31-12-2026,0,,,\n").errors.single()
        assertEquals(listOf(MemberImport.PHONE_EXCEL_ERROR), error.reasons)
    }

    @Test
    fun theAadhaarColumnIsIgnoredWithoutItsOwnTick() {
        val result = preview("A,9876543210,1,31-12-2026,0,,,not-a-number\n", useAadhaar = false)
        assertTrue(result.errors.isEmpty())
        assertNull(result.rows.single().aadhaar)
    }

    @Test
    fun theAadhaarColumnIsReadWithItsTick() {
        val row = preview("A,9876543210,1,31-12-2026,0,,,$aadhaarOk\n", useAadhaar = true).rows.single()
        assertNotNull(row.aadhaar)
        assertEquals(aadhaarOk, row.aadhaar!!.revealDigits())
    }

    @Test
    fun aBlankAadhaarIsFineEvenWithTheTick() {
        assertNull(preview("A,9876543210,1,31-12-2026,0,,,\n", useAadhaar = true).rows.single().aadhaar)
    }

    @Test
    fun aBadAadhaarIsRefusedAndTheMessageNeverEchoesTheDigits() {
        val typo = aadhaarOk.dropLast(1) + ((aadhaarOk.last() - '0' + 1) % 10)
        val error = preview("A,9876543210,1,31-12-2026,0,,,$typo\n", useAadhaar = true).errors.single()
        assertEquals(listOf(MemberImport.AADHAAR_ERROR), error.reasons)
        assertFalse(typo in error.toString())
    }

    @Test
    fun explainsWhenExcelHasDamagedAnAadhaarNumber() {
        val error = preview("A,9876543210,1,31-12-2026,0,,,1.23457E+11\n", useAadhaar = true).errors.single()
        assertEquals(listOf(MemberImport.AADHAAR_EXCEL_ERROR), error.reasons)
    }

    @Test
    fun theSameAadhaarTwiceInOneFileIsRefused() {
        val result = preview(
            "A,9876543210,1,31-12-2026,0,,,$aadhaarOk\nB,9123456780,1,31-12-2026,0,,,${aadhaarOk.chunked(4).joinToString(" ")}\n",
            useAadhaar = true,
        )
        assertEquals(1, result.rows.size)
        assertEquals(listOf("Same Aadhaar number as line 2"), result.errors.single().reasons)
    }

    @Test
    fun aShortRowJustHasBlankCells() {
        val row = preview("A,9876543210,1,31-12-2026\n").rows.single()
        assertEquals(0L, row.balanceDue)
        assertEquals("", row.notes)
    }
}

package com.swastik.mbfitness.data

import com.swastik.mbfitness.domain.TodayProvider

/**
 * The local error log, newest 200 (SPEC 6). It stays on the phone. Never put a member's Aadhaar
 * number in a message here (hard rule 11).
 */
class ErrorLogger(private val db: AppDatabase, private val clock: TodayProvider) {
    suspend fun log(tag: String, message: String) {
        db.errorLog().insert(ErrorLogEntity(at = clock.nowMillis(), tag = tag, message = message))
        db.errorLog().trim(KEEP)
    }

    private companion object {
        const val KEEP = 200
    }
}

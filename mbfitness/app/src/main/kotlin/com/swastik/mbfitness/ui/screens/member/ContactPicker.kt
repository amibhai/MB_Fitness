package com.swastik.mbfitness.ui.screens.member

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.result.contract.ActivityResultContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The system contact picker. It hands back one contact with a temporary grant to read it, so
 * the app needs no contacts permission (SPEC 7.9).
 */
class PickPhoneContact : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

data class PickedContact(val name: String, val phone: String)

/** Null if the contact can't be read, which simply leaves the form as it was. */
suspend fun readPickedContact(context: Context, uri: Uri): PickedContact? = withContext(Dispatchers.IO) {
    val columns = arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
    )
    try {
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) PickedContact(cursor.getString(0).orEmpty(), cursor.getString(1).orEmpty()) else null
        }
    } catch (_: SecurityException) {
        null
    }
}

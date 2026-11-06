package com.swastik.mbfitness.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

/**
 * A folder picked through Android's file picker, so the app needs no storage permission. Works
 * with whatever provider the folder belongs to, using only built-in APIs.
 */
class SafBackupFolder(private val context: Context, private val treeUri: Uri) : BackupFolder {
    private val resolver get() = context.contentResolver

    private val folder: Uri
        get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    /** The permission can be taken away, and a folder can be deleted, so this really checks. */
    override fun isUsable(): Boolean {
        val allowed = resolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }
        if (!allowed) return false
        return try {
            children()
            true
        } catch (_: RuntimeException) {
            false
        } catch (_: IOException) {
            false
        }
    }

    override fun names(): List<String> = children().map { it.first }

    override fun read(name: String): ByteArray? {
        val target = children().firstOrNull { it.first == name }?.second ?: return null
        return resolver.openInputStream(target)?.use { it.readBytes() }
    }

    override fun write(name: String, bytes: ByteArray) {
        val target = children().firstOrNull { it.first == name }?.second
            ?: DocumentsContract.createDocument(resolver, folder, MIME_TYPE, name)
            ?: throw IOException("Could not create the file")
        val stream = resolver.openOutputStream(target, "wt") ?: throw IOException("Could not open the file")
        stream.use { it.write(bytes) }
    }

    override fun delete(name: String) {
        children().firstOrNull { it.first == name }?.let { DocumentsContract.deleteDocument(resolver, it.second) }
    }

    private fun children(): List<Pair<String, Uri>> {
        val list = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val cursor = resolver.query(list, columns, null, null, null) ?: throw IOException("Could not read the folder")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(it.getString(0) to DocumentsContract.buildDocumentUriUsingTree(treeUri, it.getString(1)))
                }
            }
        }
    }

    companion object {
        private const val MIME_TYPE = "application/json"

        /** Asks to keep access after a restart. Without this, weekly backups would stop working. */
        fun keepAccess(context: Context, uri: Uri) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }
}

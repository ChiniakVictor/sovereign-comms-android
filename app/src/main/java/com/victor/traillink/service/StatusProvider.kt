package com.victor.traillink.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Tiny read-only signal for other apps (Signal Scanner's Auto Location):
 *   content://com.victor.traillink.status/sharing  ->  one row: sharing (1/0), since (ms epoch)
 * No positions or keys are exposed.
 */
class StatusProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
        MatrixCursor(arrayOf("sharing", "since")).apply { addRow(arrayOf<Any>(if (LinkService.sharingActive) 1 else 0, LinkService.sharingSince)) }
    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.traillink.status"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

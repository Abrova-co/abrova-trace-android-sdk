package ir.abrova.trace.sdk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import ir.abrova.trace.sdk.utils.AbrovaTraceLogger

/**
 * ContentProvider for automatic SDK context initialization
 *
 * This provider is automatically instantiated before Application.onCreate()
 * and provides the application context to the SDK.
 *
 * Note: This does NOT automatically initialize the SDK - you still need to call
 * AbrovaTrace.init() with your configuration. This provider just ensures the
 * application context is available if needed for early initialization.
 */
class AbrovaTraceInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        AbrovaTraceLogger.debug("AbrovaTraceInitProvider created")
        // We don't auto-initialize here - the user should call AbrovaTrace.init()
        // This provider is mainly for future use cases where we might need
        // early context access
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}

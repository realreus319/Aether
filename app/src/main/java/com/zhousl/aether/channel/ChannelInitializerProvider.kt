package com.zhousl.aether.channel

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.zhousl.aether.aetherRuntime

/** Starts the channel foreground host only when a channel config exists. */
class ChannelInitializerProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return true
        val configFile = appContext.aetherRuntime.alpineRuntime.resolveManagedGuestPath(
            "/root/.aether/channels.json"
        )
        if (configFile.isFile && configFile.length() > 0L) {
            AetherChannelService.ensureRunning(appContext)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

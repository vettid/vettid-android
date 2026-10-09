package com.vettid.feature.history

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject

/**
 * Where a History export is written (VAULT-MESSAGING 0.22.0 §10.9, ANDROID-PLAN 0.1.17): only the document the member
 * chose in the system's "Save to…" dialog (Storage Access Framework), through its stream. Never a copy in the cache,
 * shared storage or the clipboard, never a share sheet.
 */
interface ExportDocuments {
    /** Writes the document [uri] (a `content://` URI the dialog returned) from the start, replacing what it held. */
    @Throws(IOException::class)
    fun write(uri: String, block: (OutputStream) -> Unit)

    /** Removes a document a failed write left behind (best effort). */
    fun discard(uri: String)
}

/** [ExportDocuments] over the content resolver. */
class ContentResolverDocuments @Inject constructor(@param:ApplicationContext private val context: Context) : ExportDocuments {
    override fun write(uri: String, block: (OutputStream) -> Unit) {
        val out = context.contentResolver.openOutputStream(Uri.parse(uri), "wt") ?: throw IOException("no stream for the document")
        out.use(block)
    }

    override fun discard(uri: String) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(uri)) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class HistoryExportModule {
    @Binds
    abstract fun exportDocuments(d: ContentResolverDocuments): ExportDocuments
}

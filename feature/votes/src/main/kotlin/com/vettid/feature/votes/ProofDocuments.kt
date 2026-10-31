package com.vettid.feature.votes

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
 * Where a vote's proof is written (ANDROID-PLAN 0.1.108 §4 "Votes", screen 6): only the document the member chose in the
 * system's "Save to…" dialog (Storage Access Framework), through its stream, and only on their **Save the proof**. Never a
 * copy in the cache or shared storage, never a share sheet or its preview, never a log. The file holds the member's
 * `choice_id`: it is shown to nobody else.
 */
interface ProofDocuments {
    /** Writes the document [uri] (a `content://` URI the dialog returned) from the start, replacing what it held. */
    @Throws(IOException::class)
    fun write(uri: String, block: (OutputStream) -> Unit)

    /** Removes a document a failed write left behind (best effort). */
    fun discard(uri: String)
}

/** [ProofDocuments] over the content resolver. */
class ContentResolverProofDocuments @Inject constructor(@param:ApplicationContext private val context: Context) : ProofDocuments {
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
internal abstract class VoteProofModule {
    @Binds
    abstract fun proofDocuments(d: ContentResolverProofDocuments): ProofDocuments
}

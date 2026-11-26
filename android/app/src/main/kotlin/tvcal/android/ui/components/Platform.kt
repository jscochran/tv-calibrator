package tvcal.android.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.core.content.FileProvider
import java.io.File

// Small platform helpers: links (Swift Link / openURL), sharing (ShareLink, UIActivityViewController),
// keeping the screen on (isIdleTimerDisabled) and the markdown bold that Swift Text(.init(...)) renders.

/** Swift `Link(destination:)`: opens an https URL in the browser or the app that owns it (YouTube). */
fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}

/** The FileProvider authority declared in the manifest (res/xml/file_paths.xml shares cache/shared/). */
fun fileProviderAuthority(context: Context): String = "${context.packageName}.files"

/** cacheDir/shared, created if missing: put files to share here (the export JSON, the results image). */
fun shareDir(context: Context): File = File(context.cacheDir, "shared").apply { mkdirs() }

/**
 * Shares a file from [shareDir] with the system share sheet (ACTION_SEND through the FileProvider).
 * mimeType: "application/json" for the export, "image/png" for the results image.
 */
fun shareFile(context: Context, file: File, mimeType: String, title: String? = null) {
    val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        if (title != null) putExtra(Intent.EXTRA_SUBJECT, title)
        if (title != null) putExtra(Intent.EXTRA_TITLE, title)
        // The Sharesheet's preview and title (Android 10+).
        clipData = ClipData.newUri(context.contentResolver, title ?: file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
    }
}

/**
 * Keeps the screen on while this composable is on screen (FLAG_KEEP_SCREEN_ON through the view; Swift
 * `UIApplication.shared.isIdleTimerDisabled`). The camera meter (P4) keeps it on by itself while running.
 */
@Composable
fun KeepScreenOn(enabled: Boolean = true) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        if (enabled) view.keepScreenOn = true
        onDispose { if (enabled) view.keepScreenOn = false }
    }
}

/** `**bold**` spans drawn bold (what Swift's markdown Text does for the app's tips); the rest as is. */
fun boldMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    var bold = false
    while (i < text.length) {
        val j = text.indexOf("**", i)
        val end = if (j < 0) text.length else j
        val part = text.substring(i, end)
        if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
        if (j < 0) break
        bold = !bold
        i = j + 2
    }
}

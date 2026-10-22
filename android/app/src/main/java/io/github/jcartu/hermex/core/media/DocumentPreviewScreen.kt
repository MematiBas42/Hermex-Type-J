package io.github.jcartu.hermex.core.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import coil.compose.AsyncImage
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.feature.workspace.SourceViewerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App-level host for requests emitted by `DocumentPreview.open(context, …)`. */
@Composable
fun DocumentPreviewHost() {
    val request by DocumentPreview.request.collectAsState()
    request?.let { active ->
        Dialog(
            onDismissRequest = { DocumentPreview.dismiss(active.id) },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            DocumentPreviewScreen(
                request = active,
                onClose = { DocumentPreview.dismiss(active.id) },
            )
        }
    }
}

/**
 * In-app document surface: framework PdfRenderer, zoomable full-bleed images,
 * and the same source viewer used by workspace files.
 */
@Composable
fun DocumentPreviewScreen(
    request: DocumentPreviewRequest,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)
    val imageMode = request.route == DocumentPreviewRoute.IMAGE
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (imageMode) Color.Black else MaterialTheme.colorScheme.background),
    ) {
        when (request.route) {
            DocumentPreviewRoute.PDF -> PdfDocumentPreview(request, Modifier.fillMaxSize().padding(top = 56.dp))
            DocumentPreviewRoute.IMAGE -> ZoomableDocumentImage(request, Modifier.fillMaxSize())
            DocumentPreviewRoute.TEXT -> {
                val content = remember(request.id) {
                    runCatching { request.file.readText(Charsets.UTF_8) }.getOrDefault("")
                }
                SourceViewerScreen(
                    content = content,
                    path = request.displayName,
                    modifier = Modifier.fillMaxSize().padding(top = 56.dp),
                )
            }
            DocumentPreviewRoute.EXTERNAL -> ErrorState(
                message = "This file opens in another installed app.",
                modifier = Modifier.fillMaxSize(),
            )
        }
        PreviewChrome(
            title = request.displayName,
            dark = imageMode,
            onClose = onClose,
            onShare = { shareStagedFile(context, request) },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun PreviewChrome(
    title: String,
    dark: Boolean,
    onClose: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (dark) Color.Black.copy(alpha = 0.72f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, "Close preview", tint = if (dark) Color.White else MaterialTheme.colorScheme.onSurface)
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = if (dark) Color.White else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        IconButton(onClick = onShare) {
            Icon(Icons.Filled.Share, "Share document", tint = if (dark) Color.White else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun PdfDocumentPreview(request: DocumentPreviewRequest, modifier: Modifier = Modifier) {
    val pageCount by produceState<Int?>(initialValue = null, request.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(request.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { it.pageCount }
                }
            }.getOrElse { -1 }
        }
    }
    when (val count = pageCount) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        -1 -> ErrorState("Could not render this PDF.", modifier = modifier)
        0 -> ErrorState("This PDF has no pages.", modifier = modifier)
        else -> LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items((0 until count).toList(), key = { it }) { page ->
                PdfPage(request.file, page, Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            }
        }
    }
}

@Composable
private fun PdfPage(file: java.io.File, pageIndex: Int, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, file, pageIndex) {
        value = withContext(Dispatchers.IO) { renderPdfPage(file, pageIndex) }
    }
    val rendered = bitmap
    if (rendered == null) {
        Box(modifier.padding(vertical = 48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        androidx.compose.foundation.Image(
            bitmap = rendered.asImageBitmap(),
            contentDescription = "PDF page ${pageIndex + 1}",
            modifier = modifier.background(Color.White),
            contentScale = ContentScale.FillWidth,
        )
        DisposableEffect(rendered) { onDispose { rendered.recycle() } }
    }
}

private fun renderPdfPage(file: java.io.File, pageIndex: Int): Bitmap? = runCatching {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(pageIndex).use { page ->
                val scale = (1_800f / page.width.coerceAtLeast(1)).coerceIn(1f, 2.5f)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                createBitmap(width, height).also { bitmap ->
                    bitmap.eraseColor(AndroidColor.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }
}.getOrNull()

@Composable
private fun ZoomableDocumentImage(request: DocumentPreviewRequest, modifier: Modifier = Modifier) {
    var scale by remember(request.id) { mutableFloatStateOf(1f) }
    var offset by remember(request.id) { mutableStateOf(Offset.Zero) }
    var chromeReset by remember(request.id) { mutableStateOf(false) }
    LaunchedEffect(chromeReset) {
        if (chromeReset) {
            scale = 1f
            offset = Offset.Zero
            chromeReset = false
        }
    }
    AsyncImage(
        model = request.file,
        contentDescription = request.displayName,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .pointerInput(request.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 8f)
                    scale = nextScale
                    offset = if (nextScale == 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(request.id) {
                detectTapGestures(onDoubleTap = { chromeReset = true })
            },
    )
}

private fun shareStagedFile(context: Context, request: DocumentPreviewRequest) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", request.file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(request.mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share ${request.displayName}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

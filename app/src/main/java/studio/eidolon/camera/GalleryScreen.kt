package studio.eidolon.camera

import android.content.ClipData
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Date

@Composable internal fun GalleryScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { GalleryStore(context) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var photos by remember { mutableStateOf<List<GalleryPhoto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf<GalleryPhoto?>(null) }
    var deletingNow by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(revision) {
        if (!loading) delay(160)
        galleryResult { store.photos() }.onSuccess {
            photos = it; error = null
            if (selected != null && it.none { photo -> photo.id == selected }) selected = null
        }
            .onFailure { error = "Couldn’t load your photos. Try again." }
        loading = false
    }
    DisposableEffect(store) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { revision++ }
        }
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer); store.clearThumbnails() }
    }
    BackHandler { if (selected != null) selected = null else onClose() }
    if (selected != null && photos.any { it.id == selected }) {
        PhotoViewer(photos, selected!!, onSelect = { selected = it }, onClose = { selected = null },
            onShare = { photo, raw ->
                val uri = Uri.parse(if (raw) photo.rawUri ?: photo.uri else photo.uri)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = if (raw) "image/x-adobe-dng" else "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("Eidolon photo", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { context.startActivity(Intent.createChooser(intent, "Share photo")) }
                    .onFailure { error = "No app is available to share this photo." }
            }, onDelete = { deleting = it }, onInfo = { photo -> scope.launch {
                details = galleryResult { store.details(photo) }.getOrElse { "Photo information is unavailable." }
            } }, image = { photo, active -> ViewerImage(photo, active, store) })
    } else {
        GalleryInterface(photos, loading, error, onClose, onRetry = { loading = true; revision++ }, onPhoto = { selected = it.id }) { photo ->
            GalleryThumbnail(photo, store)
        }
    }
    if (deleting != null) AlertDialog(onDismissRequest = { if (!deletingNow) deleting = null },
        containerColor = SurfaceInk, title = { Text("Delete this moment?") },
        text = { Text(if (deleting!!.rawUri != null) "The JPEG and its RAW original will be permanently deleted." else "This photo will be permanently deleted.") },
        confirmButton = { TextButton(enabled = !deletingNow, onClick = { val photo = deleting!!; deletingNow = true; scope.launch {
            galleryResult { store.delete(photo) }.onSuccess {
                photos = photos.filterNot { it.id == photo.id }; selected = null; revision++
            }.onFailure { error = "Couldn’t delete this photo. It may no longer be available."; revision++ }
            deleting = null; deletingNow = false
        } }) { Text(if (deletingNow) "Deleting…" else "Delete", color = Peach) } },
        dismissButton = { TextButton(enabled = !deletingNow, onClick = { deleting = null }) { Text("Keep") } })
    if (details != null) AlertDialog(onDismissRequest = { details = null }, containerColor = SurfaceInk,
        title = { Text("Photo details") }, text = { Text(details!!, lineHeight = 24.sp) },
        confirmButton = { TextButton(onClick = { details = null }) { Text("Done") } })
    if (error != null && selected != null) AlertDialog(onDismissRequest = { error = null }, containerColor = SurfaceInk,
        text = { Text(error!!) }, confirmButton = { TextButton(onClick = { error = null }) { Text("Done") } })
}

@Composable internal fun GalleryInterface(photos: List<GalleryPhoto>, loading: Boolean = false, error: String? = null,
    onClose: () -> Unit = {}, onRetry: () -> Unit = {}, onPhoto: (GalleryPhoto) -> Unit = {},
    thumbnail: @Composable (GalleryPhoto) -> Unit = {}) {
    var filter by rememberSaveable { mutableStateOf("All") }
    val visible = remember(photos, filter) { photos.filter { filter == "All" || it.bloom == (filter == "Bloom") } }
    Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Your moments", color = Paper, fontSize = 26.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.glass(CircleShape)) { Glyph("close", Paper, Modifier.semantics { contentDescription = "Back to camera" }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("All", "Pure", "Bloom").forEach { name ->
                FilterChip(selected = filter == name, onClick = { filter = name }, label = { Text(name) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = if (name == "Bloom") BloomViolet else Paper,
                        selectedLabelColor = Ink))
            }
            Spacer(Modifier.weight(1f))
            MicroText("${visible.size}", Muted)
        }
        if (loading || error != null || visible.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (loading) CircularProgressIndicator(color = Paper, modifier = Modifier.size(24.dp))
                else {
                    Glyph("gallery", Muted, Modifier.size(48.dp))
                    Text(if (error != null) "A moment, please." else "Make a memory.", color = Paper, fontSize = 26.sp, modifier = Modifier.padding(top = 24.dp))
                    Text(error ?: "Your ${if (filter == "All") "Eidolon" else filter} photos will appear here.", color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp))
                    TextButton(onClick = if (error != null) onRetry else onClose) { Text(if (error != null) "Try again" else "Open camera", color = Paper) }
                }
            }
        } else LazyVerticalGrid(columns = GridCells.Adaptive(112.dp), modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(visible, key = { it.id }) { photo ->
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(SurfaceInk)
                    .clickable { onPhoto(photo) }.semantics { contentDescription = "Open ${if (photo.bloom) "Bloom" else "Pure"} photo ${photo.name}" }) {
                    thumbnail(photo)
                    if (photo.rawUri != null) Text("RAW", color = Paper, fontSize = 9.sp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Ink.copy(alpha = 0.65f), RoundedCornerShape(5.dp))
                            .glass(RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 2.dp))
                }
            }
        }
    }
}

@Composable private fun GalleryThumbnail(photo: GalleryPhoto, store: GalleryStore) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, photo.uri) { value = store.thumbnail(photo) }
    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}

@Composable internal fun PhotoViewer(photos: List<GalleryPhoto>, initialId: Long, onSelect: (Long) -> Unit = {},
    onClose: () -> Unit = {}, onShare: (GalleryPhoto, Boolean) -> Unit = { _, _ -> }, onDelete: (GalleryPhoto) -> Unit = {},
    onInfo: (GalleryPhoto) -> Unit = {}, image: @Composable (GalleryPhoto, Boolean) -> Unit = { _, _ -> }) {
    val pager = rememberPagerState(initialPage = photos.indexOfFirst { it.id == initialId }.coerceAtLeast(0), pageCount = { photos.size })
    var zoomed by remember { mutableStateOf(false) }
    val photo = photos[pager.currentPage.coerceAtMost(photos.lastIndex)]
    LaunchedEffect(pager.currentPage) { zoomed = false; onSelect(photo.id) }
    Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.glass(CircleShape)) { Glyph("back", Paper, Modifier.semantics { contentDescription = "Back to gallery" }) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(photo.timestamp)), color = Paper, fontSize = 14.sp)
                MicroText("${pager.currentPage + 1} / ${photos.size}", Muted)
            }
            IconButton(onClick = { onInfo(photo) }) { Glyph("info", Paper, description = "Photo details") }
        }
        HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.weight(1f).fillMaxWidth(), key = { photos[it].id }) { index ->
            var scale by remember(photos[index].id) { mutableFloatStateOf(1f) }
            var pan by remember(photos[index].id) { mutableStateOf(Offset.Zero) }
            LaunchedEffect(pager.currentPage) { if (pager.currentPage != index) { scale = 1f; pan = Offset.Zero } }
            BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp))) {
                val width = constraints.maxWidth.toFloat(); val height = constraints.maxHeight.toFloat()
                Box(Modifier.fillMaxSize()
                    .pointerInput(photos[index].id) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; pan = Offset.Zero; zoomed = scale > 1f }) }
                    .transformable(state = rememberTransformableState { _, zoom, movement, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        pan = if (scale <= 1f) Offset.Zero else Offset(
                            (pan.x + movement.x).coerceIn(-width * (scale - 1) / 2, width * (scale - 1) / 2),
                            (pan.y + movement.y).coerceIn(-height * (scale - 1) / 2, height * (scale - 1) / 2))
                        zoomed = scale > 1f
                    }, canPan = { scale > 1f })
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y }) {
                    image(photos[index], index == pager.currentPage)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp).glass(RoundedCornerShape(24.dp)).padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { onShare(photo, false) }) { Text("Share", color = Paper) }
            if (photo.rawUri != null) TextButton(onClick = { onShare(photo, true) }) { Text("Share RAW", color = Paper) }
            TextButton(onClick = { onDelete(photo) }) { Text("Delete", color = Muted) }
        }
    }
}

@Composable private fun ViewerImage(photo: GalleryPhoto, active: Boolean, store: GalleryStore) {
    var failed by remember(photo.id) { mutableStateOf(false) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, photo.uri, active) {
        value = null; failed = false
        value = if (active) galleryResult { store.image(photo) }.onFailure { failed = true }.getOrNull() else store.thumbnail(photo)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: if (failed) Text("This photo is no longer available.", color = Muted) else CircularProgressIndicator(color = Paper, modifier = Modifier.size(24.dp))
    }
}

/** Cancellation must stop obsolete image loads instead of presenting them as failures. */
private inline fun <T> galleryResult(block: () -> T): Result<T> = try { Result.success(block()) }
catch (cancelled: CancellationException) { throw cancelled }
catch (failure: Exception) { Result.failure(failure) }

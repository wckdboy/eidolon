package studio.eidolon.camera

import android.content.ClipData
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Date

@Composable internal fun GalleryScreen(onClose: () -> Unit, onLibraryChanged: (String?) -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { GalleryStore(context) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var photos by remember { mutableStateOf<List<GalleryPhoto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("All") }
    val gridState = rememberLazyGridState()
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf<GalleryPhoto?>(null) }
    var deletingNow by remember { mutableStateOf(false) }
    var inspecting by remember { mutableStateOf<GalleryPhoto?>(null) }
    var details by remember { mutableStateOf<GalleryDetails?>(null) }
    var detailsError by remember { mutableStateOf(false) }
    LaunchedEffect(inspecting?.id) {
        details = null; detailsError = false
        inspecting?.let { photo ->
            galleryResult { store.details(photo) }.onSuccess { details = it }.onFailure { detailsError = true }
        }
    }
    LaunchedEffect(revision) {
        if (!loading) delay(160)
        galleryResult { store.photos() }.onSuccess {
            photos = it; error = null
            onLibraryChanged(it.firstOrNull()?.uri)
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
            }, onDelete = { deleting = it }, onInfo = { inspecting = it }, image = { photo, active -> ViewerImage(photo, active, store) })
    } else {
        GalleryInterface(photos, loading, error, onClose, onRetry = { loading = true; revision++ }, onPhoto = { selected = it.id },
            filter = filter, onFilter = { filter = it }, gridState = gridState) { photo ->
            GalleryThumbnail(photo, store)
        }
    }
    if (deleting != null) AlertDialog(onDismissRequest = { if (!deletingNow) deleting = null },
        containerColor = SurfaceInk, title = { Text("Delete this moment?") },
        text = { Text(if (deleting!!.rawUri != null) "The JPEG and its RAW original will be permanently deleted." else "This photo will be permanently deleted.") },
        confirmButton = { TextButton(enabled = !deletingNow, onClick = { val photo = deleting!!; deletingNow = true; scope.launch {
            galleryResult { store.delete(photo) }.onSuccess {
                photos = photos.filterNot { it.id == photo.id }; selected = null; revision++
                onLibraryChanged(photos.firstOrNull()?.uri)
            }.onFailure { error = "Couldn’t delete this photo. It may no longer be available."; revision++ }
            deleting = null; deletingNow = false
        } }) { Text(if (deletingNow) "Deleting…" else "Delete", color = Peach) } },
        dismissButton = { TextButton(enabled = !deletingNow, onClick = { deleting = null }) { Text("Keep") } })
    inspecting?.let { PhotoDetailsSheet(it, details, detailsError, onClose = { inspecting = null }) }
    if (error != null && selected != null) AlertDialog(onDismissRequest = { error = null }, containerColor = SurfaceInk,
        text = { Text(error!!) }, confirmButton = { TextButton(onClick = { error = null }) { Text("Done") } })
}

@Composable internal fun GalleryInterface(photos: List<GalleryPhoto>, loading: Boolean = false, error: String? = null,
    onClose: () -> Unit = {}, onRetry: () -> Unit = {}, onPhoto: (GalleryPhoto) -> Unit = {},
    filter: String = "All", onFilter: (String) -> Unit = {}, gridState: LazyGridState = rememberLazyGridState(),
    thumbnail: @Composable (GalleryPhoto) -> Unit = {}) {
    val visible = remember(photos, filter) { photos.filter { filter == "All" || it.bloom == (filter == "Bloom") } }
    val days = remember(visible) { visible.groupBy {
        java.time.Instant.ofEpochMilli(it.timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    } }
    Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Library", color = Paper, fontSize = 24.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.glass(CircleShape)) { Glyph("close", Paper, Modifier.semantics { contentDescription = "Back to camera" }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("All", "Pure", "Bloom").forEach { name ->
                FilterChip(selected = filter == name, onClick = { onFilter(name) }, label = { Text(name) },
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
        } else LazyVerticalGrid(state = gridState, columns = GridCells.Adaptive(112.dp), modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            days.forEach { (day, dayPhotos) ->
                item(key = "day:$day", span = { GridItemSpan(maxLineSpan) }) {
                    Text(day.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)),
                        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp).semantics { heading() })
                }
                items(dayPhotos, key = { it.id }) { photo ->
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(5.dp)).background(SurfaceInk)
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
}

@Composable private fun GalleryThumbnail(photo: GalleryPhoto, store: GalleryStore) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, photo.uri) { value = store.thumbnail(photo) }
    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}

@Composable internal fun PhotoViewer(photos: List<GalleryPhoto>, initialId: Long, onSelect: (Long) -> Unit = {},
    onClose: () -> Unit = {}, onShare: (GalleryPhoto, Boolean) -> Unit = { _, _ -> }, onDelete: (GalleryPhoto) -> Unit = {},
    onInfo: (GalleryPhoto) -> Unit = {}, initiallyShowControls: Boolean = true,
    image: @Composable (GalleryPhoto, Boolean) -> Unit = { _, _ -> }) {
    val pager = rememberPagerState(initialPage = photos.indexOfFirst { it.id == initialId }.coerceAtLeast(0), pageCount = { photos.size })
    var zoomed by remember { mutableStateOf(false) }
    var showControls by rememberSaveable { mutableStateOf(initiallyShowControls) }
    val photo = photos[pager.currentPage.coerceAtMost(photos.lastIndex)]
    LaunchedEffect(pager.currentPage, photo.id) { zoomed = false; onSelect(photo.id) }
    Box(Modifier.fillMaxSize().background(Ink)) {
        HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize(), key = { photos[it].id }) { index ->
            var scale by remember(photos[index].id) { mutableFloatStateOf(1f) }
            var pan by remember(photos[index].id) { mutableStateOf(Offset.Zero) }
            LaunchedEffect(pager.currentPage) { if (pager.currentPage != index) { scale = 1f; pan = Offset.Zero } }
            BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp))) {
                val width = constraints.maxWidth.toFloat(); val height = constraints.maxHeight.toFloat()
                Box(Modifier.fillMaxSize()
                    .semantics { onClick(label = "Show or hide viewer controls") { showControls = !showControls; true } }
                    .pointerInput(photos[index].id) { detectTapGestures(
                        onTap = { showControls = !showControls },
                        onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; pan = Offset.Zero; zoomed = scale > 1f }) }
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
        AnimatedVisibility(showControls, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.9f), Color.Transparent)))
                .statusBarsPadding().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.glass(CircleShape)) { Glyph("back", Paper, description = "Back to gallery") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(photo.timestamp)), color = Paper, fontSize = 14.sp)
                    MicroText("${pager.currentPage + 1} / ${photos.size}", Paper)
                }
                IconButton(onClick = { onInfo(photo) }, modifier = Modifier.glass(CircleShape)) { Glyph("info", Paper, description = "Photo details") }
            }
        }
        AnimatedVisibility(showControls, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)
                .background(Ink.copy(alpha = 0.8f), RoundedCornerShape(24.dp)).glass(RoundedCornerShape(24.dp)).padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { onShare(photo, false) }) { Text("Share", color = Paper) }
                if (photo.rawUri != null) TextButton(onClick = { onShare(photo, true) }) { Text("Share RAW", color = Paper) }
                TextButton(onClick = { onDelete(photo) }) { Text("Delete", color = Muted) }
            }
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

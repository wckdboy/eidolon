package studio.eidolon.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

private val ready = CameraState(ready = true, iso = 100, shutterNs = 8_000_000,
    info = CameraInfo(raw = true, manual = true, focusMax = 10f, evMin = -12, evMax = 12, evStep = 1f / 3, cameraCount = 2))

@PreviewTest
@Preview(name = "Pure Fun", widthDp = 390, heightDp = 844, showBackground = true)
@Composable fun PureFunPreview() { EidolonTheme { CameraInterface(state = ready, preview = { PreviewScene() }) } }

@PreviewTest
@Preview(name = "Bloom Pro", widthDp = 390, heightDp = 844, showBackground = true)
@Composable fun BloomProPreview() { EidolonTheme {
    CameraInterface(state = ready, settings = CaptureSettings(look = Look.BLOOM, iso = 100, shutterNs = 8_000_000),
        workspace = Workspace.PRO, activeControl = "ISO", grid = true, preview = { PreviewScene() })
} }

@PreviewTest
@Preview(name = "Compact large type", widthDp = 360, heightDp = 740, fontScale = 1.5f, showBackground = true)
@Composable fun LargeTypePreview() { EidolonTheme {
    CameraInterface(state = ready, workspace = Workspace.PRO, activeControl = "EV", preview = { PreviewScene() })
} }

@PreviewTest
@Preview(name = "Permission", widthDp = 390, heightDp = 844, showBackground = true)
@Composable fun PermissionPreview() { EidolonTheme { CameraInterface(granted = false) } }

@PreviewTest
@Preview(name = "Error", widthDp = 390, heightDp = 844, showBackground = true)
@Composable fun ErrorPreview() { EidolonTheme {
    CameraInterface(state = CameraState(error = true, message = "Camera unavailable. Close other camera apps and retry."))
} }

@PreviewTest
@Preview(name = "Small phone", widthDp = 320, heightDp = 640, showBackground = true)
@Composable fun SmallPhonePreview() { EidolonTheme { CameraInterface(state = ready, preview = { PreviewScene() }) } }

@PreviewTest
@Preview(name = "Bloom large type", widthDp = 360, heightDp = 740, fontScale = 1.5f, showBackground = true)
@Composable fun BloomLargeTypePreview() { EidolonTheme {
    CameraInterface(state = ready, settings = CaptureSettings(look = Look.BLOOM), workspace = Workspace.PRO,
        feelingOpen = true, preview = { PreviewScene() })
} }

@PreviewTest
@Preview(name = "Saving", widthDp = 390, heightDp = 844, showBackground = true)
@Composable fun SavingPreview() { EidolonTheme {
    CameraInterface(state = ready.copy(saving = 1, message = "Saving…"), preview = { PreviewScene() })
} }

/** An abstract architectural test scene; never included in the production camera preview. */
@Composable private fun PreviewScene() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFF75959C), Color(0xFFB7C8C4))))
        drawPath(Path().apply { moveTo(0f, h * .26f); lineTo(w * .57f, h * .08f); lineTo(w * .57f, h); lineTo(0f, h); close() }, Color(0xFFE5D8BE))
        drawPath(Path().apply { moveTo(w * .57f, h * .08f); lineTo(w * .76f, h * .23f); lineTo(w * .76f, h); lineTo(w * .57f, h); close() }, Color(0xFFAD9F88))
        drawRect(Color(0xFF535C52), Offset(w * .14f, h * .39f), Size(w * .26f, h * .43f))
        drawRect(Color(0xFF26392F), Offset(w * .17f, h * .41f), Size(w * .23f, h * .41f))
        drawPath(Path().apply { moveTo(0f, h * .94f); lineTo(w, h * .72f); lineTo(w, h); lineTo(0f, h); close() }, Color(0xFFC6B598))
        drawPath(Path().apply { moveTo(w * .4f, h * .82f); lineTo(w * .89f, h); lineTo(w * .65f, h); lineTo(w * .14f, h * .82f); close() }, Color(0xFF8B806A))
    }
}

private val gallerySamples = List(12) { index -> GalleryPhoto(index.toLong(), "preview:$index",
    "EIDOLON_${index}_${if (index % 3 == 0) "BLOOM" else "PURE"}.jpg", 1760000000000L,
    4000, 3000, 3500000, if (index % 3 != 0) "raw:$index" else null) }

@PreviewTest
@Preview(name = "Gallery", widthDp = 390, heightDp = 844, showBackground = true)
@Composable fun GalleryPreview() { EidolonTheme { GalleryInterface(gallerySamples) { PreviewScene() } } }

@PreviewTest
@Preview(name = "Empty gallery", widthDp = 320, heightDp = 640, fontScale = 1.5f, showBackground = true)
@Composable fun EmptyGalleryPreview() { EidolonTheme { GalleryInterface(emptyList()) } }

@PreviewTest
@Preview(name = "Photo viewer", widthDp = 390, heightDp = 844, showBackground = true)
@Composable fun PhotoViewerPreview() { EidolonTheme { PhotoViewer(gallerySamples, 1) { _, _ -> PreviewScene() } } }

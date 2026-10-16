package studio.eidolon.camera

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PhotoDetailsSheet(photo: GalleryPhoto, details: GalleryDetails?, failed: Boolean, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SurfaceInk, contentColor = Paper) {
        PhotoDetailsPanel(photo, details, failed, onClose)
    }
}

@Composable internal fun PhotoDetailsPanel(photo: GalleryPhoto, details: GalleryDetails?, failed: Boolean = false, onClose: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
        MicroText(if (photo.bloom) "BLOOM" else "PURE", if (photo.bloom) BloomViolet else Muted)
        Text("Photo details", color = Paper, fontSize = 26.sp, letterSpacing = (-0.7).sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
        if (details != null) {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), photo.bloom).padding(16.dp)) {
                MetricRow("ISO" to details.iso, "SHUTTER" to details.shutter, "APERTURE" to details.aperture)
                HorizontalDivider(Modifier.padding(vertical = 16.dp), color = EdgeInk)
                MetricRow("FOCAL LENGTH" to details.focalLength, "SIZE" to details.size)
            }
            Spacer(Modifier.height(20.dp))
            MetricRow("CAMERA" to details.camera)
            Spacer(Modifier.height(16.dp))
            MetricRow("DIMENSIONS" to details.dimensions, "FORMAT" to if (photo.rawUri != null) "RAW + JPEG" else "JPEG")
            if (photo.rawUri != null) Text("Viewing the JPEG companion. Share RAW to export the sensor original.",
                color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 16.dp))
        } else if (failed) Text("Photo information is unavailable.", color = Muted)
        else CircularProgressIndicator(Modifier.size(24.dp), color = Paper, strokeWidth = 2.dp)
        Text(photo.name, color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 24.dp))
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Done", color = Paper) }
    }
}

@Composable private fun MetricRow(vararg metrics: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        metrics.forEach { (label, value) ->
            Column(Modifier.weight(1f)) {
                MicroText(label, Muted)
                Text(value, color = Paper, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}

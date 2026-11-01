package com.gpsphoto.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.gpsphoto.app.data.PhotoFiles
import com.gpsphoto.app.data.hasLocation
import com.gpsphoto.app.data.tags
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(vm: PhotoViewModel, photoId: Long, onBack: () -> Unit, onShowOnMap: (String) -> Unit) {
    val byId by vm.byId.collectAsStateWithLifecycle()
    // Freeze the swipe order on entry so edits (e.g. a rename while sorted by name) don't shuffle pages.
    val ids = remember { vm.visible.value.map { it.photo.id }.takeIf { photoId in it } ?: listOf(photoId) }
    val pager = rememberPagerState(initialPage = ids.indexOf(photoId)) { ids.size }
    val current = byId[ids[pager.currentPage]]
    var editing by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current?.photo?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Black)) { page ->
                AsyncImage(
                    model = byId[ids[page]]?.photo?.uri,
                    contentDescription = byId[ids[page]]?.photo?.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (ids.size > 1) {
                Text(
                    "${pager.currentPage + 1} of ${ids.size} · swipe for more",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
                )
            }
            if (current == null) {
                Text("This photo is no longer on the device.", modifier = Modifier.padding(16.dp))
                return@Column
            }
            val p = current.photo

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Button(onClick = { editing = true }) { ButtonContent(Icons.Default.Edit, "Edit details") }
                val cellKey = p.cellKey
                if (cellKey != null) {
                    OutlinedButton(onClick = { onShowOnMap(cellKey) }) { ButtonContent(Icons.Default.Map, "Show on map") }
                }
                OutlinedButton(onClick = { vm.saveCopy(p.id) }) { ButtonContent(Icons.Default.SaveAlt, "Save renamed copy") }
            }

            InfoRow(Icons.Default.Event, "Taken", formatDateTime(p.dateTaken))
            InfoRow(
                Icons.Default.Place, "Place",
                when {
                    p.placeName != null -> p.placeName
                    current.hasLocation -> "Place name appears when online"
                    else -> "No location saved in this photo"
                },
            )
            if (p.lat != null && p.lng != null) {
                InfoRow(Icons.Default.GpsFixed, "GPS", "%.6f, %.6f".format(Locale.US, p.lat, p.lng))
            }
            InfoRow(Icons.Default.Folder, "Category", p.category.ifEmpty { "None" })
            InfoRow(Icons.AutoMirrored.Filled.Label, "Tags", current.tags.joinToString(", ").ifEmpty { "None" })
            InfoRow(Icons.AutoMirrored.Filled.Notes, "Description", p.description.ifEmpty { "None" })
            InfoRow(
                Icons.Default.Image, "File",
                "${p.fileName} · ${Formatter.formatShortFileSize(context, p.sizeBytes)} · ${p.width}×${p.height}",
            )
            Text(
                "Edits are kept in this app; the original photo and its GPS data are never changed. " +
                    "\"Save renamed copy\" creates a new file in ${PhotoFiles.ALBUM} with your name, description and tags.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )

            if (editing) {
                EditDetailsDialog(
                    title = p.title,
                    description = p.description,
                    category = p.category,
                    tags = current.tags,
                    onDismiss = { editing = false },
                    onSave = { t, d, c, g ->
                        vm.saveDetails(p.id, t, d, c, g)
                        editing = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ButtonContent(icon: ImageVector, label: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text(label)
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.padding(start = 16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

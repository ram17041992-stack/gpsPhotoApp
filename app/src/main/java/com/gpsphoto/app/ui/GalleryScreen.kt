package com.gpsphoto.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.gpsphoto.app.data.GroupBy
import com.gpsphoto.app.data.PhotoGroup
import com.gpsphoto.app.data.PhotoWithTags
import com.gpsphoto.app.data.SortBy
import com.gpsphoto.app.data.hasLocation
import com.gpsphoto.app.data.localDayEnd
import com.gpsphoto.app.data.localDayStart

private enum class BulkAction(
    val title: String, val label: String, val hint: String, val confirm: String, val allowEmpty: Boolean,
) {
    TAG("Add tags", "Tags", "Separate tags with commas, e.g. beach, family", "Add", false),
    RENAME("Rename photos", "New name", "Photos are numbered in date order: Name 01, Name 02…", "Rename", false),
    CATEGORY("Set category", "Category", "Leave empty to remove the category", "Apply", true),
}

@Composable
fun GalleryScreen(vm: PhotoViewModel, onOpen: (Long) -> Unit) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val filtersActive by vm.filtersActive.collectAsStateWithLifecycle()
    val allTags by vm.allTags.collectAsStateWithLifecycle()
    val allCategories by vm.allCategories.collectAsStateWithLifecycle()
    var bulkAction by remember { mutableStateOf<BulkAction?>(null) }

    BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }

    Column(Modifier.fillMaxSize()) {
        if (selection.isEmpty()) SearchHeader(vm)
        else SelectionHeader(
            count = selection.size,
            onClose = vm::clearSelection,
            onSelectAll = { vm.select(vm.visible.value.map { it.photo.id }) },
            onAction = { bulkAction = it },
        )
        if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            groups.isEmpty() && loaded && filtersActive -> EmptyState("No photos match", "Clear filters", vm::clearFilters)
            groups.isEmpty() && loaded -> EmptyState("No photos yet.\nTap Camera to take a photo with its location.", null, null)
            else -> PhotoGrid(
                groups = groups,
                selection = selection,
                onClick = { id -> if (selection.isNotEmpty()) vm.toggle(id) else onOpen(id) },
                onLongClick = vm::toggle,
                onSelectGroup = { group -> vm.select(group.photos.map { it.photo.id }) },
            )
        }
    }

    bulkAction?.let { action ->
        TextInputDialog(
            title = action.title,
            label = action.label,
            hint = action.hint,
            confirmLabel = action.confirm,
            allowEmpty = action.allowEmpty,
            suggestions = when (action) {
                BulkAction.TAG -> allTags
                BulkAction.CATEGORY -> allCategories
                BulkAction.RENAME -> emptyList()
            },
            appendSuggestions = action == BulkAction.TAG,
            onDismiss = { bulkAction = null },
            onConfirm = { text ->
                when (action) {
                    BulkAction.TAG -> vm.tagSelected(text)
                    BulkAction.RENAME -> vm.renameSelected(text)
                    BulkAction.CATEGORY -> vm.categorizeSelected(text)
                }
                bulkAction = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchHeader(vm: PhotoViewModel) {
    val query by vm.query.collectAsStateWithLifecycle()
    val sort by vm.sortBy.collectAsStateWithLifecycle()
    val group by vm.groupBy.collectAsStateWithLifecycle()
    val withLocation by vm.withLocationOnly.collectAsStateWithLifecycle()
    val range by vm.dateRange.collectAsStateWithLifecycle()
    val place by vm.placeFilter.collectAsStateWithLifecycle()
    var pickDates by remember { mutableStateOf(false) }

    Column(Modifier.statusBarsPadding().padding(top = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { vm.query.value = it },
            placeholder = { Text("Search place, name, tag or date") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            place?.let {
                InputChip(
                    selected = true,
                    onClick = { vm.placeFilter.value = null },
                    label = { Text(it.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(140.dp)) },
                    leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) },
                    trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove place filter") },
                )
            }
            MenuChip("Sort: ${sort.label}", SortBy.entries, { it.label }) { vm.sortBy.value = it }
            MenuChip("Group: ${group.label}", GroupBy.entries, { it.label }) { vm.groupBy.value = it }
            FilterChip(
                selected = withLocation,
                onClick = { vm.withLocationOnly.value = !withLocation },
                label = { Text("Has location") },
                leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) },
            )
            FilterChip(
                selected = range != null,
                onClick = { if (range != null) vm.dateRange.value = null else pickDates = true },
                label = { Text(range?.let { "${formatDay(it.first)} – ${formatDay(it.second)}" } ?: "Dates") },
                leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                trailingIcon = if (range != null) {
                    { Icon(Icons.Default.Close, contentDescription = "Remove date filter") }
                } else null,
            )
        }
    }

    if (pickDates) {
        val state = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { pickDates = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val start = state.selectedStartDateMillis!!
                        vm.dateRange.value = localDayStart(start) to localDayEnd(state.selectedEndDateMillis ?: start)
                        pickDates = false
                    },
                ) { Text("Show") }
            },
            dismissButton = { TextButton(onClick = { pickDates = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SelectionHeader(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit, onAction: (BulkAction) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Cancel selection") }
                Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onSelectAll) {
                    Icon(Icons.Default.SelectAll, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("All")
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            ) {
                BulkButton(Icons.AutoMirrored.Filled.Label, "Tag") { onAction(BulkAction.TAG) }
                BulkButton(Icons.Default.DriveFileRenameOutline, "Rename") { onAction(BulkAction.RENAME) }
                BulkButton(Icons.Default.Folder, "Category") { onAction(BulkAction.CATEGORY) }
            }
        }
    }
}

@Composable
private fun BulkButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun PhotoGrid(
    groups: List<PhotoGroup>,
    selection: Set<Long>,
    onClick: (Long) -> Unit,
    onLongClick: (Long) -> Unit,
    onSelectGroup: (PhotoGroup) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(108.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        groups.forEach { group ->
            item(key = "header/${group.key}", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                GroupHeader(group) { onSelectGroup(group) }
            }
            items(group.photos, key = { "${group.key}/${it.photo.id}" }, contentType = { "photo" }) { p ->
                Thumbnail(
                    photo = p,
                    selected = p.photo.id in selection,
                    onClick = { onClick(p.photo.id) },
                    onLongClick = { onLongClick(p.photo.id) },
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(group: PhotoGroup, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp),
    ) {
        Text(
            group.label,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            "  ${group.photos.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSelect) { Text("Select") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Thumbnail(photo: PhotoWithTags, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(Modifier.aspectRatio(1f).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        AsyncImage(
            model = photo.photo.uri,
            contentDescription = photo.photo.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
                .padding(horizontal = 4.dp, vertical = 3.dp),
        ) {
            if (photo.hasLocation) {
                Icon(Icons.Default.Place, contentDescription = "Has location", tint = Color.White, modifier = Modifier.size(14.dp))
            }
            Text(
                photo.photo.title,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)))
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Selected",
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(message: String, actionLabel: String?, onAction: (() -> Unit)?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(32.dp),
    ) {
        Icon(
            Icons.Default.PhotoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp),
        )
        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(actionLabel) }
        }
    }
}

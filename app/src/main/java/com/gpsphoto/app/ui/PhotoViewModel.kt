package com.gpsphoto.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gpsphoto.app.data.GroupBy
import com.gpsphoto.app.data.MediaSync
import com.gpsphoto.app.data.PhotoDatabase
import com.gpsphoto.app.data.PhotoFiles
import com.gpsphoto.app.data.PhotoFilter
import com.gpsphoto.app.data.PhotoTag
import com.gpsphoto.app.data.PhotoWithTags
import com.gpsphoto.app.data.Place
import com.gpsphoto.app.data.SavedPlace
import com.gpsphoto.app.data.applySavedPlaces
import com.gpsphoto.app.data.SortBy
import com.gpsphoto.app.data.groupPhotos
import com.gpsphoto.app.data.matches
import com.gpsphoto.app.data.parseTags
import com.gpsphoto.app.data.placesOf
import com.gpsphoto.app.data.tags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Shared by gallery, map and detail so filters and selection stay linked across views. */
@OptIn(FlowPreview::class)
class PhotoViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = PhotoDatabase.get(app).photoDao()
    private val syncLock = Mutex()

    val query = MutableStateFlow("")
    val sortBy = MutableStateFlow(SortBy.NEWEST)
    val groupBy = MutableStateFlow(GroupBy.DATE)
    val withLocationOnly = MutableStateFlow(false)
    val dateRange = MutableStateFlow<Pair<Long, Long>?>(null)
    val placeFilter = MutableStateFlow<Place?>(null)
    val selection = MutableStateFlow<Set<Long>>(emptySet())
    /** Cell key the map should zoom to next time it is shown. */
    val mapFocus = MutableStateFlow<String?>(null)
    val syncing = MutableStateFlow(false)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val savedPlaces: StateFlow<List<SavedPlace>> = dao.observePlaces()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Every photo, with named places applied, so all screens show the same names. */
    private val all: StateFlow<List<PhotoWithTags>?> = combine(dao.observeAll(), dao.observePlaces()) { photos, places ->
        applySavedPlaces(photos, places)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val loaded = all.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val byId = all.map { list -> list.orEmpty().associateBy { it.photo.id } }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val allTags = all.map { list -> list.orEmpty().flatMap { it.tags }.distinct().sorted() }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allCategories = all.map { list -> list.orEmpty().map { it.photo.category }.filter(String::isNotBlank).distinct().sorted() }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val filter = combine(
        query.debounce(200).onStart { emit(query.value) }, withLocationOnly, dateRange, placeFilter,
    ) { q, loc, range, place -> PhotoFilter(q, loc, range?.first, range?.second, place?.key) }

    val visible: StateFlow<List<PhotoWithTags>> = combine(all, filter, sortBy) { photos, f, sort ->
        photos.orEmpty().filter { it.matches(f) }.sortedWith(sort.comparator)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val groups = combine(visible, groupBy) { photos, by -> groupPhotos(photos, by) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val places = visible.map { placesOf(it) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val filtersActive = combine(query, withLocationOnly, dateRange, placeFilter) { q, loc, range, place ->
        q.isNotBlank() || loc || range != null || place != null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun clearFilters() {
        query.value = ""
        withLocationOnly.value = false
        dateRange.value = null
        placeFilter.value = null
    }

    fun sync() {
        viewModelScope.launch {
            if (!syncLock.tryLock()) return@launch
            syncing.value = true
            try {
                MediaSync.sync(getApplication(), dao)
            } catch (e: Exception) {
                messages.emit("Couldn't read photos: ${e.message}")
            } finally {
                syncing.value = false
                syncLock.unlock()
            }
        }
    }

    /** Names a place (or renames [existing]). Photos within 50 m of it use the name. */
    fun namePlace(existing: SavedPlace?, name: String, lat: Double, lng: Double) {
        viewModelScope.launch {
            val clean = name.trim()
            dao.upsertPlace(existing?.copy(name = clean) ?: SavedPlace(name = clean, lat = lat, lng = lng))
            placeFilter.value = null
            messages.emit("Place saved as \"$clean\"")
        }
    }

    fun removePlace(place: SavedPlace) {
        viewModelScope.launch {
            dao.deletePlace(place.id)
            placeFilter.value = null
            messages.emit("Removed the name \"${place.name}\"")
        }
    }

    fun saveDetails(id: Long, title: String, description: String, category: String, tagsText: String) {
        viewModelScope.launch {
            val current = byId.value[id] ?: return@launch
            dao.saveDetails(id, title.trim().ifEmpty { current.photo.title }, description.trim(), category.trim(), parseTags(tagsText))
            messages.emit("Details saved")
        }
    }

    fun saveCopy(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val p = byId.value[id] ?: return@launch
            val ok = PhotoFiles.saveEditedCopy(getApplication(), p.photo, p.tags)
            messages.emit(if (ok) "Copy saved to ${PhotoFiles.ALBUM}" else "Couldn't save the copy")
            if (ok) sync()
        }
    }

    // --- Selection & bulk actions ---

    fun toggle(id: Long) = selection.update { if (id in it) it - id else it + id }
    fun select(ids: Collection<Long>) = selection.update { it + ids }
    fun clearSelection() { selection.value = emptySet() }

    fun renameSelected(baseName: String) = bulk("Renamed") { ids ->
        val ordered = ids.mapNotNull { byId.value[it] }.sortedBy { it.photo.dateTaken }
        val digits = ordered.size.toString().length.coerceAtLeast(2)
        dao.renameAll(ordered.mapIndexed { i, p -> p.photo.id to "$baseName ${(i + 1).toString().padStart(digits, '0')}" }.toMap())
    }

    fun tagSelected(tagsText: String) = bulk("Tagged") { ids ->
        val tags = parseTags(tagsText)
        dao.addTags(ids.flatMap { id -> tags.map { PhotoTag(id, it) } })
    }

    fun categorizeSelected(category: String) = bulk("Updated") { ids -> dao.setCategoryAll(ids, category.trim()) }

    private fun bulk(verb: String, action: suspend (List<Long>) -> Unit) {
        val ids = selection.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            action(ids)
            clearSelection()
            messages.emit("$verb ${ids.size} photo${if (ids.size == 1) "" else "s"}")
        }
    }
}

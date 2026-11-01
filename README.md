# GPS Photos (Android)

See, organise and find your photos by **where** and **when** they were taken.

| Screen | What you can do |
|---|---|
| **Photos** | Thumbnail grid grouped by month, place, category or tag. Search by place, name, tag, category or date (e.g. `march 2026`, `2026-03-14`). Filter by "Has location" or a date range. Long-press to select several photos, then **Tag**, **Rename** (numbered in date order) or set a **Category**. |
| **Map** | Every place where you took photos is a numbered pin. Tap one to see its photos, open one, or **Show in gallery** to filter the grid to that place. |
| **Photo** | Large view (swipe for next), date in your phone's time zone, place name, GPS, file details. **Edit details** (name, description, category, tags), **Show on map**, **Save renamed copy**. |
| **Camera** | Opens your camera app. If the camera didn't save GPS, the app adds your current location to the new photo. |

**Your originals are safe.** The app never changes existing photos. Names, descriptions, tags and
categories are stored in the app. "Save renamed copy" writes a *new* file to `Pictures/GPS Camera`
with all original EXIF/GPS data, plus your description and tags.

Nearby photos (within ~200 m) are grouped as one place. Place names come from the phone's
geocoder, which needs internet; offline photos show coordinates until the next sync online.
Maps use OpenStreetMap, so you don't need an API key.

## Build

Requirements: Android Studio (Ladybug or newer) or JDK 17 + Android SDK 35.

```bash
./gradlew assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. On GitHub, every push to `main` runs the
tests and publishes `GpsPhotos.apk` on the **Releases** page under "latest".

Minimum Android version: 10 (API 29).

## Code map

| Path | Purpose |
|---|---|
| `data/PhotoDatabase.kt` | Room schema: `photo`, `photo_tag` (cascade delete), DAO |
| `data/MediaSync.kt` | Reads the MediaStore library + EXIF GPS, reverse-geocodes places |
| `data/PhotoFiles.kt` | The only code that writes files (capture, GPS tag, safe copy) |
| `data/PhotoQueries.kt` | Pure search/sort/group/place logic (unit-tested) |
| `ui/PhotoViewModel.kt` | Shared state that links the gallery, map and detail screens |
| `ui/*Screen.kt` | Compose screens |

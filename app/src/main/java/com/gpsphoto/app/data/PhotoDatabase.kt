package com.gpsphoto.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One row per device photo. The original file is never modified: title, description and
 * category are the app's own layer on top of the MediaStore entry (id = MediaStore _ID).
 */
@Entity(tableName = "photo", indices = [Index("cellKey"), Index("dateTaken")])
data class Photo(
    @PrimaryKey val id: Long,
    val uri: String,
    val fileName: String,
    val mimeType: String,
    val dateTaken: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val lat: Double?,
    val lng: Double?,
    /** Grid cell (~200 m) used to group nearby photos; null when the photo has no GPS. */
    val cellKey: String?,
    val placeName: String? = null,
    val title: String,
    val description: String = "",
    val category: String = "",
)

@Entity(
    tableName = "photo_tag",
    primaryKeys = ["photoId", "tag"],
    foreignKeys = [ForeignKey(
        entity = Photo::class,
        parentColumns = ["id"],
        childColumns = ["photoId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("tag")],
)
data class PhotoTag(val photoId: Long, val tag: String)

data class PhotoWithTags(
    @Embedded val photo: Photo,
    @Relation(parentColumn = "id", entityColumn = "photoId") val tagRows: List<PhotoTag>,
)

/** A place the user named. Photos within [radiusM] metres of it use this name. */
@Entity(tableName = "saved_place")
data class SavedPlace(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Double = 50.0,
)

data class CellPoint(val cellKey: String, val lat: Double, val lng: Double)

data class PhotoRef(val id: Long, val uri: String)

@Dao
abstract class PhotoDao {
    @Transaction
    @Query("SELECT * FROM photo ORDER BY dateTaken DESC")
    abstract fun observeAll(): Flow<List<PhotoWithTags>>

    @Query("SELECT id FROM photo")
    abstract suspend fun allIds(): List<Long>

    @Query("SELECT id, uri FROM photo WHERE lat IS NULL")
    abstract suspend fun photosWithoutLocation(): List<PhotoRef>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPhotos(photos: List<Photo>)

    @Query("DELETE FROM photo WHERE id IN (:ids)")
    abstract suspend fun deleteIds(ids: List<Long>)

    @Query("UPDATE photo SET lat = :lat, lng = :lng, cellKey = :cellKey WHERE id = :id")
    abstract suspend fun setLocation(id: Long, lat: Double, lng: Double, cellKey: String)

    @Query("SELECT cellKey, lat, lng FROM photo WHERE placeName IS NULL AND cellKey IS NOT NULL GROUP BY cellKey")
    abstract suspend fun unnamedCells(): List<CellPoint>

    @Query("UPDATE photo SET placeName = :name WHERE cellKey = :cellKey")
    abstract suspend fun setPlaceName(cellKey: String, name: String)

    @Query("UPDATE photo SET title = :title, description = :description, category = :category WHERE id = :id")
    abstract suspend fun updateDetails(id: Long, title: String, description: String, category: String)

    @Query("UPDATE photo SET title = :title WHERE id = :id")
    abstract suspend fun setTitle(id: Long, title: String)

    @Query("UPDATE photo SET category = :category WHERE id IN (:ids)")
    abstract suspend fun setCategory(ids: List<Long>, category: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addTags(tags: List<PhotoTag>)

    @Query("DELETE FROM photo_tag WHERE photoId = :id")
    abstract suspend fun clearTags(id: Long)

    @Query("SELECT * FROM saved_place ORDER BY name COLLATE NOCASE")
    abstract fun observePlaces(): Flow<List<SavedPlace>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPlace(place: SavedPlace)

    @Query("DELETE FROM saved_place WHERE id = :id")
    abstract suspend fun deletePlace(id: Long)

    // SQLite allows at most 999 bound variables, so IN-lists are chunked.
    @Transaction
    open suspend fun deleteAll(ids: List<Long>) = ids.chunked(500).forEach { deleteIds(it) }

    @Transaction
    open suspend fun setCategoryAll(ids: List<Long>, category: String) =
        ids.chunked(500).forEach { setCategory(it, category) }

    @Transaction
    open suspend fun renameAll(titles: Map<Long, String>) = titles.forEach { (id, t) -> setTitle(id, t) }

    @Transaction
    open suspend fun saveDetails(id: Long, title: String, description: String, category: String, tags: List<String>) {
        updateDetails(id, title, description, category)
        clearTags(id)
        addTags(tags.map { PhotoTag(id, it) })
    }
}

/** v2 adds named places. Existing photos, names and tags are kept. */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `saved_place` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, `radiusM` REAL NOT NULL)",
        )
    }
}

@Database(entities = [Photo::class, PhotoTag::class, SavedPlace::class], version = 2)
abstract class PhotoDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao

    companion object {
        @Volatile private var instance: PhotoDatabase? = null

        fun get(context: Context): PhotoDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, PhotoDatabase::class.java, "photos.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}

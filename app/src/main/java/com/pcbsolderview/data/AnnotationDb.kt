package com.pcbsolderview.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pcbsolderview.render.ComponentMark
import com.pcbsolderview.render.InkStroke
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "marks", primaryKeys = ["boardKey", "refDes"])
data class MarkEntity(val boardKey: String, val refDes: String, val argb: Int, val note: String? = null) {
    fun toMark() = ComponentMark(refDes, argb, note)
}

@Entity(tableName = "strokes", indices = [Index("boardKey")])
data class StrokeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val boardKey: String,
    val argb: Int,
    val widthUm: Float,
    /** "x,y;x,y;…" in whole micrometres. */
    val points: String,
) {
    fun toStroke() = InkStroke(id, argb, widthUm, decodePoints(points))

    companion object {
        fun encodePoints(pts: List<Pair<Double, Double>>): String =
            pts.joinToString(";") { "${Math.round(it.first)},${Math.round(it.second)}" }

        fun decodePoints(s: String): List<Pair<Double, Double>> =
            if (s.isEmpty()) emptyList() else s.split(';').mapNotNull { p ->
                val i = p.indexOf(',')
                if (i < 0) null else (p.substring(0, i).toDoubleOrNull() ?: return@mapNotNull null) to (p.substring(i + 1).toDoubleOrNull() ?: return@mapNotNull null)
            }
    }
}

/** A board opened before; its files are copied into app storage at [dir]. */
@Entity(tableName = "recent_boards")
data class RecentBoardEntity(
    @PrimaryKey val boardKey: String,
    val name: String,
    val source: String,
    val components: Int,
    val lastOpened: Long,
    /** Absolute path of the folder holding the board's files and thumb.png. */
    val dir: String,
)

data class MarkCount(val boardKey: String, val argb: Int, val n: Int)
data class StrokeCount(val boardKey: String, val n: Int)

@Dao
interface RecentDao {
    @Query("SELECT * FROM recent_boards ORDER BY lastOpened DESC")
    fun recents(): Flow<List<RecentBoardEntity>>

    @Upsert
    suspend fun upsert(r: RecentBoardEntity)

    @Query("UPDATE recent_boards SET lastOpened = :time WHERE boardKey = :key")
    suspend fun touch(key: String, time: Long)

    @Query("DELETE FROM recent_boards WHERE boardKey = :key")
    suspend fun delete(key: String)

    @Query("SELECT boardKey, argb, COUNT(*) AS n FROM marks GROUP BY boardKey, argb")
    fun markCounts(): Flow<List<MarkCount>>

    @Query("SELECT boardKey, COUNT(*) AS n FROM strokes GROUP BY boardKey")
    fun strokeCounts(): Flow<List<StrokeCount>>
}

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM marks WHERE boardKey = :board")
    fun marks(board: String): Flow<List<MarkEntity>>

    @Query("SELECT * FROM strokes WHERE boardKey = :board ORDER BY id")
    fun strokes(board: String): Flow<List<StrokeEntity>>

    @Query("SELECT * FROM marks WHERE boardKey = :board")
    suspend fun marksNow(board: String): List<MarkEntity>

    @Query("SELECT * FROM strokes WHERE boardKey = :board")
    suspend fun strokesNow(board: String): List<StrokeEntity>

    @Query("SELECT * FROM marks WHERE boardKey = :board AND refDes = :refDes")
    suspend fun mark(board: String, refDes: String): MarkEntity?

    @Upsert
    suspend fun upsertMark(mark: MarkEntity)

    @Upsert
    suspend fun upsertMarks(marks: List<MarkEntity>)

    @Query("DELETE FROM marks WHERE boardKey = :board AND refDes = :refDes")
    suspend fun deleteMark(board: String, refDes: String)

    @Insert
    suspend fun insertStroke(stroke: StrokeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreStrokes(strokes: List<StrokeEntity>)

    @Query("SELECT * FROM strokes WHERE id = :id")
    suspend fun stroke(id: Long): StrokeEntity?

    @Query("DELETE FROM strokes WHERE id = :id")
    suspend fun deleteStroke(id: Long)

    @Query("DELETE FROM strokes WHERE boardKey = :board")
    suspend fun clearStrokes(board: String)

    @Query("DELETE FROM marks WHERE boardKey = :board")
    suspend fun clearMarks(board: String)
}

@Database(entities = [MarkEntity::class, StrokeEntity::class, RecentBoardEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun annotations(): AnnotationDao
    abstract fun recents(): RecentDao

    companion object {
        /** v1 -> v2: adds the recent boards table; existing annotations are kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recent_boards` (`boardKey` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, `components` INTEGER NOT NULL, `lastOpened` INTEGER NOT NULL, " +
                        "`dir` TEXT NOT NULL, PRIMARY KEY(`boardKey`))",
                )
            }
        }

        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "pcbsolderview.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}

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

@Database(entities = [MarkEntity::class, StrokeEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun annotations(): AnnotationDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "pcbsolderview.db").build().also { instance = it }
        }
    }
}

package app.mawsoaa.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** kind: "fav" | "bookmark" | "note".  type: "ayah" | "dhikr" | "hadith". */
@Entity(tableName = "mark", indices = [Index(value = ["type", "refKey", "kind"], unique = true)])
data class Mark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, val refKey: String, val kind: String,
    val title: String, val preview: String, val note: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "daily_progress", primaryKeys = ["day", "itemKey"])
data class DailyProgress(val day: String, val itemKey: String, val done: Int)

@Dao
interface UserDao {
    @Query("SELECT * FROM mark ORDER BY createdAt DESC") fun allMarks(): Flow<List<Mark>>
    @Query("SELECT * FROM mark WHERE type=:type AND refKey LIKE :prefix || '%'") fun marks(type: String, prefix: String): Flow<List<Mark>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(m: Mark)
    @Query("DELETE FROM mark WHERE type=:type AND refKey=:ref AND kind=:kind") suspend fun remove(type: String, ref: String, kind: String)
    @Query("DELETE FROM mark WHERE id=:id") suspend fun removeId(id: Long)

    @Query("SELECT * FROM daily_progress WHERE day=:day") fun progress(day: String): Flow<List<DailyProgress>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setProgress(p: DailyProgress)
    @Query("DELETE FROM daily_progress WHERE day=:day AND itemKey LIKE :prefix || '%'") suspend fun resetProgress(day: String, prefix: String)
}

@Database(entities = [Mark::class, DailyProgress::class], version = 1, exportSchema = false)
abstract class UserDb : RoomDatabase() {
    abstract fun dao(): UserDao
    companion object {
        @Volatile private var inst: UserDb? = null
        fun get(c: Context): UserDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, UserDb::class.java, "user.db").build().also { inst = it }
        }
    }
}

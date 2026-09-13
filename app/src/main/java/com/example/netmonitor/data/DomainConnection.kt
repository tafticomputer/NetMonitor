package com.example.netmonitor.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * هر رکورد یعنی: این برنامه (uid/appName) در این زمان به این دامنه وصل شده است.
 */
@Entity(tableName = "domain_connections")
data class DomainConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: Int,
    val appName: String,
    val packageName: String,
    val domain: String,
    val timestamp: Long
)

/** خروجی گروه‌بندی‌شده برای نمایش: هر برنامه + لیست دامنه‌های یکتای آن */
data class AppDomainsGroup(
    val appName: String,
    val packageName: String,
    val domain: String,
    val count: Int,
    val lastSeen: Long
)

@Dao
interface DomainConnectionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: DomainConnectionEntity)

    // گروه‌بندی بر اساس برنامه و دامنه، به همراه تعداد دفعات و آخرین زمان مشاهده
    @Query(
        """
        SELECT appName, packageName, domain, COUNT(*) as count, MAX(timestamp) as lastSeen
        FROM domain_connections
        GROUP BY packageName, domain
        ORDER BY appName ASC, lastSeen DESC
        """
    )
    fun observeGrouped(): Flow<List<AppDomainsGroup>>

    @Query("DELETE FROM domain_connections")
    suspend fun clearAll()
}

@Database(entities = [DomainConnectionEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun domainConnectionDao(): DomainConnectionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "netmonitor.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

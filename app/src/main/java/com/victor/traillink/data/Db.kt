package com.victor.traillink.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "nodes")
data class NodeEntity(
    @PrimaryKey val num: Long,
    val longName: String,
    val shortName: String,
    val lastHeard: Long = 0,        // ms epoch, when any packet was last received
    val lat: Double? = null,
    val lon: Double? = null,
    val altM: Int? = null,
    val posTime: Long = 0,          // ms epoch of last position
    val snr: Float? = null,
    val rssi: Int? = null,
    val battery: Int? = null,       // 0-100, 101 = powered
    val voltage: Float? = null,
    val hops: Int? = null,
    val isSelf: Boolean = false,    // the radio this phone is connected to
    val demo: Boolean = false,      // legacy column (demo mode removed in 1.2; rows purged on upgrade)
    val alarmMuted: Boolean = false,
    val posSource: String? = null,  // "lora", "net" (phone GPS over internet) or "gw" (radio position forwarded by a gateway)
    val netId: String? = null,      // internet sender id, for phones without a radio
    val accM: Float? = null,        // reported accuracy of the last position (internet partners)
)

@Entity(tableName = "points", indices = [Index("nodeNum", "time")])
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nodeNum: Long,
    val time: Long,                 // ms epoch
    val lat: Double,
    val lon: Double,
    val altM: Int? = null,
    val snr: Float? = null,
    val rssi: Int? = null,
    val battery: Int? = null,
    val demo: Boolean = false,
    /** 2.5: recorded while the partner was out of service and sent later (drawn dashed) */
    @androidx.room.ColumnInfo(defaultValue = "0") val bf: Boolean = false,
)

/** Chat message (group or direct), also used for voice clips (kind = "voice"). */
@Entity(tableName = "messages", indices = [Index("convo", "time")])
data class MessageEntity(
    @PrimaryKey val id: String,          // 8 hex = Meshtastic packet id, same on LoRa and internet
    val convo: String,                   // "group" or the peer key ("n:<installId>" / "l:<nodeNum>")
    val fromKey: String,                 // "me" or peer key
    val fromName: String,
    val toKey: String?,                  // null = group
    val text: String,
    val time: Long,
    val mine: Boolean,
    val status: Int = 0,                 // mine: 0 sending, 1 sent, 2 delivered, 3 read, -1 not sent
    val sos: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
    val via: String = "",                // "net", "lora", "net+lora"
    val read: Boolean = true,            // incoming: seen by the user
    val bridged: Boolean = false,        // LoRa message this phone forwarded to the internet
    val kind: String = "text",           // "text" | "voice"
    val clipFile: String? = null,        // voice: local file
    val durMs: Long? = null,
)

/** Shared pin / waypoint. id = 8 hex of the Meshtastic Waypoint id (uint32), same on LoRa and internet. */
@Entity(tableName = "pins")
data class PinEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,                 // see core.PinKind
    val color: Int,
    val note: String = "",
    val lat: Double,
    val lon: Double,
    val alt: Int? = null,
    val byKey: String,                // "me", "n:<installId>", "l:<nodeNum>"
    val byName: String,
    val created: Long,
    val updated: Long,
    val expires: Long = 0,            // ms epoch, 0 = never
    val shared: Boolean = true,
    val mine: Boolean = false,
    val hidden: Boolean = false,      // "remove from my map" (others' pins)
    val deleted: Boolean = false,     // tombstone
    val emoji: String? = null,        // icon from a stock Meshtastic app that isn't one of our kinds
)

@Dao
interface PinDao {
    @Query("SELECT * FROM pins WHERE deleted = 0 AND hidden = 0 AND (expires = 0 OR expires > :now) ORDER BY created DESC") fun visible(now: Long): Flow<List<PinEntity>>
    @Query("SELECT * FROM pins WHERE id = :id") suspend fun get(id: String): PinEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(p: PinEntity)
    @Query("SELECT * FROM pins WHERE mine = 1 AND shared = 1") suspend fun mineShared(): List<PinEntity>
    @Query("DELETE FROM pins WHERE byKey = :key AND mine = 0") suspend fun deleteBy(key: String): Int
    @Query("SELECT * FROM pins WHERE deleted = 0 AND hidden = 0 AND (expires = 0 OR expires > :now)") suspend fun visibleNow(now: Long): List<PinEntity>
}

@Dao
interface MsgDao {
    @Query("SELECT * FROM messages WHERE convo = :convo ORDER BY time") fun convo(convo: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages ORDER BY time DESC LIMIT 500") fun recent(): Flow<List<MessageEntity>>
    @Query("SELECT COUNT(*) FROM messages WHERE mine = 0 AND read = 0") fun unread(): Flow<Int>
    @Query("SELECT * FROM messages WHERE id = :id") suspend fun get(id: String): MessageEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(m: MessageEntity): Long
    @Update suspend fun update(m: MessageEntity)
    @Query("UPDATE messages SET status = :st WHERE id = :id AND mine = 1 AND status < :st") suspend fun raiseStatus(id: String, st: Int)
    @Query("SELECT * FROM messages WHERE convo = :convo AND mine = 0 AND read = 0") suspend fun unreadIn(convo: String): List<MessageEntity>
    @Query("UPDATE messages SET read = 1 WHERE convo = :convo AND mine = 0") suspend fun markRead(convo: String)
    @Query("SELECT * FROM messages WHERE time >= :since AND (mine = 1 OR bridged = 1) AND kind = 'text' ORDER BY time") suspend fun outboxSince(since: Long): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE sos = 1 AND lat IS NOT NULL AND time >= :since ORDER BY time") fun sosSince(since: Long): Flow<List<MessageEntity>>
    @Query("DELETE FROM messages WHERE convo = :convo") suspend fun clearConvo(convo: String)
    @Query("SELECT clipFile FROM messages WHERE convo = :convo AND clipFile IS NOT NULL") suspend fun clipsIn(convo: String): List<String>
    @Query("DELETE FROM messages WHERE time < :before") suspend fun prune(before: Long)
}

@Dao
interface TrailDao {
    @Query("SELECT * FROM nodes ORDER BY isSelf DESC, lastHeard DESC")
    fun nodes(): Flow<List<NodeEntity>>

    @Query("SELECT * FROM nodes")
    suspend fun nodesNow(): List<NodeEntity>

    @Query("SELECT * FROM nodes WHERE num = :num")
    suspend fun node(num: Long): NodeEntity?

    @Upsert suspend fun upsert(n: NodeEntity)

    @Insert suspend fun insert(p: TrackPoint)

    @Query("SELECT * FROM points WHERE time >= :since ORDER BY nodeNum, time")
    fun pointsSince(since: Long): Flow<List<TrackPoint>>

    @Query("SELECT * FROM points WHERE time >= :since ORDER BY nodeNum, time")
    suspend fun pointsSinceNow(since: Long): List<TrackPoint>

    @Query("SELECT time FROM points WHERE nodeNum = :num AND time BETWEEN :from AND :to")
    suspend fun pointTimes(num: Long, from: Long, to: Long): List<Long>

    @Query("SELECT * FROM points WHERE nodeNum = :num ORDER BY time DESC LIMIT 1")
    suspend fun lastPoint(num: Long): TrackPoint?

    @Query("UPDATE nodes SET alarmMuted = :muted WHERE num = :num")
    suspend fun setMuted(num: Long, muted: Boolean)

    @Query("DELETE FROM points WHERE demo = 0") suspend fun clearRealPoints()
    // 2.2: partners' tracks only (my own recorded track is nodeNum = MyTrack.NUM = -1)
    @Query("DELETE FROM points WHERE demo = 0 AND nodeNum >= 0") suspend fun clearPartnerPoints()
    @Query("SELECT * FROM points WHERE nodeNum = -1 AND time >= :since ORDER BY time") fun myTrackSince(since: Long): Flow<List<TrackPoint>>
    @Query("DELETE FROM points WHERE time < :before") suspend fun prune(before: Long)
    @Query("DELETE FROM nodes WHERE num = :num") suspend fun deleteNode(num: Long)
    @Query("DELETE FROM points WHERE nodeNum = :num") suspend fun deletePoints(num: Long)
    @Query("UPDATE nodes SET isSelf = 0 WHERE num != :num") suspend fun clearSelfExcept(num: Long)
}

@Database(entities = [NodeEntity::class, TrackPoint::class, MessageEntity::class, PinEntity::class], version = 7, exportSchema = false)
abstract class TrailDb : RoomDatabase() {
    abstract fun dao(): TrailDao
    abstract fun msgs(): MsgDao
    abstract fun pins(): PinDao

    companion object {
        @Volatile private var inst: TrailDb? = null
        fun get(ctx: Context): TrailDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, TrailDb::class.java, "trail.db").addMigrations(PURGE_DEMO_2_3, ADD_ACC_3_4, ADD_MESSAGES_4_5, ADD_PINS_5_6, ADD_BACKFILL_6_7).fallbackToDestructiveMigration().build().also { inst = it }
        }
    }
}

/** 1.1 -> 1.2: demo mode removed; purge any simulated partners and trails, keep everything real. */
val PURGE_DEMO_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM points WHERE demo = 1 OR nodeNum >= 3724541952 AND nodeNum <= 3724541967")
        db.execSQL("DELETE FROM nodes WHERE demo = 1 OR num >= 3724541952 AND num <= 3724541967 OR netId = 'de00lynx'")
    }
}

/** 1.2 -> 1.3: partner position accuracy (keeps all data). */
val ADD_ACC_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("ALTER TABLE nodes ADD COLUMN accM REAL") }
}

/** 1.3 -> 1.4: messages table (keeps all data). SQL copied from Room's generated schema. */
val ADD_MESSAGES_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        MESSAGES_SQL.forEach { db.execSQL(it) }
    }
}
val MESSAGES_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `convo` TEXT NOT NULL, `fromKey` TEXT NOT NULL, `fromName` TEXT NOT NULL, `toKey` TEXT, `text` TEXT NOT NULL, `time` INTEGER NOT NULL, `mine` INTEGER NOT NULL, `status` INTEGER NOT NULL, `sos` INTEGER NOT NULL, `lat` REAL, `lon` REAL, `via` TEXT NOT NULL, `read` INTEGER NOT NULL, `bridged` INTEGER NOT NULL, `kind` TEXT NOT NULL, `clipFile` TEXT, `durMs` INTEGER, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_messages_convo_time` ON `messages` (`convo`, `time`)"
)

/** 1.5 -> 1.6: pins table (keeps all data). SQL copied from Room's generated schema. */
val ADD_PINS_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { PINS_SQL.forEach { db.execSQL(it) } }
}
val PINS_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `pins` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `color` INTEGER NOT NULL, `note` TEXT NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `alt` INTEGER, `byKey` TEXT NOT NULL, `byName` TEXT NOT NULL, `created` INTEGER NOT NULL, `updated` INTEGER NOT NULL, `expires` INTEGER NOT NULL, `shared` INTEGER NOT NULL, `mine` INTEGER NOT NULL, `hidden` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, `emoji` TEXT, PRIMARY KEY(`id`))"
)

/** 2.4 -> 2.5: backfilled-point flag on trail points (keeps all data). */
val ADD_BACKFILL_6_7 = object : androidx.room.migration.Migration(6, 7) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("ALTER TABLE points ADD COLUMN bf INTEGER NOT NULL DEFAULT 0") }
}

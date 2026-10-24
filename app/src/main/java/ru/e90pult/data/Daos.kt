package ru.e90pult.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY id")
    fun all(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY id")
    suspend fun allNow(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: Long): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun observe(id: Long): Flow<ProfileEntity?>

    @Insert
    suspend fun insert(p: ProfileEntity): Long

    @Update
    suspend fun update(p: ProfileEntity)

    @Query("UPDATE profiles SET moduleFw = :fw WHERE id = :id")
    suspend fun setFw(id: Long, fw: String)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SmsDao {
    @Insert
    suspend fun insert(s: SmsEntity): Long

    @Query("SELECT * FROM sms WHERE id = :id")
    suspend fun get(id: Long): SmsEntity?

    @Query("UPDATE sms SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("SELECT * FROM sms WHERE profileId = :pid AND (emulated = 0 OR :emu) ORDER BY timestamp DESC LIMIT :limit")
    fun recent(pid: Long, emu: Boolean, limit: Int): Flow<List<SmsEntity>>

    @Query("SELECT * FROM sms WHERE profileId = :pid ORDER BY timestamp")
    suspend fun allForExport(pid: Long): List<SmsEntity>

    @Query("SELECT * FROM sms WHERE runId = :runId ORDER BY timestamp")
    fun byRun(runId: Long): Flow<List<SmsEntity>>

    /**
     * Тот же SMS, уже принятый другим путём (приёмник / досканирование базы SMS).
     * Если время SMS-центра известно у обоих — сравнивается оно (±10 с), иначе время получения (±2 мин).
     * Разные SMS с одинаковым текстом (повторный ответ на ту же команду) так не теряются.
     */
    @Query(
        "SELECT COUNT(*) FROM sms WHERE outgoing = 0 AND emulated = 0 AND phone = :phone AND text = :text AND " +
            "((:sc > 0 AND scTimestamp > 0 AND scTimestamp BETWEEN :sc - 10000 AND :sc + 10000) OR " +
            "((:sc = 0 OR scTimestamp = 0) AND timestamp BETWEEN :ts - 120000 AND :ts + 120000))"
    )
    suspend fun countSimilarIncoming(phone: String, text: String, ts: Long, sc: Long): Int

    /** Частей SMS, отправленных телефоном (для счётчика); эмуляция и неотправленные не считаются. */
    @Query("SELECT COALESCE(SUM(parts), 0) FROM sms WHERE outgoing = 1 AND emulated = 0 AND status != 'SEND_FAILED' AND timestamp >= :from")
    fun sentPartsSince(from: Long): Flow<Int>

    @Query("SELECT COALESCE(SUM(parts), 0) FROM sms WHERE outgoing = 1 AND emulated = 0 AND status != 'SEND_FAILED' AND timestamp >= :from")
    suspend fun sentPartsSinceNow(from: Long): Int

    /** Частей SMS, отправленных модулем (входящих) начиная с момента [from] — для оценки баланса. */
    @Query("SELECT COALESCE(SUM(parts), 0) FROM sms WHERE profileId = :pid AND outgoing = 0 AND emulated = :emu AND timestamp >= :from")
    suspend fun incomingPartsSince(pid: Long, from: Long, emu: Boolean): Int

    @Query("DELETE FROM sms WHERE emulated = 1")
    suspend fun deleteEmulated()

    @Query("DELETE FROM sms WHERE profileId = :pid")
    suspend fun deleteForProfile(pid: Long)
}

@Dao
interface RunDao {
    @Insert
    suspend fun insert(r: CommandRunEntity): Long

    @Update
    suspend fun update(r: CommandRunEntity)

    @Query("SELECT * FROM runs WHERE id = :id")
    suspend fun get(id: Long): CommandRunEntity?

    @Query("SELECT * FROM runs WHERE id = :id")
    fun observe(id: Long): Flow<CommandRunEntity?>

    @Query("SELECT * FROM runs WHERE profileId = :pid AND state IN ('SENDING', 'SENT', 'DELIVERED') ORDER BY createdAt")
    suspend fun pending(pid: Long): List<CommandRunEntity>

    @Query("SELECT * FROM runs WHERE profileId = :pid AND state IN ('SENDING', 'SENT', 'DELIVERED') ORDER BY createdAt")
    fun pendingFlow(pid: Long): Flow<List<CommandRunEntity>>

    @Query("SELECT * FROM runs WHERE profileId = :pid AND state IN ('NO_REPLY', 'CANCELLED') AND deadline >= :since ORDER BY createdAt")
    suspend fun unansweredSince(pid: Long, since: Long): List<CommandRunEntity>

    @Query("SELECT * FROM runs WHERE profileId = :pid AND kind = 'FIRMWARE_UPDATE' AND sessionUntil >= :now ORDER BY createdAt DESC LIMIT 1")
    suspend fun activeUpdate(pid: Long, now: Long): CommandRunEntity?

    @Query("SELECT * FROM runs WHERE profileId = :pid AND kind = 'FIRMWARE_UPDATE' ORDER BY createdAt DESC LIMIT 1")
    fun latestUpdate(pid: Long): Flow<CommandRunEntity?>

    @Query("SELECT * FROM runs WHERE state IN ('SENDING', 'SENT', 'DELIVERED') AND deadline < :now")
    suspend fun overdue(now: Long): List<CommandRunEntity>

    @Query("SELECT * FROM runs WHERE kind = 'FIRMWARE_UPDATE' AND sessionUntil > 0 AND sessionUntil < :now AND fwAfter = '' AND state != 'CANCELLED'")
    suspend fun expiredUpdateSessions(now: Long): List<CommandRunEntity>

    @Query("SELECT * FROM runs WHERE profileId = :pid AND (emulated = 0 OR :emu) ORDER BY createdAt DESC LIMIT :limit")
    fun latest(pid: Long, emu: Boolean, limit: Int): Flow<List<CommandRunEntity>>

    @Query("SELECT * FROM runs WHERE profileId = :pid ORDER BY createdAt")
    suspend fun allForProfile(pid: Long): List<CommandRunEntity>

    @Query("DELETE FROM runs WHERE emulated = 1")
    suspend fun deleteEmulated()

    @Query("DELETE FROM runs WHERE profileId = :pid")
    suspend fun deleteForProfile(pid: Long)
}

@Dao
interface ObsDao {
    @Insert
    suspend fun insertAll(items: List<ObservationEntity>)

    /** Все значения за период по ключам (для карточки берётся последнее по каждому ключу). */
    @Query(
        "SELECT o.* FROM obs o JOIN (SELECT obsKey AS k, MAX(timestamp) AS ts FROM obs " +
            "WHERE profileId = :pid AND (emulated = 0 OR :emu) GROUP BY obsKey) m " +
            "ON o.obsKey = m.k AND o.timestamp = m.ts WHERE o.profileId = :pid AND (o.emulated = 0 OR :emu)"
    )
    fun latest(pid: Long, emu: Boolean): Flow<List<ObservationEntity>>

    @Query("SELECT * FROM obs WHERE profileId = :pid AND obsKey = :key AND (emulated = 0 OR :emu) ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestOf(pid: Long, key: String, emu: Boolean): ObservationEntity?

    @Query("SELECT * FROM obs WHERE profileId = :pid AND obsKey = :key AND timestamp >= :from AND (emulated = 0 OR :emu) ORDER BY timestamp")
    fun series(pid: Long, key: String, from: Long, emu: Boolean): Flow<List<ObservationEntity>>

    @Query("DELETE FROM obs WHERE emulated = 1")
    suspend fun deleteEmulated()

    @Query("DELETE FROM obs WHERE profileId = :pid")
    suspend fun deleteForProfile(pid: Long)
}

@Dao
interface BalanceDao {
    @Insert
    suspend fun insert(e: BalanceEventEntity): Long

    @Query("SELECT * FROM balance WHERE profileId = :pid AND (emulated = 0 OR :emu) ORDER BY timestamp DESC LIMIT 50")
    fun recent(pid: Long, emu: Boolean): Flow<List<BalanceEventEntity>>

    /** Последний известный баланс: от модуля или введённый вручную. */
    @Query("SELECT * FROM balance WHERE profileId = :pid AND type IN ('REPORTED', 'MANUAL') AND emulated = :emu ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastKnown(pid: Long, emu: Boolean): BalanceEventEntity?

    @Query("SELECT COALESCE(SUM(amount), 0) FROM balance WHERE profileId = :pid AND type = 'TOPUP' AND emulated = :emu AND timestamp > :from")
    suspend fun topupsSince(pid: Long, from: Long, emu: Boolean): Double

    @Query("DELETE FROM balance WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM balance WHERE emulated = 1")
    suspend fun deleteEmulated()

    @Query("DELETE FROM balance WHERE profileId = :pid")
    suspend fun deleteForProfile(pid: Long)
}

package ru.e90pult.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.e90pult.core.Availability
import ru.e90pult.core.BalanceEstimate
import ru.e90pult.core.BalanceInputs
import ru.e90pult.core.BalanceMath
import ru.e90pult.core.BlockKind
import ru.e90pult.core.Built
import ru.e90pult.core.CmdResult
import ru.e90pult.core.CommandContext
import ru.e90pult.core.CommandDef
import ru.e90pult.core.CommandKind
import ru.e90pult.core.CommandText
import ru.e90pult.core.Describe
import ru.e90pult.core.EmuMode
import ru.e90pult.core.FirmwareNews
import ru.e90pult.core.FwVersion
import ru.e90pult.core.Matching
import ru.e90pult.core.ObsKey
import ru.e90pult.core.Observations
import ru.e90pult.core.PendingRun
import ru.e90pult.core.Phone
import ru.e90pult.core.ReplyEmulator
import ru.e90pult.core.ResponseParser
import ru.e90pult.core.Rules
import ru.e90pult.core.SendOptions
import ru.e90pult.data.AppDatabase
import ru.e90pult.data.BalanceEventEntity
import ru.e90pult.data.BalanceType
import ru.e90pult.data.CommandRepo
import ru.e90pult.data.CommandRunEntity
import ru.e90pult.data.ObservationEntity
import ru.e90pult.data.Prefs
import ru.e90pult.data.ProfileEntity
import ru.e90pult.data.RunState
import ru.e90pult.data.SecretBox
import ru.e90pult.data.SmsEntity
import ru.e90pult.data.SmsKind
import ru.e90pult.data.SmsStatus
import ru.e90pult.safety.PinStore
import ru.e90pult.safety.SafetyMonitor
import ru.e90pult.sms.Delivery
import ru.e90pult.sms.InboxScanner
import ru.e90pult.sms.SmsGateway
import ru.e90pult.sms.TimeoutReceiver
import ru.e90pult.widget.WidgetUpdater
import java.time.LocalDate
import java.time.ZoneId

/**
 * Отправка команд, приём ответов, привязка ответа к команде, тайм-ауты, сеанс обновления ПО,
 * наблюдения для карточки состояния, баланс, счётчик SMS, уведомления.
 */
class Engine(
    private val context: Context,
    private val db: AppDatabase,
    private val prefs: Prefs,
    private val notifier: Notifier,
    private val commands: CommandRepo,
    private val gateway: SmsGateway,
    private val inbox: InboxScanner,
    private val widgets: WidgetUpdater,
    private val safety: SafetyMonitor,
    private val pin: PinStore,
    private val scope: CoroutineScope
) {
    private val mutex = Mutex()
    private val emulator = ReplyEmulator()

    sealed class SendResult {
        data class Ok(val runId: Long, val warning: String? = null) : SendResult()
        data class Error(val message: String) : SendResult()
    }

    data class BalanceView(
        val last: BalanceEventEntity?,
        val estimate: BalanceEstimate?,
        /** Баланс давно не запрашивали. */
        val stale: Boolean,
        /** Оценка ниже порога. */
        val low: Boolean
    )

    companion object {
        const val UPDATE_SESSION_MS = 30 * 60_000L
        const val LATE_WINDOW_MS = 30 * 60_000L
        const val QUIET_INBOX_MS = 15 * 60_000L
        const val EMU_PHONE = "эмулятор"
        private val O7 = FwVersion('O', 7)

        fun startOfDay(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun startOfMonth(): Long =
            LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun todayKey(): Long = LocalDate.now().toEpochDay()
        fun money(v: Double): String = Describe.volts(v)
    }

    // ---------- Параметры отправки ----------

    fun password(p: ProfileEntity): String? = SecretBox.decrypt(p.passwordEnc)?.takeIf { it.isNotEmpty() }

    fun options(p: ProfileEntity): SendOptions {
        val fw = FwVersion.parse(p.moduleFw)
        val hash = when (p.hashMode) {
            1 -> true
            2 -> false
            else -> fw != null && fw >= O7
        }
        return SendOptions(p.passwordMode, password(p), hash, p.ussd.ifBlank { "*100#" })
    }

    /** Текст SMS, который будет отправлен — для показа в подтверждении. */
    fun preview(p: ProfileEntity, def: CommandDef, params: Map<String, String>): Built =
        CommandText.build(def, params, options(p))

    /** Версия ПО модуля: последнее значение из ответов (в эмуляции — с учётом эмулированных), иначе из профиля. */
    suspend fun effectiveFw(p: ProfileEntity, emu: Boolean = prefs.value.emulator): FwVersion? =
        FwVersion.parse(db.obs().latestOf(p.id, ObsKey.FW.name, emu)?.text) ?: FwVersion.parse(p.moduleFw)

    // ---------- Отправка ----------

    /**
     * Защита на стороне движка — повторяет проверки интерфейса: версия ПО, пароль, движение,
     * Bluetooth машины, ПИН для критичных команд. [emergency] — аварийное включение антиограбления в движении.
     */
    private fun guard(def: CommandDef, p: ProfileEntity, fw: FwVersion?, emergency: Boolean): String? {
        val s = prefs.value
        val st = safety.state.value
        val ctx = CommandContext(
            moduleFw = fw,
            passwordMode = p.passwordMode,
            hasPassword = password(p) != null,
            moving = s.motionGps && st.moving,
            inCar = s.motionBt && st.inCar
        )
        val a = Rules.availability(def, ctx)
        if (a is Availability.Disabled) {
            val emergencyOk = emergency && a.kind == BlockKind.MOTION && def.code == Rules.ANTIHIJACK_CODE
            if (!emergencyOk) return a.reason
        }
        if (def.critical && def.kind != CommandKind.ANTIHIJACK_OFF && !pin.isUnlocked()) {
            return "Критичная команда: сначала откройте раздел «Критичное» ПИН-кодом."
        }
        return null
    }

    suspend fun send(
        profileId: Long,
        def: CommandDef,
        params: Map<String, String> = emptyMap(),
        emergency: Boolean = false
    ): SendResult {
        val p = db.profiles().get(profileId) ?: return SendResult.Error("Профиль не найден.")
        val s = prefs.value
        val emu = s.emulator
        if (!emu) {
            if (!Phone.isValid(p.alarmPhone)) return SendResult.Error("Укажите номер SIM сигнализации в профиле.")
            if (!gateway.hasSendPermission()) return SendResult.Error("Нет разрешения на отправку SMS.")
        }
        val fw = effectiveFw(p, emu)
        guard(def, p, fw, emergency)?.let { return SendResult.Error(it) }
        val text = when (val b = CommandText.build(def, params, options(p))) {
            is Built.Ok -> b.text
            is Built.Error -> return SendResult.Error(b.message)
        }
        return dispatch(p, def, text, fw, emu)
    }

    /** Повтор вручную: тот же текст SMS новой отправкой (с проверкой доступности). */
    suspend fun resend(runId: Long): SendResult {
        val old = db.runs().get(runId) ?: return SendResult.Error("Отправка не найдена.")
        val p = db.profiles().get(old.profileId) ?: return SendResult.Error("Профиль не найден.")
        val emu = prefs.value.emulator
        if (!emu) {
            if (!Phone.isValid(p.alarmPhone)) return SendResult.Error("Укажите номер SIM сигнализации в профиле.")
            if (!gateway.hasSendPermission()) return SendResult.Error("Нет разрешения на отправку SMS.")
        }
        if (old.emulated != emu) {
            return SendResult.Error(
                if (old.emulated) "Это была эмулированная отправка: сейчас повтор ушёл бы настоящим SMS. Отправьте команду заново."
                else "Сейчас включён эмулятор: повтор настоящей команды недоступен."
            )
        }
        val def = commands.find(p, old.code)
            ?: return SendResult.Error("Команды ${old.code} нет в таблице — повтор недоступен.")
        if (def.critical || def.dangerous || def.params.isNotEmpty()) {
            return SendResult.Error("Эта команда повторяется только через окно команды с полным подтверждением.")
        }
        val fw = effectiveFw(p, emu)
        guard(def, p, fw, emergency = false)?.let { return SendResult.Error(it) }
        return dispatch(p, def, old.smsText, fw, emu)
    }

    private suspend fun dispatch(p: ProfileEntity, def: CommandDef, text: String, fw: FwVersion?, emu: Boolean): SendResult {
        if (def.kind == CommandKind.FIRMWARE_UPDATE && db.obs().latestOf(p.id, ObsKey.ARMED.name, emu)?.num == 1.0) {
            return SendResult.Error(
                "По последнему статусу охрана включена. Снимите с охраны (10) или запросите состояние (41), затем повторите."
            )
        }
        val s = prefs.value
        val now = System.currentTimeMillis()
        val timeout = s.replyTimeoutMin.coerceIn(1, 60) * 60_000L
        val isUpdate = def.kind == CommandKind.FIRMWARE_UPDATE
        val parts = SmsGateway.partsOf(text)
        val phone = if (emu && p.alarmPhone.isBlank()) EMU_PHONE else p.alarmPhone
        val ids = mutex.withLock {
            val runId = db.runs().insert(
                CommandRunEntity(
                    profileId = p.id,
                    code = def.code,
                    name = def.name,
                    smsText = text,
                    kind = def.kind.name,
                    control = def.control,
                    createdAt = now,
                    deadline = now + timeout,
                    state = RunState.SENDING,
                    partsTotal = parts,
                    emulated = emu,
                    sessionUntil = if (isUpdate) now + UPDATE_SESSION_MS else 0L,
                    fwBefore = if (isUpdate) fw?.toString().orEmpty() else ""
                )
            )
            val smsId = db.sms().insert(
                SmsEntity(
                    profileId = p.id,
                    outgoing = true,
                    phone = phone,
                    text = text,
                    timestamp = now,
                    parts = parts,
                    kind = SmsKind.COMMAND,
                    runId = runId,
                    status = SmsStatus.SENDING,
                    emulated = emu,
                    source = if (emu) "EMULATOR" else "APP"
                )
            )
            runId to smsId
        }
        val runId = ids.first
        val smsId = ids.second
        TimeoutReceiver.schedule(context, runId, now + timeout, TimeoutReceiver.TYPE_REPLY)
        if (isUpdate) TimeoutReceiver.schedule(context, runId, now + UPDATE_SESSION_MS, TimeoutReceiver.TYPE_SESSION)
        widgets.requestUpdate()

        if (emu) {
            scope.launch { emulate(p, def, runId, smsId) }
            return SendResult.Ok(runId)
        }
        return try {
            withContext(Dispatchers.Main) { gateway.send(runId, smsId, p.alarmPhone, text, p.subscriptionId) }
            SendResult.Ok(runId, checkLimits())
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            onSent(runId, smsId, false, msg)
            SendResult.Error("Не удалось отправить: $msg")
        }
    }

    private suspend fun emulate(p: ProfileEntity, def: CommandDef, runId: Long, smsId: Long) {
        val s = prefs.value
        val mode = EmuMode.entries.firstOrNull { it.name == s.emuMode } ?: EmuMode.SUCCESS
        val pause = s.emuDelaySec.coerceIn(1, 120) * 1000L
        delay(700)
        onSent(runId, smsId, true, "")
        delay(700)
        onDelivered(runId, smsId, Delivery.OK)
        delay(pause)
        val fw = (effectiveFw(p, true) ?: FwVersion('I', 7)).toString()
        val reply = emulator.reply(def, mode, fw) ?: return
        val t1 = System.currentTimeMillis()
        onIncoming(p.alarmPhone, reply, SmsGateway.partsOf(reply), t1, t1, "EMULATOR", emulated = true, forcedProfileId = p.id)
        if (def.kind == CommandKind.FIRMWARE_UPDATE && mode != EmuMode.FAILURE) {
            delay(pause)
            val greeting = emulator.greeting(s.emuFwAfterUpdate.ifBlank { "M8" })
            val t2 = System.currentTimeMillis()
            onIncoming(p.alarmPhone, greeting, SmsGateway.partsOf(greeting), t2, t2, "EMULATOR", emulated = true, forcedProfileId = p.id)
        }
    }

    /** Входящее без команды — проверка уведомления о событии в режиме отладки. */
    suspend fun emulateUnsolicited(profileId: Long) {
        val p = db.profiles().get(profileId) ?: return
        val text = emulator.unsolicited()
        val t = System.currentTimeMillis()
        onIncoming(p.alarmPhone, text, 1, t, t, "EMULATOR", emulated = true, forcedProfileId = p.id)
    }

    // ---------- Отчёты об отправке и доставке ----------

    suspend fun onSent(runId: Long, smsId: Long, ok: Boolean, error: String) {
        var failedRun: CommandRunEntity? = null
        mutex.withLock {
            val run = db.runs().get(runId) ?: return
            if (!ok) {
                db.sms().setStatus(smsId, SmsStatus.SEND_FAILED)
                if (run.state in RunState.PENDING) {
                    val upd = run.copy(state = RunState.FAILED, error = "Не отправлено: $error", sessionUntil = 0L)
                    db.runs().update(upd)
                    TimeoutReceiver.cancel(context, runId, TimeoutReceiver.TYPE_REPLY)
                    TimeoutReceiver.cancel(context, runId, TimeoutReceiver.TYPE_SESSION)
                    failedRun = upd
                }
                return@withLock
            }
            val sentParts = run.partsSent + 1
            var upd = run.copy(partsSent = sentParts)
            if (sentParts >= run.partsTotal) {
                upd = upd.copy(
                    sentAt = run.sentAt ?: System.currentTimeMillis(),
                    state = if (run.state == RunState.SENDING) RunState.SENT else run.state
                )
                val sms = db.sms().get(smsId)
                if (sms != null && sms.status == SmsStatus.SENDING) db.sms().setStatus(smsId, SmsStatus.SENT)
            }
            db.runs().update(upd)
        }
        failedRun?.let {
            notifier.post(
                Notifier.ID_WARN_BASE + (it.id % 100_000).toInt(), Notifier.CH_WARN,
                "Не отправлено: ${it.name}", it.error, "run:${it.id}"
            )
        }
        widgets.requestUpdate()
    }

    suspend fun onDelivered(runId: Long, smsId: Long, status: Delivery) {
        var failedRun: CommandRunEntity? = null
        mutex.withLock {
            val run = db.runs().get(runId) ?: return
            when (status) {
                Delivery.PENDING -> Unit
                Delivery.OK -> {
                    val delivered = run.partsDelivered + 1
                    var upd = run.copy(partsDelivered = delivered)
                    if (delivered >= run.partsTotal) {
                        upd = upd.copy(
                            deliveredAt = System.currentTimeMillis(),
                            state = if (run.state == RunState.SENT || run.state == RunState.SENDING) RunState.DELIVERED else run.state
                        )
                        db.sms().setStatus(smsId, SmsStatus.DELIVERED)
                    }
                    db.runs().update(upd)
                }
                Delivery.FAILED -> {
                    db.sms().setStatus(smsId, SmsStatus.DELIVERY_FAILED)
                    if (run.state in RunState.PENDING) {
                        val upd = run.copy(
                            state = RunState.FAILED,
                            error = "Оператор сообщил: SMS не доставлено",
                            sessionUntil = if (run.kind == CommandKind.FIRMWARE_UPDATE.name) 0L else run.sessionUntil
                        )
                        db.runs().update(upd)
                        TimeoutReceiver.cancel(context, runId, TimeoutReceiver.TYPE_REPLY)
                        TimeoutReceiver.cancel(context, runId, TimeoutReceiver.TYPE_SESSION)
                        failedRun = upd
                    }
                }
            }
        }
        failedRun?.let {
            notifier.post(
                Notifier.ID_WARN_BASE + (it.id % 100_000).toInt(), Notifier.CH_WARN,
                "Не доставлено: ${it.name}", it.error, "run:${it.id}"
            )
        }
        widgets.requestUpdate()
    }

    // ---------- Приём ----------

    suspend fun onIncoming(
        from: String,
        body: String,
        parts: Int,
        receivedAt: Long,
        scTs: Long,
        source: String,
        emulated: Boolean = false,
        forcedProfileId: Long? = null
    ) {
        var notify: (() -> Unit)? = null
        var checkProfile: ProfileEntity? = null
        mutex.withLock {
            val profile = (if (forcedProfileId != null) db.profiles().get(forcedProfileId) else null)
                ?: db.profiles().allNow().firstOrNull { Phone.same(it.alarmPhone, from) }
                ?: return
            val phone = Phone.normalize(from).ifEmpty { profile.alarmPhone }.ifEmpty { EMU_PHONE }
            if (!emulated && db.sms().countSimilarIncoming(phone, body, receivedAt, scTs) > 0) return
            val parsed = ResponseParser.parse(body)

            val pending = db.runs().pending(profile.id).filter { it.emulated == emulated }
            val recent = db.runs().unansweredSince(profile.id, receivedAt - LATE_WINDOW_MS - 3_600_000L)
                .filter { it.emulated == emulated }
            // «Нет ответа» могли поставить раньше, чем досканировали ответ, пришедший вовремя.
            val candidates = pending + recent.filter { it.state == RunState.NO_REPLY }
            val picked = Matching.pick(candidates.map { PendingRun(it.id, it.sentAt ?: it.createdAt, it.deadline) }, receivedAt)
            val update = db.runs().activeUpdate(profile.id, receivedAt)?.takeIf { it.emulated == emulated }
            var kind = SmsKind.EVENT
            var run: CommandRunEntity? = null
            var note = ""
            if (update != null && (parsed.greeting || parsed.updateMention)) {
                // Приветствие и сообщения об обновлении во время сеанса относятся к 00591,
                // даже если сейчас ждёт ответа другая команда (например, 09).
                run = update
                kind = if (update.state in RunState.PENDING || update.state == RunState.NO_REPLY) SmsKind.REPLY else SmsKind.UPDATE
            } else if (picked != null) {
                run = candidates.first { it.id == picked.id }
                kind = SmsKind.REPLY
            } else if (update != null && update.state == RunState.REPLIED) {
                run = update
                kind = SmsKind.UPDATE
            } else {
                // Поздний ответ: окно закрыто (будильник «нет ответа» мог ещё не сработать).
                val lateCandidates = recent + pending.filter { it.deadline < receivedAt }
                val late = Matching.lateCandidate(
                    lateCandidates.map { PendingRun(it.id, it.sentAt ?: it.createdAt, it.deadline) }, receivedAt, LATE_WINDOW_MS
                )
                if (late != null) {
                    val lr = lateCandidates.first { it.id == late.id }
                    note = "Возможно, поздний ответ на «${lr.name}» (${lr.code})"
                }
            }

            val smsId = db.sms().insert(
                SmsEntity(
                    profileId = profile.id,
                    outgoing = false,
                    phone = phone,
                    text = body,
                    timestamp = receivedAt,
                    scTimestamp = scTs,
                    parts = parts.coerceAtLeast(1),
                    kind = kind,
                    runId = run?.id,
                    status = SmsStatus.RECEIVED,
                    emulated = emulated,
                    source = source,
                    note = note
                )
            )

            val isBalanceReply = kind == SmsKind.REPLY && run?.kind == CommandKind.USSD_BALANCE.name
            val prevFw = FwVersion.parse(db.obs().latestOf(profile.id, ObsKey.FW.name, emulated)?.text)
                ?: FwVersion.parse(profile.moduleFw)
            val obs = Observations.from(parsed, isBalanceReply)
            if (obs.isNotEmpty()) {
                db.obs().insertAll(obs.map {
                    ObservationEntity(
                        profileId = profile.id, obsKey = it.key.name, text = it.text, num = it.num,
                        timestamp = receivedAt, smsId = smsId, emulated = emulated
                    )
                })
            }

            if (run != null && kind == SmsKind.REPLY) {
                var r = run.copy(state = RunState.REPLIED, replyAt = receivedAt, replySmsId = smsId, replyText = body)
                val updateRefused = run.kind == CommandKind.FIRMWARE_UPDATE.name &&
                    (parsed.result == CmdResult.FAILED || parsed.result == CmdResult.ERROR)
                if (updateRefused) r = r.copy(sessionUntil = receivedAt)
                db.runs().update(r)
                TimeoutReceiver.cancel(context, run.id, TimeoutReceiver.TYPE_REPLY)
                if (updateRefused) TimeoutReceiver.cancel(context, run.id, TimeoutReceiver.TYPE_SESSION)
                if (run.state == RunState.NO_REPLY) notifier.cancel(Notifier.ID_WARN_BASE + (run.id % 100_000).toInt())
            }

            val newFw = parsed.fw
            var newsText: String? = null
            if (newFw != null) {
                if (!emulated && FwVersion.parse(profile.moduleFw) != newFw) db.profiles().setFw(profile.id, newFw.toString())
                if (prevFw != null && newFw > prevFw) {
                    val news = FirmwareNews.newlyAvailable(commands.forProfile(profile), prevFw, newFw)
                    prefs.update { it.copy(newsCodes = news.map { d -> d.code }, newsFrom = prevFw.toString(), newsTo = newFw.toString()) }
                    newsText = "ПО модуля: $prevFw → $newFw." +
                        if (news.isEmpty()) "" else " Стали доступны: " + news.joinToString { "${it.name} (${it.code})" }
                }
            }

            // Приветствие с новой версией завершает сеанс обновления.
            val sessionRun = run?.takeIf { it.kind == CommandKind.FIRMWARE_UPDATE.name }
            if (sessionRun != null && parsed.greeting && newFw != null) {
                db.runs().get(sessionRun.id)?.let { cur ->
                    db.runs().update(cur.copy(fwAfter = newFw.toString(), sessionUntil = receivedAt))
                    TimeoutReceiver.cancel(context, cur.id, TimeoutReceiver.TYPE_SESSION)
                }
            }

            val bal = if (isBalanceReply) parsed.balanceForBalanceReply else parsed.balance
            if (bal != null) {
                db.balance().insert(
                    BalanceEventEntity(
                        profileId = profile.id, type = BalanceType.REPORTED, amount = bal,
                        timestamp = receivedAt, smsId = smsId, emulated = emulated
                    )
                )
            }

            if (!emulated) checkProfile = profile
            val quiet = source == "INBOX" && System.currentTimeMillis() - receivedAt > QUIET_INBOX_MS
            if (!quiet) {
                val prefix = if (emulated) "[эмуляция] " else ""
                val summary = Describe.short(parsed, isBalanceReply)
                val runId = run?.id ?: 0L
                val runName = run?.name.orEmpty()
                val finalKind = kind
                val finalNote = note
                val news = newsText
                val alarm = parsed.alarm
                val alarmInReply = alarm && kind != SmsKind.EVENT
                notify = {
                    when (finalKind) {
                        SmsKind.REPLY -> notifier.post(
                            Notifier.ID_REPLY_BASE + (runId % 100_000).toInt(), Notifier.CH_REPLIES,
                            "${prefix}Ответ: $runName", summary, "run:$runId"
                        )
                        SmsKind.UPDATE -> notifier.post(
                            Notifier.ID_UPDATE, Notifier.CH_REPLIES, "${prefix}Обновление ПО модуля", body, "firmware"
                        )
                        else -> notifier.post(
                            Notifier.ID_EVENT_BASE + (smsId % 100_000).toInt(), Notifier.CH_EVENTS,
                            prefix + if (alarm) "Тревога: сообщение от сигнализации" else "Сообщение от сигнализации",
                            listOf(finalNote, summary, if (alarm) body else "").filter { it.isNotBlank() }.joinToString("\n"),
                            "journal"
                        )
                    }
                    news?.let {
                        notifier.post(Notifier.ID_FW_NEWS, Notifier.CH_WARN, "${prefix}Версия ПО модуля изменилась", it, "firmware")
                    }
                    if (alarmInReply) {
                        notifier.post(
                            Notifier.ID_EVENT_BASE + (smsId % 100_000).toInt(), Notifier.CH_EVENTS,
                            "${prefix}Внимание: тревога в сообщении сигнализации", body, "journal"
                        )
                    }
                }
            }
        }
        notify?.invoke()
        widgets.requestUpdate()
        checkProfile?.let { checkBalance(it) }
    }

    // ---------- Тайм-ауты ----------

    suspend fun onTimeout(runId: Long) {
        val now = System.currentTimeMillis()
        val run = mutex.withLock {
            val r = db.runs().get(runId) ?: return
            if (r.state !in RunState.PENDING) return
            if (now < r.deadline - 5_000) {
                TimeoutReceiver.schedule(context, r.id, r.deadline, TimeoutReceiver.TYPE_REPLY)
                return
            }
            val upd = r.copy(state = RunState.NO_REPLY)
            db.runs().update(upd)
            upd
        }
        val min = prefs.value.replyTimeoutMin
        notifier.post(
            Notifier.ID_WARN_BASE + (run.id % 100_000).toInt(), Notifier.CH_WARN,
            (if (run.emulated) "[эмуляция] " else "") + "Нет ответа: ${run.name}",
            "За $min мин ответ на «${run.smsText}» не пришёл. При слабом сигнале он может прийти позже. Повторить команду можно только вручную.",
            "run:${run.id}"
        )
        widgets.requestUpdate()
    }

    suspend fun onSessionTimeout(runId: Long) {
        val r = db.runs().get(runId) ?: return
        if (r.kind != CommandKind.FIRMWARE_UPDATE.name || r.fwAfter.isNotEmpty() || r.state == RunState.CANCELLED) return
        if (r.state == RunState.FAILED) return
        if (r.sessionUntil > System.currentTimeMillis() + 5_000) return
        notifier.post(
            Notifier.ID_UPDATE, Notifier.CH_WARN,
            (if (r.emulated) "[эмуляция] " else "") + "Обновление ПО не подтверждено",
            "За 30 минут не пришло приветственное SMS с новой версией. Отправьте 09, чтобы узнать текущую версию модуля.",
            "firmware"
        )
    }

    suspend fun cancelWait(runId: Long) {
        mutex.withLock {
            val r = db.runs().get(runId) ?: return
            val now = System.currentTimeMillis()
            val isUpdate = r.kind == CommandKind.FIRMWARE_UPDATE.name
            db.runs().update(
                r.copy(
                    state = if (r.state in RunState.PENDING) RunState.CANCELLED else r.state,
                    sessionUntil = if (isUpdate && r.sessionUntil > now) now else r.sessionUntil
                )
            )
            TimeoutReceiver.cancel(context, runId, TimeoutReceiver.TYPE_REPLY)
            TimeoutReceiver.cancel(context, runId, TimeoutReceiver.TYPE_SESSION)
        }
        widgets.requestUpdate()
    }

    /** Просроченные ожидания → «нет ответа»; будильники для ещё идущих ожиданий. */
    suspend fun reconcile() {
        val now = System.currentTimeMillis()
        val overdue = mutex.withLock {
            val list = db.runs().overdue(now)
            list.forEach { db.runs().update(it.copy(state = RunState.NO_REPLY)) }
            list
        }
        overdue.forEach { r ->
            notifier.post(
                Notifier.ID_WARN_BASE + (r.id % 100_000).toInt(), Notifier.CH_WARN,
                "Нет ответа: ${r.name}", "Ответ на «${r.smsText}» не пришёл. Повторить можно вручную.", "run:${r.id}"
            )
        }
        for (p in db.profiles().allNow()) {
            db.runs().pending(p.id).forEach {
                TimeoutReceiver.schedule(context, it.id, it.deadline, TimeoutReceiver.TYPE_REPLY)
            }
            db.runs().activeUpdate(p.id, now)?.let {
                TimeoutReceiver.schedule(context, it.id, it.sessionUntil, TimeoutReceiver.TYPE_SESSION)
            }
        }
    }

    /** Досканирование входящих за последние сутки — ответы, пришедшие, пока приложение было выгружено. */
    suspend fun scanInbox() {
        if (!inbox.canRead()) return
        val now = System.currentTimeMillis()
        val rows = withContext(Dispatchers.IO) { inbox.read(now - BalanceMath.DAY_MS) }
        val profiles = db.profiles().allNow()
        for (r in rows) {
            if (profiles.none { Phone.same(it.alarmPhone, r.address) }) continue
            onIncoming(r.address, r.body, SmsGateway.partsOf(r.body), r.date, r.dateSent, "INBOX")
        }
        prefs.update { it.copy(lastInboxScan = now) }
    }

    fun onAppStart() {
        scope.launch {
            scanInbox()
            reconcile()
            widgets.updateNow()
        }
    }

    // ---------- Баланс и счётчик ----------

    suspend fun balanceView(p: ProfileEntity, emulated: Boolean = prefs.value.emulator): BalanceView {
        val last = db.balance().lastKnown(p.id, emulated) ?: return BalanceView(null, null, stale = true, low = false)
        val now = System.currentTimeMillis()
        val parts = db.sms().incomingPartsSince(p.id, last.timestamp, emulated)
        val topups = db.balance().topupsSince(p.id, last.timestamp, emulated)
        val est = BalanceMath.estimate(
            BalanceInputs(last.amount, last.timestamp, parts, p.smsPrice, p.monthlyFee, p.trafficPerDay, topups, now)
        )
        val stale = now - last.timestamp > p.balanceRemindDays.coerceAtLeast(1) * BalanceMath.DAY_MS
        return BalanceView(last, est, stale, est.value < p.balanceWarn)
    }

    suspend fun addBalanceEvent(profileId: Long, type: String, amount: Double) {
        db.balance().insert(
            BalanceEventEntity(
                profileId = profileId, type = type, amount = amount,
                timestamp = System.currentTimeMillis(), emulated = prefs.value.emulator
            )
        )
        widgets.requestUpdate()
    }

    private suspend fun checkBalance(p: ProfileEntity) {
        val v = balanceView(p, emulated = false)
        val est = v.estimate ?: return
        if (!v.low) return
        val today = todayKey()
        if (prefs.value.lastBalanceWarnDay == today) return
        prefs.update { it.copy(lastBalanceWarnDay = today) }
        notifier.post(
            Notifier.ID_BALANCE, Notifier.CH_WARN, "Низкий баланс SIM сигнализации",
            "Оценка: ${money(est.value)} ₽ (порог ${money(p.balanceWarn)} ₽). Проверьте баланс и пополните счёт.",
            "balance"
        )
    }

    /** Предупреждение о превышении лимита SMS; null — лимит не превышен. */
    private suspend fun checkLimits(): String? {
        val s = prefs.value
        val day = db.sms().sentPartsSinceNow(startOfDay())
        val month = db.sms().sentPartsSinceNow(startOfMonth())
        val over = (s.smsLimitDay > 0 && day >= s.smsLimitDay) || (s.smsLimitMonth > 0 && month >= s.smsLimitMonth)
        if (!over) return null
        val text = "Отправлено SMS: сегодня $day из ${limitText(s.smsLimitDay)}, за месяц $month из ${limitText(s.smsLimitMonth)}."
        val today = todayKey()
        if (s.lastLimitWarnDay != today) {
            prefs.update { it.copy(lastLimitWarnDay = today) }
            notifier.post(Notifier.ID_LIMIT, Notifier.CH_WARN, "Превышен лимит SMS", text, "counter")
        }
        return text
    }

    private fun limitText(v: Int) = if (v > 0) v.toString() else "∞"

    // ---------- Обслуживание ----------

    suspend fun clearEmulated() {
        mutex.withLock {
            db.sms().deleteEmulated()
            db.runs().deleteEmulated()
            db.obs().deleteEmulated()
            db.balance().deleteEmulated()
        }
        // Сообщение «что появилось» могло прийти от эмулированного обновления.
        prefs.update { it.copy(newsCodes = emptyList(), newsFrom = "", newsTo = "") }
        widgets.requestUpdate()
    }

    suspend fun deleteProfile(id: Long) {
        mutex.withLock {
            db.sms().deleteForProfile(id)
            db.runs().deleteForProfile(id)
            db.obs().deleteForProfile(id)
            db.balance().deleteForProfile(id)
            db.profiles().delete(id)
        }
        val rest = db.profiles().allNow()
        prefs.update { s -> if (s.activeProfileId == id) s.copy(activeProfileId = rest.firstOrNull()?.id ?: 0L) else s }
        widgets.requestUpdate()
    }
}

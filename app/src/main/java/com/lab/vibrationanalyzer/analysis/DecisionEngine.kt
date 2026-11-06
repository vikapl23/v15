package com.lab.vibrationanalyzer.analysis

/**
 * ЛР4. Логічний блок прийняття рішень: «IF sensor_value > threshold THEN activate_actuator».
 *
 * Чистий Kotlin без Android API, тому повністю перевіряється юніт-тестами.
 *
 * Вхід: RMS вібрації з ЛР1 + команди з трьох джерел (ручне керування, хмара, автоматика).
 * Вихід: що зараз мають робити два актуатори:
 *  - «сирена» (вібровідгук через VibratorManager);
 *  - «сигнальний прожектор» (ліхтарик через CameraManager).
 *
 * Пріоритет джерел для кожного актуатора: РУЧНЕ > ХМАРА > АВТО.
 *  - Ручне «Увімк» / «Вимк» перебиває все інше (оператор поруч з обладнанням).
 *  - Ручне «Авто» → актуатор увімкнено, якщо його ввімкнула хмара АБО автоматика.
 *
 * ЛР6: друге автоматичне правило — модель TensorFlow Lite. Якщо вона стабільно бачить
 * «Спробу злому» (input.aiAnomaly), актуатори вмикаються навіть тоді, коли RMS нижче порогу:
 * серія коротких ударів має невеликий RMS, і простий поріг її пропустив би.
 *
 * Гістерезис: тривога вмикається при RMS ≥ threshold, а вимикається лише при
 * RMS < threshold · (1 − hysteresis). Без цього на межі порогу актуатори «брязкали» б
 * (вмикались і вимикались десятки разів на секунду).
 */
class DecisionEngine(
    /** Критичне значення RMS, м/с². За замовчуванням — поріг «Критична» з ЛР1 (1.5 м/с²). */
    threshold: Float = VibrationState.CRITICAL_THRESHOLD,
    /** Частка порогу для вимкнення: 0.2 → вимкнути, коли RMS < 0.8 · threshold. */
    val hysteresis: Float = 0.2f,
    /** Поки тривога активна, вібросигнал повторюється з цим інтервалом, мс. */
    val alarmRepeatMs: Long = 5_000L
) {
    var threshold: Float = threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        set(value) {
            field = value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        }

    /** Поріг вимкнення тривоги (нижня межа гістерезису). */
    val releaseThreshold: Float get() = threshold * (1f - hysteresis)

    private var exceeded = false          // «засувка» гістерезису
    private var lastAlarmActive = false
    private var lastVibrationAtMs: Long? = null

    fun decide(input: DecisionInput): Decision {
        // 1. IF rms ≥ threshold THEN тривога (з гістерезисом)
        exceeded = when {
            input.rms >= threshold -> true
            input.rms < releaseThreshold -> false
            else -> exceeded                       // зона гістерезису: стан не змінюється
        }
        val autoOn = input.autoEnabled && exceeded
        // ЛР6: IF model == "Спроба злому" THEN activate_actuator
        val aiOn = input.autoEnabled && input.aiAnomaly

        // 2. Для кожного актуатора обираємо джерело команди за пріоритетом
        val torch = resolve(input.manualTorch, input.cloudTorch, autoOn, aiOn)
        val alarm = resolve(input.manualAlarm, input.cloudAlarm, autoOn, aiOn)

        // 3. Вібрація: одразу при вмиканні тривоги і далі раз на alarmRepeatMs
        val last = lastVibrationAtMs
        val vibrateNow = alarm.on &&
            (!lastAlarmActive || last == null || input.nowMs - last >= alarmRepeatMs)
        if (vibrateNow) lastVibrationAtMs = input.nowMs
        val stopVibration = lastAlarmActive && !alarm.on
        if (!alarm.on) lastVibrationAtMs = null
        lastAlarmActive = alarm.on

        return Decision(
            thresholdExceeded = exceeded,
            aiAnomaly = input.aiAnomaly,
            torchOn = torch.on,
            torchSource = torch.source,
            alarmActive = alarm.on,
            alarmSource = alarm.source,
            vibrateNow = vibrateNow,
            stopVibration = stopVibration,
            reason = reason(input, torch, alarm)
        )
    }

    /** Скинути внутрішній стан (наприклад, після ремонту обладнання). */
    fun reset() {
        exceeded = false
        lastAlarmActive = false
        lastVibrationAtMs = null
    }

    private data class Resolved(val on: Boolean, val source: CommandSource?)

    private fun resolve(manual: ManualMode, cloud: Boolean, auto: Boolean, ai: Boolean): Resolved = when (manual) {
        ManualMode.ON -> Resolved(true, CommandSource.MANUAL)
        ManualMode.OFF -> Resolved(false, CommandSource.MANUAL)
        ManualMode.AUTO -> when {
            cloud -> Resolved(true, CommandSource.CLOUD)
            auto -> Resolved(true, CommandSource.AUTO)
            ai -> Resolved(true, CommandSource.AI)
            else -> Resolved(false, null)
        }
    }

    private fun reason(input: DecisionInput, torch: Resolved, alarm: Resolved): String {
        val rule = "RMS %.2f %s %.2f".format(input.rms, if (exceeded) "≥" else "<", threshold) +
            if (input.aiAnomaly) ", ШІ: спроба злому" else ""
        val sources = listOfNotNull(torch.source, alarm.source).distinct()
        return when {
            !torch.on && !alarm.on && torch.source == CommandSource.MANUAL &&
                alarm.source == CommandSource.MANUAL -> "$rule → актуатори вимкнено вручну"
            !torch.on && !alarm.on -> if ((exceeded || input.aiAnomaly) && !input.autoEnabled) {
                "$rule → поріг перевищено, але автоматику вимкнено"
            } else {
                "$rule → норма, актуатори вимкнено"
            }
            else -> "$rule → активовано: " + sources.joinToString { it.title }
        }
    }

    companion object {
        const val MIN_THRESHOLD = 0.3f
        const val MAX_THRESHOLD = 5f
    }
}

/** Ручний режим одного актуатора на панелі «Manual Control». */
enum class ManualMode(val title: String) {
    AUTO("Авто"),
    ON("Увімк"),
    OFF("Вимк")
}

/** Хто ввімкнув актуатор. */
enum class CommandSource(val title: String) {
    MANUAL("ручне керування"),
    CLOUD("команда з хмари"),
    AUTO("поріг вібрації"),
    AI("ШІ: спроба злому")   // ЛР6: модель TensorFlow Lite
}

/** Усе, на основі чого DecisionEngine приймає рішення. */
data class DecisionInput(
    val rms: Float,
    val nowMs: Long,
    val autoEnabled: Boolean = true,
    val manualTorch: ManualMode = ManualMode.AUTO,
    val manualAlarm: ManualMode = ManualMode.AUTO,
    val cloudTorch: Boolean = false,   // control/led_status у Firebase
    val cloudAlarm: Boolean = false,   // control/alarm у Firebase
    val aiAnomaly: Boolean = false     // ЛР6: модель стабільно бачить «Спробу злому»
)

/** Рішення: бажаний стан актуаторів і разові дії (вібрувати зараз / зупинити вібрацію). */
data class Decision(
    val thresholdExceeded: Boolean,
    val aiAnomaly: Boolean = false,
    val torchOn: Boolean,
    val torchSource: CommandSource?,
    val alarmActive: Boolean,
    val alarmSource: CommandSource?,
    val vibrateNow: Boolean,
    val stopVibration: Boolean,
    val reason: String
)

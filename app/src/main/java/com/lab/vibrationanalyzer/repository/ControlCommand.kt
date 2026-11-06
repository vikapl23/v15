package com.lab.vibrationanalyzer.repository

/**
 * ЛР4. Команда з хмари (Cloud-to-Device). Відповідає гілці Firebase
 * devices/<deviceId>/control, яку студент змінює вручну в консолі:
 *
 *   control
 *   ├── led_status   : true / false  — увімкнути «прожектор» (ліхтарик + лампа на екрані)
 *   ├── alarm        : true / false  — увімкнути «сирену» (вібровідгук)
 *   ├── auto_enabled : true / false  — дозволити автоматичне керування за порогом
 *   └── threshold    : 1.5           — (необов'язково) новий поріг RMS, м/с²
 *
 * Відсутні ключі мають значення за замовчуванням, тому порожня гілка — це «команд немає».
 */
data class ControlCommand(
    val ledStatus: Boolean = false,
    val alarm: Boolean = false,
    val autoEnabled: Boolean = true,
    val threshold: Float? = null
)

/**
 * ЛР4. Зворотний зв'язок (Device-to-Cloud): що реально зробили актуатори.
 * Записується PUT-запитом у devices/<deviceId>/actuators, і в консолі Firebase
 * видно, що команду виконано. Gson перетворює клас на JSON, наприклад:
 *
 * { "torch": true, "torchSource": "CLOUD", "torchHardware": false,
 *   "alarm": false, "alarmSource": "NONE", "rms": 0.12, "threshold": 1.5,
 *   "timestamp": 1762020000000 }
 */
data class ActuatorReport(
    val torch: Boolean,
    val torchSource: String,
    val torchHardware: Boolean,   // true — справжній спалах, false — віртуальний (емулятор)
    val alarm: Boolean,
    val alarmSource: String,
    val rms: Float,
    val threshold: Float,
    val timestamp: Long
)

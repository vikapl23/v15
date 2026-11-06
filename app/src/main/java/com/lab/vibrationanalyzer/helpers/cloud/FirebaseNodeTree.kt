package com.lab.vibrationanalyzer.helpers.cloud

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.lab.vibrationanalyzer.repository.ControlCommand

/**
 * ЛР4. Локальна копія гілки control, яку оновлюють події потоку Firebase (Server-Sent Events).
 *
 * Firebase REST streaming надсилає події двох типів:
 *   event: put   data: {"path": "/",           "data": {"led_status": true}}  — замінити вузол
 *   event: put   data: {"path": "/led_status", "data": false}                 — замінити один ключ
 *   event: patch data: {"path": "/",           "data": {"alarm": true}}       — злити ключі
 * Перша подія після підключення — put з path "/" і ВСІМ вмістом гілки (як перший
 * виклик onDataChange у ValueEventListener Firebase SDK).
 */
class FirebaseNodeTree {

    var root: JsonElement = JsonNull.INSTANCE
        private set

    /** Застосувати подію put/patch. Повертає true, якщо дані змінились. */
    fun apply(eventType: String, eventData: String): Boolean {
        val event = JsonParser.parseString(eventData).asJsonObject
        val path = event.get("path")?.asString ?: "/"
        val data = event.get("data") ?: JsonNull.INSTANCE
        val before = root.deepCopy()
        val keys = path.split('/').filter { it.isNotEmpty() }
        when (eventType) {
            "put" -> root = put(root, keys, data)
            "patch" -> {
                val obj = data.takeIf { it.isJsonObject }?.asJsonObject ?: return false
                for ((k, v) in obj.entrySet()) root = put(root, keys + k, v)
            }
            else -> return false
        }
        return root != before
    }

    fun command(): ControlCommand = root.toControlCommand()

    private fun put(node: JsonElement, keys: List<String>, value: JsonElement): JsonElement {
        if (keys.isEmpty()) return value
        val obj = if (node.isJsonObject) node.asJsonObject.deepCopy() else JsonObject()
        val child = put(obj.get(keys.first()) ?: JsonNull.INSTANCE, keys.drop(1), value)
        if (child.isJsonNull) obj.remove(keys.first()) else obj.add(keys.first(), child)
        return obj
    }
}

/** JSON гілки control → [ControlCommand]. Значення читаємо поблажливо: true / "true" / 1 / "on". */
fun JsonElement?.toControlCommand(): ControlCommand {
    val obj = this?.takeIf { it.isJsonObject }?.asJsonObject ?: return ControlCommand()
    val defaults = ControlCommand()
    return ControlCommand(
        ledStatus = obj.get("led_status").asFlag() ?: defaults.ledStatus,
        alarm = obj.get("alarm").asFlag() ?: defaults.alarm,
        autoEnabled = obj.get("auto_enabled").asFlag() ?: defaults.autoEnabled,
        threshold = obj.get("threshold").asNumberOrNull()
    )
}

private fun JsonElement?.asFlag(): Boolean? {
    val p = this?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    return when {
        p.isBoolean -> p.asBoolean
        p.isNumber -> p.asDouble != 0.0
        else -> when (p.asString.trim().lowercase()) {
            "true", "on", "1", "yes" -> true
            "false", "off", "0", "no" -> false
            else -> null
        }
    }
}

private fun JsonElement?.asNumberOrNull(): Float? {
    val p: JsonPrimitive = this?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    return when {
        p.isNumber -> p.asFloat
        p.isString -> p.asString.replace(',', '.').toFloatOrNull()
        else -> null
    }
}

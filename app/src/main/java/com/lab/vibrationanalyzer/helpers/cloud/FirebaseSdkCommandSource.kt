package com.lab.vibrationanalyzer.helpers.cloud

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.gson.Gson
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException

/** Ключі застосунку з google-services.json (gradle переносить їх у BuildConfig). */
data class FirebaseSdkOptions(
    val applicationId: String,   // client_info.mobilesdk_app_id
    val apiKey: String,          // api_key.current_key
    val projectId: String,       // project_info.project_id
    val databaseUrl: String      // адреса Realtime Database
) {
    val isComplete: Boolean
        get() = applicationId.isNotBlank() && apiKey.isNotBlank() && databaseUrl.isNotBlank()
}

/**
 * ЛР4. Cloud-to-Device через Firebase SDK — саме те, що просить методичка:
 * ValueEventListener на гілку devices/<deviceId>/control.
 *
 * Firebase SDK тримає постійне WebSocket-з'єднання з базою. Щойно студент змінює
 * значення в консолі, сервер сам надсилає його, і Android викликає onDataChange().
 * Додаток нічого не опитує — це подієве (Event-driven) керування.
 *
 * FirebaseApp ініціалізуємо вручну з FirebaseOptions, тому плагін google-services
 * не потрібен і проєкт збирається навіть без google-services.json (тоді цей режим просто недоступний).
 */
class FirebaseSdkCommandSource(
    context: Context,
    private val options: FirebaseSdkOptions,
    private val deviceId: String
) : ICloudCommandSource {

    override val mode = ChannelMode.FIREBASE_SDK
    override val endpoint: String
        get() = "ValueEventListener " + options.databaseUrl.trimEnd('/') + "/devices/$deviceId/control"

    private val appContext = context.applicationContext
    private val gson = Gson()

    private val database: FirebaseDatabase by lazy {
        val app = FirebaseApp.getApps(appContext).firstOrNull { it.name == APP_NAME }
            ?: FirebaseApp.initializeApp(
                appContext,
                FirebaseOptions.Builder()
                    .setApplicationId(options.applicationId)
                    .setApiKey(options.apiKey)
                    .setProjectId(options.projectId)
                    .setDatabaseUrl(options.databaseUrl)
                    .build(),
                APP_NAME
            )
        FirebaseDatabase.getInstance(app, options.databaseUrl.trimEnd('/'))
    }

    override fun updates(): Flow<ControlUpdate> = callbackFlow {
        val control = database.getReference("devices/$deviceId/control")
        val connected = database.getReference(".info/connected")   // службова гілка: є зв'язок чи ні

        // Головне: ValueEventListener. onDataChange викликається одразу з поточним значенням,
        // а потім — при КОЖНІЙ зміні гілки в консолі Firebase.
        val controlListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val json = gson.toJsonTree(snapshot.value)   // Map/Boolean/Long → JSON
                Log.d(TAG, "onDataChange: $json")
                trySend(ControlUpdate.Command(json.toControlCommand(), json.toString()))
            }

            override fun onCancelled(error: DatabaseError) {
                // Наприклад, правила бази забороняють читання (Permission denied)
                Log.e(TAG, "onCancelled: ${error.message}")
                close(IOException("Firebase: ${error.message}"))
            }
        }

        var wasConnected = false
        val connectionListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.getValue(Boolean::class.java) == true) {
                    wasConnected = true
                    trySend(ControlUpdate.Connected)
                } else if (wasConnected) {
                    // SDK перепідключиться сам; лише показуємо статус
                    trySend(ControlUpdate.Disconnected("немає з'єднання з Firebase"))
                }
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }

        connected.addValueEventListener(connectionListener)
        control.addValueEventListener(controlListener)
        awaitClose {
            // Корутину скасовано (вимкнули канал або змінили режим) → знімаємо слухачів
            control.removeEventListener(controlListener)
            connected.removeEventListener(connectionListener)
            Log.d(TAG, "ValueEventListener знято")
        }
    }

    private companion object {
        const val TAG = "C2D_SDK"
        const val APP_NAME = "vibro"
    }
}

package com.childInHelp2026.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.childInHelp2026.app.firebase.AppFirebase
import java.nio.charset.StandardCharsets

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "MyFirebaseMsgService"

        private const val CHANNEL_ID_SOS = "childInHelp2026_sos_alerts"
        private const val CHANNEL_NAME_SOS = "Ειδοποιήσεις SOS"
        private const val CHANNEL_DESCRIPTION_SOS = "Ειδοποιήσεις για νέα SOS περιστατικά"

        private const val PREFS_PUSH_DEDUP = "push_dedup"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)

        Log.d(TAG, "New FCM token received.")

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Log.d(TAG, "No authenticated user during onNewToken. Token will be synced later.")
            return
        }

        saveUserToken(
            uid = currentUser.uid,
            email = currentUser.email,
            token = token
        )
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d(TAG, "Message received. Data=${remoteMessage.data}")

        val data = remoteMessage.data

        val type = data["type"] ?: ""
        val sosId = data["sosId"] ?: ""
        val latitude = data["latitude"] ?: ""
        val longitude = data["longitude"] ?: ""
        val requestId = data["requestId"] ?: ""

        if (type == MainActivity.NOTIFICATION_TYPE_SOS_ALERT) {
            if (requestId.isNotBlank() && isAlreadyHandled(requestId)) {
                Log.d(TAG, "Duplicate notification ignored: requestId=$requestId")
                return
            }

            if (requestId.isNotBlank()) {
                markHandled(requestId)
            }

            sendAck(requestId)
        }

        val title = data["title"]
            ?: when (type) {
                MainActivity.NOTIFICATION_TYPE_SOS_ALERT -> "🚨 Νέο SOS"
                else -> "childInHelp2026"
            }

        val body = data["body"] ?: "Υπάρχει νέο περιστατικό κοντά σου"

        if (type == MainActivity.NOTIFICATION_TYPE_SOS_ALERT) {
            showSosNotification(
                title = title,
                body = body,
                sosId = sosId,
                latitude = latitude,
                longitude = longitude,
                requestId = requestId
            )
        } else {
            Log.d(TAG, "Ignoring unsupported notification type=$type")
        }
    }

    private fun showSosNotification(
        title: String,
        body: String,
        sosId: String,
        latitude: String,
        longitude: String,
        requestId: String
    ) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_FROM_NOTIFICATION, true)
            putExtra(MainActivity.EXTRA_NOTIFICATION_TYPE, MainActivity.NOTIFICATION_TYPE_SOS_ALERT)
            putExtra(MainActivity.EXTRA_SOS_ID, sosId)
            putExtra(MainActivity.EXTRA_SOS_LATITUDE, latitude)
            putExtra(MainActivity.EXTRA_SOS_LONGITUDE, longitude)
            putExtra("requestId", requestId)
        }

        val requestCode = if (requestId.isNotBlank()) {
            requestId.hashCode()
        } else {
            sosId.hashCode()
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            requestCode,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutableFlag()
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_SOS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted.")
                return
            }
        }

        NotificationManagerCompat.from(this).notify(
            requestCode,
            notification
        )

        Log.d(TAG, "Local notification shown for sosId=$sosId requestId=$requestId")
    }

    private fun sendAck(requestId: String) {
        if (requestId.isBlank()) return

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        AppFirebase.database
            .getReference("notification_requests")
            .child(requestId)
            .child("ackedBy")
            .child(uid)
            .setValue(true)
            .addOnSuccessListener {
                Log.d(TAG, "ACK sent for requestId=$requestId uid=$uid")
            }
            .addOnFailureListener {
                Log.e(TAG, "ACK FAILED for requestId=$requestId", it)
            }
    }

    private fun isAlreadyHandled(requestId: String): Boolean {
        if (requestId.isBlank()) return false

        val prefs = getSharedPreferences(PREFS_PUSH_DEDUP, MODE_PRIVATE)
        return prefs.getBoolean(requestId, false)
    }

    private fun markHandled(requestId: String) {
        if (requestId.isBlank()) return

        val prefs = getSharedPreferences(PREFS_PUSH_DEDUP, MODE_PRIVATE)
        prefs.edit { putBoolean(requestId, true) }
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID_SOS)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID_SOS,
            CHANNEL_NAME_SOS,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = CHANNEL_DESCRIPTION_SOS
        }

        manager.createNotificationChannel(channel)
        Log.d(TAG, "Notification channel created: $CHANNEL_ID_SOS")
    }

    private fun saveUserToken(uid: String, email: String?, token: String) {
        val tokenId = encodeToken(token)

        val tokenRecord = hashMapOf<String, Any?>(
            "token" to token,
            "platform" to "android",
            "active" to true,
            "updatedAt" to System.currentTimeMillis(),
            "uid" to uid,
            "email" to (email ?: "")
        )

        val userTokensRef = AppFirebase.database.getReference("user_tokens")

        userTokensRef
            .get()
            .addOnSuccessListener { snapshot ->
                val updates = hashMapOf<String, Any?>()

                collectTokenCleanupUpdates(
                    snapshot = snapshot,
                    currentUid = uid,
                    currentToken = token,
                    updates = updates
                )

                updates["$uid/$tokenId"] = tokenRecord

                userTokensRef
                    .updateChildren(updates)
                    .addOnSuccessListener {
                        Log.d(
                            TAG,
                            "FCM token saved uniquely for uid=$uid. " +
                                    "Cleanup updates=${updates.size - 1}"
                        )
                    }
                    .addOnFailureListener { exception ->
                        Log.e(TAG, "Failed to save unique FCM token for uid=$uid", exception)
                    }
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to scan existing FCM tokens before save", exception)
            }
    }

    private fun collectTokenCleanupUpdates(
        snapshot: DataSnapshot,
        currentUid: String,
        currentToken: String,
        updates: HashMap<String, Any?>
    ) {
        snapshot.children.forEach { userNode ->
            val otherUid = userNode.key ?: return@forEach

            userNode.children.forEach { tokenNode ->
                val tokenNodeKey = tokenNode.key ?: return@forEach
                val existingToken = when (val value = tokenNode.value) {
                    is String -> value
                    is Map<*, *> -> value["token"] as? String
                    else -> null
                }

                if (existingToken.isNullOrBlank()) {
                    return@forEach
                }

                if (existingToken != currentToken) {
                    return@forEach
                }

                val isSameOwner = otherUid == currentUid
                val isSameTokenNode = tokenNodeKey == encodeToken(currentToken)

                if (isSameOwner && isSameTokenNode) {
                    return@forEach
                }

                updates["$otherUid/$tokenNodeKey"] = null

                Log.d(
                    TAG,
                    "Removing duplicated token from uid=$otherUid tokenNodeKey=$tokenNodeKey"
                )
            }
        }
    }

    private fun encodeToken(token: String): String {
        return Base64.encodeToString(
            token.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE
        )
    }

    private fun pendingIntentMutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }
}
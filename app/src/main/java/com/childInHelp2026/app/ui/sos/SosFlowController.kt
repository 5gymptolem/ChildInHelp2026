package com.childInHelp2026.app.ui.sos

import android.content.Context
import android.content.Intent
import android.location.Location
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.firebase.auth.FirebaseUser
import com.childInHelp2026.app.data.UserProfile
import com.childInHelp2026.app.firebase.AppFirebase
import com.childInHelp2026.app.service.SosTrackingService
import org.osmdroid.util.GeoPoint

class SosFlowController(
    private val context: Context,
    private val getCurrentUser: () -> FirebaseUser?,
    private val getCurrentUserRole: () -> String,
    private val getCurrentUserLocation: () -> Location?,
    private val onSwitchToSosTab: () -> Unit,
    private val onStatusMessage: (String) -> Unit
) {

    fun showCreateSosDialog() {
        val user = getCurrentUser()
        if (user == null) {
            Toast.makeText(context, "Πρέπει πρώτα να συνδεθείς.", Toast.LENGTH_SHORT).show()
            return
        }

        val location = getCurrentUserLocation()
        if (location == null) {
            Toast.makeText(context, "Δεν βρέθηκε τοποθεσία.", Toast.LENGTH_SHORT).show()
            return
        }

        val typeOptions = arrayOf(
            "Ιατρικό SOS",
            "Απώλεια αισθήσεων",
            "Ανακοπή / ΚΑΡΠΑ",
            "Τραυματισμός",
            "Άλλο επείγον"
        )

        var selectedType = typeOptions[0]

        val messageInput = EditText(context).apply {
            hint = "Προαιρετικό μήνυμα / πληροφορίες"
            minLines = 3
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(messageInput)
        }

        AlertDialog.Builder(context)
            .setTitle("Νέο SOS")
            .setSingleChoiceItems(typeOptions, 0) { _, which ->
                selectedType = typeOptions[which]
            }
            .setView(container)
            .setPositiveButton("Αποστολή SOS") { _, _ ->
                createSos(
                    type = selectedType,
                    message = messageInput.text?.toString()?.trim().orEmpty()
                )
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }

    private fun createSos(type: String, message: String) {
        val user = getCurrentUser()
        if (user == null) {
            Toast.makeText(context, "Δεν υπάρχει συνδεδεμένος χρήστης.", Toast.LENGTH_SHORT).show()
            return
        }

        val location = getCurrentUserLocation()
        if (location == null) {
            Toast.makeText(context, "Δεν βρέθηκε τοποθεσία.", Toast.LENGTH_SHORT).show()
            return
        }

        val sosId = AppFirebase.rootRef.child("sos").push().key
        if (sosId.isNullOrBlank()) {
            Toast.makeText(context, "Αποτυχία δημιουργίας SOS.", Toast.LENGTH_SHORT).show()
            return
        }

        AppFirebase.rootRef
            .child("users")
            .child(user.uid)
            .get()
            .addOnSuccessListener { snapshot ->
                val profile = snapshot.getValue(UserProfile::class.java)

                val createdByName = profile?.fullName?.trim().orEmpty()
                val createdByPhone = profile?.phone?.trim().orEmpty()

                writeSosRecord(
                    sosId = sosId,
                    user = user,
                    location = location,
                    type = type,
                    message = message,
                    createdByName = createdByName,
                    createdByPhone = createdByPhone
                )
            }
            .addOnFailureListener {
                writeSosRecord(
                    sosId = sosId,
                    user = user,
                    location = location,
                    type = type,
                    message = message,
                    createdByName = "",
                    createdByPhone = ""
                )
            }
    }

    private fun writeSosRecord(
        sosId: String,
        user: FirebaseUser,
        location: Location,
        type: String,
        message: String,
        createdByName: String,
        createdByPhone: String
    ) {
        val now = System.currentTimeMillis()

        val data = hashMapOf<String, Any>(
            "id" to sosId,
            "createdByUid" to user.uid,
            "createdByEmail" to (user.email ?: ""),
            "createdByName" to createdByName,
            "createdByPhone" to createdByPhone,
            "createdByRole" to getCurrentUserRole(),
            "createdAt" to now,
            "updatedAt" to now,
            "type" to type,
            "message" to message,
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "status" to "ACTIVE",
            "source" to "MOBILE_APP",
            "responderCount" to 0
        )

        AppFirebase.rootRef
            .child("sos")
            .child(sosId)
            .setValue(data)
            .addOnSuccessListener {
                startSosTrackingService(sosId)
                onSwitchToSosTab()
                onStatusMessage("Το SOS στάλθηκε επιτυχώς.")
                Toast.makeText(context, "Το SOS στάλθηκε.", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { exception ->
                Toast.makeText(
                    context,
                    "Αποτυχία αποστολής SOS: ${exception.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun startSosTrackingService(sosId: String) {
        val intent = Intent(context, SosTrackingService::class.java).apply {
            putExtra(SosTrackingService.EXTRA_SOS_ID, sosId)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun triggerPanicSos() {
        val user = getCurrentUser()
        if (user == null) return

        val location = getCurrentUserLocation()
        if (location == null) {
            Toast.makeText(context, "Αποτυχία Panic SOS: Δεν βρέθηκε τοποθεσία.", Toast.LENGTH_SHORT).show()
            return
        }

        // Trigger SOS immediately with a predefined type
        createSos(
            type = "Πανικός / Επείγον (Shake)",
            message = "Αυτόματο SOS μέσω Volume Up + Shake"
        )
    }

    fun showAdminBroadcastDialog(point: GeoPoint) {
        val input = EditText(context).apply {
            hint = "Μήνυμα"
        }

        AlertDialog.Builder(context)
            .setTitle("Αποστολή μηνύματος περιοχής")
            .setMessage("Σημείο: ${point.latitude}, ${point.longitude}")
            .setView(input)
            .setPositiveButton("Αποστολή") { _, _ ->
                val message = input.text?.toString()?.trim().orEmpty()
                if (message.isBlank()) {
                    Toast.makeText(context, "Συμπλήρωσε μήνυμα.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val data = hashMapOf<String, Any>(
                    "lat" to point.latitude,
                    "lng" to point.longitude,
                    "message" to message,
                    "radiusKm" to 2,
                    "createdByRole" to getCurrentUserRole(),
                    "timestamp" to System.currentTimeMillis()
                )

                AppFirebase.rootRef
                    .child("area_messages")
                    .push()
                    .setValue(data)
                    .addOnSuccessListener {
                        Toast.makeText(context, "Το μήνυμα περιοχής καταχωρήθηκε.", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { exception ->
                        Toast.makeText(
                            context,
                            "Αποτυχία αποστολής μηνύματος: ${exception.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }
}
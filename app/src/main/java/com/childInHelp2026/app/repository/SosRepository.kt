package com.childInHelp2026.app.repository

import android.location.Location
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import com.childInHelp2026.app.data.BiometricReading
import com.childInHelp2026.app.data.Responder
import com.childInHelp2026.app.data.SosRecord
import com.childInHelp2026.app.data.UserProfile
import com.childInHelp2026.app.firebase.AppFirebase
import java.util.Locale

class SosRepository {

    companion object {
        private const val TAG = "SosRepository"
        private const val STATUS_ACTIVE = "ACTIVE"
        private const val STATUS_INACTIVE = "INACTIVE"
        private const val RESPONDER_STATUS_RESPONDING = "RESPONDING"
    }

    private fun sosRef(): DatabaseReference {
        return AppFirebase.rootRef.child("sos")
    }

    private fun activeSosQuery(): Query {
        return sosRef()
            .orderByChild("status")
            .equalTo(STATUS_ACTIVE)
    }

    fun observeActiveSos(
        onData: (List<SosRecord>) -> Unit,
        onError: (String) -> Unit
    ): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val items = mutableListOf<SosRecord>()

                for (child in snapshot.children) {
                    val statusRaw = child.child("status").getValue(String::class.java).orEmpty()
                    val status = statusRaw.trim().uppercase(Locale.ROOT)

                    if (status != STATUS_ACTIVE) continue

                    val latitude = child.child("latitude").getValue(Double::class.java) ?: 0.0
                    val longitude = child.child("longitude").getValue(Double::class.java) ?: 0.0

                    if (latitude == 0.0 && longitude == 0.0) continue

                    val respondersMap = mutableMapOf<String, Responder>()
                    val respondersSnapshot = child.child("responders")

                    for (responderChild in respondersSnapshot.children) {
                        val responder = responderChild.getValue(Responder::class.java)
                        if (responder != null) {
                            respondersMap[responderChild.key ?: ""] = responder
                        }
                    }

                    val biometricList = mutableListOf<BiometricReading>()
                    val biometricSnapshot = child.child("biometricHistory")
                    for (bioChild in biometricSnapshot.children) {
                        val reading = bioChild.getValue(BiometricReading::class.java)
                        if (reading != null) {
                            biometricList.add(reading)
                        }
                    }

                    items.add(
                        SosRecord(
                            id = child.child("id").getValue(String::class.java)
                                ?: (child.key ?: ""),
                            createdByUid = child.child("createdByUid")
                                .getValue(String::class.java).orEmpty(),
                            createdByEmail = child.child("createdByEmail")
                                .getValue(String::class.java).orEmpty(),
                            createdByName = child.child("createdByName")
                                .getValue(String::class.java).orEmpty(),
                            createdByPhone = child.child("createdByPhone")
                                .getValue(String::class.java).orEmpty(),
                            createdByRole = child.child("createdByRole")
                                .getValue(String::class.java).orEmpty(),
                            createdAt = child.child("createdAt").getValue(Long::class.java) ?: 0L,
                            updatedAt = child.child("updatedAt").getValue(Long::class.java) ?: 0L,
                            type = child.child("type").getValue(String::class.java) ?: "SOS",
                            message = child.child("message").getValue(String::class.java).orEmpty(),
                            latitude = latitude,
                            longitude = longitude,
                            status = status,
                            source = child.child("source").getValue(String::class.java).orEmpty(),
                            responderCount = respondersSnapshot.childrenCount.toInt(),
                            responders = respondersMap,
                            biometricHistory = biometricList
                        )
                    )
                }

                val sorted = items.sortedByDescending { it.createdAt }
                Log.d(TAG, "observeActiveSos -> active items=${sorted.size}")
                onData(sorted)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observeActiveSos cancelled", error.toException())
                onError(error.message)
            }
        }

        activeSosQuery().addValueEventListener(listener)
        return listener
    }

    fun removeObserver(listener: ValueEventListener) {
        activeSosQuery().removeEventListener(listener)
    }

    fun deactivateSos(
        sosId: String,
        actorUid: String,
        actorRole: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        resolveSosNodeRef(
            sosId = sosId,
            onSuccess = { sosNodeRef ->
                val updates = hashMapOf<String, Any>(
                    "status" to STATUS_INACTIVE,
                    "updatedAt" to System.currentTimeMillis(),
                    "deactivatedByUid" to actorUid,
                    "deactivatedByRole" to actorRole
                )

                sosNodeRef
                    .updateChildren(updates)
                    .addOnSuccessListener {
                        Log.d(TAG, "deactivateSos success for id=$sosId")
                        onSuccess()
                    }
                    .addOnFailureListener { exception ->
                        Log.e(TAG, "deactivateSos failed for id=$sosId", exception)
                        onError(exception.message ?: "Άγνωστο σφάλμα")
                    }
            },
            onError = onError
        )
    }

    fun respondToSos(
        sosId: String,
        responderUid: String,
        responderEmail: String,
        responderRole: String,
        currentLocation: Location?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        resolveSosNodeRef(
            sosId = sosId,
            onSuccess = { sosNodeRef ->
                AppFirebase.rootRef
                    .child("users")
                    .child(responderUid)
                    .get()
                    .addOnSuccessListener { snapshot ->
                        val profile = snapshot.getValue(UserProfile::class.java)
                        writeResponderData(
                            sosNodeRef = sosNodeRef,
                            responderUid = responderUid,
                            responderEmail = responderEmail,
                            responderRole = responderRole,
                            profile = profile,
                            currentLocation = currentLocation,
                            onSuccess = onSuccess,
                            onError = onError
                        )
                    }
                    .addOnFailureListener { exception ->
                        Log.e(TAG, "respondToSos profile fetch failed for uid=$responderUid", exception)

                        writeResponderData(
                            sosNodeRef = sosNodeRef,
                            responderUid = responderUid,
                            responderEmail = responderEmail,
                            responderRole = responderRole,
                            profile = null,
                            currentLocation = currentLocation,
                            onSuccess = onSuccess,
                            onError = onError
                        )
                    }
            },
            onError = onError
        )
    }

    fun updateResponderStatus(
        sosId: String,
        responderUid: String,
        newStatus: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        resolveSosNodeRef(
            sosId = sosId,
            onSuccess = { sosNodeRef ->
                val now = System.currentTimeMillis()
                val updates = hashMapOf<String, Any>(
                    "status" to newStatus,
                    "lastUpdatedAt" to now
                )

                sosNodeRef
                    .child("responders")
                    .child(responderUid)
                    .updateChildren(updates)
                    .addOnSuccessListener {
                        sosNodeRef.child("updatedAt").setValue(now)
                        onSuccess()
                    }
                    .addOnFailureListener { exception ->
                        Log.e(
                            TAG,
                            "updateResponderStatus failed for sosId=$sosId responderUid=$responderUid status=$newStatus",
                            exception
                        )
                        onError(exception.message ?: "Αποτυχία ενημέρωσης κατάστασης responder")
                    }
            },
            onError = onError
        )
    }

    private fun writeResponderData(
        sosNodeRef: DatabaseReference,
        responderUid: String,
        responderEmail: String,
        responderRole: String,
        profile: UserProfile?,
        currentLocation: Location?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val now = System.currentTimeMillis()

        val responderData = hashMapOf<String, Any>(
            "uid" to responderUid,
            "email" to responderEmail,
            "fullName" to profile?.fullName?.trim().orEmpty(),
            "phone" to profile?.phone?.trim().orEmpty(),
            "role" to responderRole,
            "status" to RESPONDER_STATUS_RESPONDING,
            "startedAt" to now,
            "lastUpdatedAt" to now,
            "latitude" to (currentLocation?.latitude ?: 0.0),
            "longitude" to (currentLocation?.longitude ?: 0.0)
        )

        val updates = hashMapOf<String, Any>(
            "responders/$responderUid" to responderData,
            "updatedAt" to now
        )

        sosNodeRef
            .updateChildren(updates)
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "writeResponderData failed for responderUid=$responderUid", exception)
                onError(exception.message ?: "Αποτυχία καταχώρησης ανταπόκρισης")
            }
    }

    private fun resolveSosNodeRef(
        sosId: String,
        onSuccess: (DatabaseReference) -> Unit,
        onError: (String) -> Unit
    ) {
        if (sosId.isBlank()) {
            onError("Μη έγκυρο sosId")
            return
        }

        val directRef = sosRef().child(sosId)

        directRef.get()
            .addOnSuccessListener { directSnapshot ->
                if (directSnapshot.exists()) {
                    onSuccess(directRef)
                    return@addOnSuccessListener
                }

                sosRef()
                    .orderByChild("id")
                    .equalTo(sosId)
                    .get()
                    .addOnSuccessListener { querySnapshot ->
                        val firstMatch = querySnapshot.children.firstOrNull()

                        if (firstMatch != null) {
                            onSuccess(firstMatch.ref)
                        } else {
                            Log.e(TAG, "resolveSosNodeRef: SOS not found for sosId=$sosId")
                            onError("Το SOS δεν βρέθηκε.")
                        }
                    }
                    .addOnFailureListener { exception ->
                        Log.e(TAG, "resolveSosNodeRef query failed for sosId=$sosId", exception)
                        onError(exception.message ?: "Αποτυχία εντοπισμού SOS")
                    }
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "resolveSosNodeRef direct lookup failed for sosId=$sosId", exception)
                onError(exception.message ?: "Αποτυχία εντοπισμού SOS")
            }
    }
}
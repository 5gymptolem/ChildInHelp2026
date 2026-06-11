package com.childInHelp2026.app.repository

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.childInHelp2026.app.data.Aed
import com.childInHelp2026.app.firebase.AppFirebase

class AedRepository {

    companion object {
        private const val TAG = "AedRepository"
    }

    fun observeActiveAeds(
        onData: (List<Aed>) -> Unit,
        onError: (String) -> Unit,
    ): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val items = mutableListOf<Aed>()

                for (child in snapshot.children) {
                    val aed = child.toAedOrNull() ?: continue
                    if (!aed.active) continue
                    items.add(aed)
                }

                onData(items.sortedBy { it.displayName().lowercase() })
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observeActiveAeds cancelled", error.toException())
                onError(error.message)
            }
        }

        AppFirebase.rootRef.child("aeds").addValueEventListener(listener)
        return listener
    }

    fun removeObserver(listener: ValueEventListener) {
        AppFirebase.rootRef.child("aeds").removeEventListener(listener)
    }

    private fun DataSnapshot.toAedOrNull(): Aed? {
        val id = key ?: return null

        val latitude = readDouble("latitude") ?: return null
        val longitude = readDouble("longitude") ?: return null

        if ((latitude == 0.0) && (longitude == 0.0)) return null

        val locationName = readString("locationName")
        val legacyName = readString("name")
        val resolvedName = when {
            locationName.isNotBlank() -> locationName
            legacyName.isNotBlank() -> legacyName
            else -> "AED"
        }

        val address = readString("address").ifBlank { resolvedName }

        val notes = readString("notes")
        val legacyDescription = readString("description")
        val resolvedDescription = when {
            notes.isNotBlank() -> notes
            legacyDescription.isNotBlank() -> legacyDescription
            else -> ""
        }

        val contactPhone = readString("contactPhone")
            .ifBlank { readString("phone") }

        val contactEmail = readString("contactEmail")
            .ifBlank { readString("email") }

        val contactName = readString("contactName")
            .ifBlank { readString("responsiblePerson") }

        val active = child("active").getValue(Boolean::class.java) ?: true

        return Aed(
            id = id,

            // νέο schema
            locationName = locationName.ifBlank { resolvedName },
            latitude = latitude,
            longitude = longitude,
            address = address,
            notes = notes.ifBlank { resolvedDescription },
            contactPhone = contactPhone,
            contactEmail = contactEmail,
            active = active,

            // backward-friendly aliases
            name = resolvedName,
            description = resolvedDescription,
            contactName = contactName
        )
    }

    private fun DataSnapshot.readString(childKey: String): String {
        return child(childKey).getValue(String::class.java)?.trim().orEmpty()
    }

    private fun DataSnapshot.readDouble(childKey: String): Double? {
        val node = child(childKey)

        node.getValue(Double::class.java)?.let { return it }
        node.getValue(Long::class.java)?.let { return it.toDouble() }
        node.getValue(Int::class.java)?.let { return it.toDouble() }

        val asString = node.getValue(String::class.java)?.trim().orEmpty()
        if (asString.isBlank()) return null

        return asString.replace(",", ".").toDoubleOrNull()
    }
}
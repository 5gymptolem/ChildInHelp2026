package com.childInHelp2026.app.repository

import android.util.Log
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.childInHelp2026.app.data.AppSettings
import com.childInHelp2026.app.firebase.AppFirebase

class SettingsRepository {

    companion object {
        private const val TAG = "SettingsRepository"
    }

    fun observeAppSettings(
        onChange: (AppSettings) -> Unit,
        onError: (String) -> Unit
    ): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val settings = snapshot.getValue(AppSettings::class.java) ?: AppSettings()
                onChange(settings)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "App settings listener cancelled", error.toException())
                onError(error.message)
            }
        }

        AppFirebase.rootRef
            .child("app_settings")
            .addValueEventListener(listener)

        return listener
    }

    fun removeListener(listener: ValueEventListener) {
        AppFirebase.rootRef
            .child("app_settings")
            .removeEventListener(listener)
    }
}
package com.childInHelp2026.app.firebase

import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

object AppFirebase {
    const val DATABASE_URL: String =
        "https://childInHelp2026-default-rtdb.europe-west1.firebasedatabase.app/"

    val database: FirebaseDatabase by lazy {
        FirebaseDatabase.getInstance(DATABASE_URL)
    }

    val rootRef: DatabaseReference by lazy {
        database.reference
    }
}
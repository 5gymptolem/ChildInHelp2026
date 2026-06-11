package com.childInHelp2026.app.repository

import android.util.Log
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.childInHelp2026.app.data.AppSettings
import com.childInHelp2026.app.data.UserProfile
import com.childInHelp2026.app.firebase.AppFirebase

class AuthRepository {

    companion object {
        private const val TAG = "childInHelp2026Auth"
    }

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db = AppFirebase.rootRef

    fun getCurrentUser(): FirebaseUser? = auth.currentUser

    fun logout() {
        auth.signOut()
    }

    fun fetchGeneralSettings(
        onSuccess: (AppSettings) -> Unit,
        onError: (String) -> Unit
    ) {
        db.child("settings").child("general").get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) {
                    onSuccess(AppSettings())
                    return@addOnSuccessListener
                }

                val settings = AppSettings(
                    defaultRadiusKm = snapshot.child("defaultRadiusKm").getValue(Int::class.java) ?: 10,
                    maxRadiusKm = snapshot.child("maxRadiusKm").getValue(Int::class.java) ?: 50,
                    allowCustomRadius = snapshot.child("allowCustomRadius").getValue(Boolean::class.java) ?: true,
                    notifyCertifiedOnly = snapshot.child("notifyCertifiedOnly").getValue(Boolean::class.java) ?: false,
                    notifyNonCertifiedAlso = snapshot.child("notifyNonCertifiedAlso").getValue(Boolean::class.java) ?: true,
                    maxLocationAgeMinutes = snapshot.child("maxLocationAgeMinutes").getValue(Int::class.java) ?: 30,
                    responderTrackingEnabled = snapshot.child("responderTrackingEnabled").getValue(Boolean::class.java) ?: true,
                    responderTrackingIntervalSeconds = snapshot.child("responderTrackingIntervalSeconds").getValue(Int::class.java) ?: 15,
                    appName = snapshot.child("appName").getValue(String::class.java) ?: "@String/app_name",
                    footerText = snapshot.child("footerText").getValue(String::class.java)
                        ?: "@String/Dev_by"
                )

                onSuccess(settings)
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "fetchGeneralSettings failed", exception)
                onError("@String/apotyxia_fortosis: ${exception.message}")
            }
    }

    fun registerUser(
        fullName: String,
        email: String,
        password: String,
        phone: String,
        area: String,
        certifiedFirstAid: Boolean,
        preferredRadiusKm: Int,
        onProgress: (String) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        onProgress("Βήμα 1/4: Δημιουργία λογαριασμού Authentication...")

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    val ex = task.exception
                    Log.e(TAG, "createUserWithEmailAndPassword failed", ex)
                    onError(buildReadableError(ex ?: Exception("Άγνωστο σφάλμα Authentication")))
                    return@addOnCompleteListener
                }

                val firebaseUser = task.result?.user ?: auth.currentUser
                if (firebaseUser == null) {
                    onError("Η εγγραφή ολοκληρώθηκε αλλά δεν βρέθηκε χρήστης.")
                    return@addOnCompleteListener
                }

                onProgress("Βήμα 2/4: Έγινε Auth δημιουργία. Ανανέωση token...")

                firebaseUser.getIdToken(true)
                    .addOnCompleteListener { tokenTask ->
                        if (!tokenTask.isSuccessful) {
                            val ex = tokenTask.exception
                            Log.e(TAG, "Token refresh failed", ex)
                            onError(
                                "Απέτυχε η ανανέωση token μετά την εγγραφή: ${buildReadableError(ex ?: Exception("Άγνωστο σφάλμα token"))}"
                            )
                            return@addOnCompleteListener
                        }

                        onProgress("Βήμα 3/4: Token ΟΚ. Αποθήκευση προφίλ στο RTDB...")

                        val profile = buildRegisteredProfile(
                            firebaseUser = firebaseUser,
                            fullName = fullName,
                            phone = phone,
                            area = area,
                            certifiedFirstAid = certifiedFirstAid,
                            preferredRadiusKm = preferredRadiusKm
                        )

                        saveProfile(
                            uid = firebaseUser.uid,
                            profile = profile,
                            onSuccess = {
                                onProgress("Βήμα 4/4: Το προφίλ αποθηκεύτηκε στο RTDB.")
                                onSuccess(firebaseUser.uid)
                            },
                            onError = { exception ->
                                Log.e(TAG, "RTDB profile save failed for uid=${firebaseUser.uid}", exception)
                                onError(
                                    "Η εγγραφή έγινε, αλλά απέτυχε η αποθήκευση προφίλ: ${buildReadableError(exception)}"
                                )
                            }
                        )
                    }
            }
    }

    fun loginUser(
        email: String,
        password: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    val ex = task.exception
                    Log.e(TAG, "loginUser failed", ex)
                    onError(buildReadableError(ex ?: Exception("Άγνωστο σφάλμα σύνδεσης")))
                    return@addOnCompleteListener
                }

                val firebaseUser = task.result?.user ?: auth.currentUser
                if (firebaseUser == null) {
                    onError("Επιτυχής σύνδεση αλλά λείπει το uid.")
                    return@addOnCompleteListener
                }

                ensureCurrentUserProfileExists(
                    onSuccess = { onSuccess(firebaseUser.uid) },
                    onError = { errorMessage ->
                        Log.e(TAG, "ensureCurrentUserProfileExists after login failed: $errorMessage")
                        onError(errorMessage)
                    }
                )
            }
    }

    fun ensureCurrentUserProfileExists(
        onSuccess: (UserProfile) -> Unit,
        onError: (String) -> Unit
    ) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            onError("Δεν υπάρχει συνδεδεμένος χρήστης.")
            return
        }

        val userRef = db.child("users").child(currentUser.uid)
        userRef.get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    val profile = snapshot.getValue(UserProfile::class.java)
                    if (profile != null) {
                        onSuccess(profile)
                    } else {
                        val repairedProfile = buildBootstrapProfile(currentUser)
                        saveProfile(
                            uid = currentUser.uid,
                            profile = repairedProfile,
                            onSuccess = { onSuccess(repairedProfile) },
                            onError = { exception ->
                                Log.e(TAG, "Failed to repair unreadable profile for uid=${currentUser.uid}", exception)
                                onError("Αποτυχία επιδιόρθωσης προφίλ: ${buildReadableError(exception)}")
                            }
                        )
                    }
                    return@addOnSuccessListener
                }

                val bootstrapProfile = buildBootstrapProfile(currentUser)
                saveProfile(
                    uid = currentUser.uid,
                    profile = bootstrapProfile,
                    onSuccess = { onSuccess(bootstrapProfile) },
                    onError = { exception ->
                        Log.e(TAG, "Failed to bootstrap missing profile for uid=${currentUser.uid}", exception)
                        onError("Αποτυχία δημιουργίας βασικού προφίλ: ${buildReadableError(exception)}")
                    }
                )
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "ensureCurrentUserProfileExists failed", exception)
                onError("Αποτυχία ελέγχου προφίλ: ${buildReadableError(exception)}")
            }
    }

    fun fetchCurrentUserProfile(
        onSuccess: (UserProfile) -> Unit,
        onError: (String) -> Unit
    ) {
        ensureCurrentUserProfileExists(
            onSuccess = onSuccess,
            onError = onError
        )
    }

    fun updateCurrentUserProfile(
        updatedProfile: UserProfile,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            onError("Δεν υπάρχει συνδεδεμένος χρήστης.")
            return
        }

        currentUser.getIdToken(true)
            .addOnCompleteListener { tokenTask ->
                if (!tokenTask.isSuccessful) {
                    val ex = tokenTask.exception
                    onError(
                        "Απέτυχε η ανανέωση token πριν την ενημέρωση: ${buildReadableError(ex ?: Exception("Άγνωστο σφάλμα token"))}"
                    )
                    return@addOnCompleteListener
                }

                saveProfile(
                    uid = currentUser.uid,
                    profile = updatedProfile,
                    onSuccess = { onSuccess() },
                    onError = { exception ->
                        Log.e(TAG, "updateCurrentUserProfile failed", exception)
                        onError("Αποτυχία ενημέρωσης προφίλ: ${exception.message}")
                    }
                )
            }
    }

    private fun saveProfile(
        uid: String,
        profile: UserProfile,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        db.child("users").child(uid).setValue(profile)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { exception -> onError(exception) }
    }

    private fun buildRegisteredProfile(
        firebaseUser: FirebaseUser,
        fullName: String,
        phone: String,
        area: String,
        certifiedFirstAid: Boolean,
        preferredRadiusKm: Int
    ): UserProfile {
        val now = System.currentTimeMillis()
        return UserProfile(
            fullName = fullName,
            email = firebaseUser.email ?: "",
            phone = phone,
            area = area,
            certifiedFirstAid = certifiedFirstAid,
            preferredRadiusKm = preferredRadiusKm,
            notificationsEnabled = true,
            active = true,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun buildBootstrapProfile(firebaseUser: FirebaseUser): UserProfile {
        val now = System.currentTimeMillis()
        val fallbackName = firebaseUser.displayName?.takeIf { it.isNotBlank() }
            ?: firebaseUser.email?.substringBefore("@")
            ?: "Χρήστης childInHelp2026"

        return UserProfile(
            fullName = fallbackName,
            email = firebaseUser.email ?: "",
            phone = "",
            area = "",
            certifiedFirstAid = false,
            preferredRadiusKm = 10,
            notificationsEnabled = true,
            active = true,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun buildReadableError(exception: Exception): String {
        return when (exception) {
            is FirebaseAuthUserCollisionException -> {
                "Υπάρχει ήδη λογαριασμός με αυτό το email."
            }

            is FirebaseAuthInvalidCredentialsException -> {
                "Μη έγκυρα στοιχεία. Έλεγξε email ή κωδικό."
            }

            is FirebaseAuthInvalidUserException -> {
                "Ο χρήστης δεν βρέθηκε ή είναι απενεργοποιημένος."
            }

            is FirebaseNetworkException -> {
                "Πρόβλημα δικτύου. Έλεγξε τη σύνδεσή σου στο internet."
            }

            is FirebaseAuthException -> {
                val code = exception.errorCode
                val message = exception.localizedMessage ?: exception.message ?: "Άγνωστο σφάλμα Authentication"

                when (code) {
                    "ERROR_OPERATION_NOT_ALLOWED" ->
                        "Το Email/Password sign-in δεν είναι ενεργοποιημένο στο Firebase Console."
                    "ERROR_TOO_MANY_REQUESTS" ->
                        "Πάρα πολλές προσπάθειες. Δοκίμασε ξανά αργότερα."
                    "ERROR_INVALID_EMAIL" ->
                        "Το email δεν είναι έγκυρο."
                    else ->
                        "Firebase Auth error [$code]: $message"
                }
            }

            else -> {
                val className = exception::class.java.simpleName
                val message = exception.localizedMessage ?: exception.message ?: "Άγνωστο σφάλμα"
                "Σφάλμα [$className]: $message"
            }
        }
    }
}
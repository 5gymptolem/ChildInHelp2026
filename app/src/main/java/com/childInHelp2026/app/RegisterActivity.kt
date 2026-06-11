package com.childInHelp2026.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.childInHelp2026.app.data.AppSettings
import com.childInHelp2026.app.databinding.ActivityRegisterBinding
import com.childInHelp2026.app.repository.AuthRepository

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var authRepository: AuthRepository

    private var currentSettings: AppSettings = AppSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authRepository = AuthRepository()

        binding.btnRegister.setOnClickListener {
            attemptRegister()
        }

        binding.btnGoLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        loadSettings()
    }

    @SuppressLint("SetTextI18n")
    private fun loadSettings() {
        binding.textSettingsInfo.text = "Φόρτωση ρυθμίσεων..."
        authRepository.fetchGeneralSettings(
            onSuccess = { settings ->
                currentSettings = settings
                binding.textSettingsInfo.text =
                    "Default ακτίνα: ${settings.defaultRadiusKm} km | Μέγιστη: ${settings.maxRadiusKm} km"
            },
            onError = { errorMessage ->
                binding.textSettingsInfo.text =
                    "Δεν φορτώθηκαν οι ρυθμίσεις. Θα χρησιμοποιηθεί default ακτίνα ${currentSettings.defaultRadiusKm} km."
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
            }
        )
    }

    @SuppressLint("SetTextI18n")
    private fun attemptRegister() {
        clearErrors()

        val fullName = binding.editFullName.text?.toString()?.trim().orEmpty()
        val email = binding.editEmail.text?.toString()?.trim().orEmpty()
        val password = binding.editPassword.text?.toString().orEmpty()
        val confirmPassword = binding.editConfirmPassword.text?.toString().orEmpty()
        val phone = binding.editPhone.text?.toString()?.trim().orEmpty()
        val area = binding.editArea.text?.toString()?.trim().orEmpty()
        val radiusText = binding.editRadius.text?.toString()?.trim().orEmpty()
        val certified = binding.checkboxCertified.isChecked

        var hasError = false

        if (fullName.isBlank()) {
            binding.layoutFullName.error = "Συμπλήρωσε ονοματεπώνυμο"
            hasError = true
        }

        if (email.isBlank()) {
            binding.layoutEmail.error = "Συμπλήρωσε email"
            hasError = true
        }

        if (password.length < 6) {
            binding.layoutPassword.error = "Ο κωδικός πρέπει να έχει τουλάχιστον 6 χαρακτήρες"
            hasError = true
        }

        if (confirmPassword != password) {
            binding.layoutConfirmPassword.error = "Οι κωδικοί δεν ταιριάζουν"
            hasError = true
        }

        if (phone.isBlank()) {
            binding.layoutPhone.error = "Συμπλήρωσε τηλέφωνο"
            hasError = true
        }

        if (area.isBlank()) {
            binding.layoutArea.error = "Συμπλήρωσε περιοχή"
            hasError = true
        }

        val resolvedRadius = resolveRadius(radiusText)
        if (resolvedRadius == null) {
            binding.layoutRadius.error =
                "Η ακτίνα πρέπει να είναι αριθμός από 1 έως ${currentSettings.maxRadiusKm}"
            hasError = true
        }

        if (hasError || resolvedRadius == null) return

        setLoading(true)
        binding.textStatus.text = "Εκκίνηση εγγραφής..."

        authRepository.registerUser(
            fullName = fullName,
            email = email,
            password = password,
            phone = phone,
            area = area,
            certifiedFirstAid = certified,
            preferredRadiusKm = resolvedRadius,
            onProgress = { progressMessage ->
                binding.textStatus.text = progressMessage
            },
            onSuccess = {
                setLoading(false)
                Toast.makeText(this, "Η εγγραφή ολοκληρώθηκε επιτυχώς.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            },
            onError = { errorMessage ->
                setLoading(false)
                binding.textStatus.text = "Σφάλμα: $errorMessage"
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun resolveRadius(radiusText: String): Int? {
        if (!currentSettings.allowCustomRadius) {
            return currentSettings.defaultRadiusKm
        }

        if (radiusText.isBlank()) {
            return currentSettings.defaultRadiusKm
        }

        val parsed = radiusText.toIntOrNull() ?: return null
        if (parsed < 1 || parsed > currentSettings.maxRadiusKm) return null

        return parsed
    }

    private fun clearErrors() {
        binding.layoutFullName.error = null
        binding.layoutEmail.error = null
        binding.layoutPassword.error = null
        binding.layoutConfirmPassword.error = null
        binding.layoutPhone.error = null
        binding.layoutArea.error = null
        binding.layoutRadius.error = null
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnRegister.isEnabled = !loading
        binding.btnGoLogin.isEnabled = !loading
        binding.btnBack.isEnabled = !loading
    }
}
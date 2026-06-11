package com.childInHelp2026.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.childInHelp2026.app.databinding.ActivityLoginBinding
import com.childInHelp2026.app.repository.AuthRepository

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authRepository = AuthRepository()

        binding.btnLogin.setOnClickListener {
            attemptLogin()
        }

        binding.btnGoRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
        }

        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun attemptLogin() {
        clearErrors()

        val email = binding.editEmail.text?.toString()?.trim().orEmpty()
        val password = binding.editPassword.text?.toString().orEmpty()

        var hasError = false

        if (email.isBlank()) {
            binding.layoutEmail.error = "Συμπλήρωσε email"
            hasError = true
        }

        if (password.isBlank()) {
            binding.layoutPassword.error = "Συμπλήρωσε κωδικό"
            hasError = true
        }

        if (hasError) return

        setLoading(loading = true)
        binding.textStatus.text = "Γίνεται σύνδεση..."

        authRepository.loginUser(
            email = email,
            password = password,
            onSuccess = {
                setLoading(loading = false)
                binding.textStatus.text = "Η σύνδεση ολοκληρώθηκε."
                Toast.makeText(this, "Επιτυχής σύνδεση", Toast.LENGTH_LONG).show()

                // 🔥 ΠΑΜΕ ΣΤΟ MAIN (ΟΧΙ PROFILE)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            },
            onError = { errorMessage ->
                setLoading(loading = false)
                binding.textStatus.text = "Σφάλμα: $errorMessage"
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun clearErrors() {
        binding.layoutEmail.error = null
        binding.layoutPassword.error = null
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.btnGoRegister.isEnabled = !loading
        binding.btnBack.isEnabled = !loading
    }
}
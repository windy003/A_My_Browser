package com.swiftbrowser.ui.auth

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.swiftbrowser.SwiftBrowserApp
import com.swiftbrowser.databinding.ActivityLoginBinding
import com.swiftbrowser.sync.AuthResult
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val syncManager get() = (application as SwiftBrowserApp).cloudSyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogin.setOnClickListener { performLogin() }
        binding.btnRegister.setOnClickListener { performRegister() }
    }

    private fun performLogin() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "请输入用户名和密码", Toast.LENGTH_SHORT).show()
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            val result = syncManager.login(username, password)
            setLoading(false)
            when (result) {
                is AuthResult.Success -> {
                    Toast.makeText(this@LoginActivity, "欢迎，${result.username}", Toast.LENGTH_SHORT).show()
                    finish()
                }
                is AuthResult.Error -> {
                    Toast.makeText(this@LoginActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun performRegister() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "请输入用户名和密码", Toast.LENGTH_SHORT).show()
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            val result = syncManager.register(username, password)
            setLoading(false)
            when (result) {
                is AuthResult.Success -> {
                    Toast.makeText(this@LoginActivity, "注册成功，欢迎 ${result.username}", Toast.LENGTH_SHORT).show()
                    finish()
                }
                is AuthResult.Error -> {
                    Toast.makeText(this@LoginActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.btnRegister.isEnabled = !loading
    }
}

package com.fasterdownloads

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fasterdownloads.core.DownloadController
import com.fasterdownloads.core.TorrentEngine
import com.fasterdownloads.core.ThemeMode
import com.fasterdownloads.core.ThemePreference
import com.fasterdownloads.ui.FasterDownloadsApp
import com.fasterdownloads.ui.theme.FasterDownloadsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemePreference.load(this)
        enableEdgeToEdge(
            statusBarStyle = systemBarStyle(ThemePreference.isNight(this)),
            navigationBarStyle = systemBarStyle(ThemePreference.isNight(this)),
        )
        handleIntent(intent)
        // Warm the BitTorrent engine up while the user is still typing: DHT bootstrap takes
        // tens of seconds, and doing it in advance is what makes the first magnet quick.
        lifecycleScope.launch(Dispatchers.IO) { TorrentEngine.warmUp(this@MainActivity) }
        setContent {
            val mode by ThemePreference.mode.collectAsState()
            val systemNight = isSystemInDarkTheme()
            val night = when (mode) {
                ThemeMode.DAY -> false
                ThemeMode.NIGHT -> true
                ThemeMode.SYSTEM -> systemNight
            }

            // The bars follow the theme, not the system, once the user has picked one.
            LaunchedEffect(night) {
                enableEdgeToEdge(
                    statusBarStyle = systemBarStyle(night),
                    navigationBarStyle = systemBarStyle(night),
                )
            }

            FasterDownloadsTheme(darkTheme = night) {
                FasterDownloadsApp(
                    night = night,
                    onToggleTheme = {
                        ThemePreference.set(
                            this@MainActivity,
                            if (night) ThemeMode.DAY else ThemeMode.NIGHT,
                        )
                    },
                    onOpenFile = { uri -> openFile(uri) },
                    onEnsurePermissions = { ensurePermissions() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Dark icons on the neon canvas, light icons on the black one. */
    private fun systemBarStyle(night: Boolean): SystemBarStyle {
        val transparent = android.graphics.Color.TRANSPARENT
        return if (night) {
            SystemBarStyle.dark(transparent)
        } else {
            SystemBarStyle.light(transparent, transparent)
        }
    }

    /** Magnet links arrive through ACTION_VIEW or a text share. */
    private fun handleIntent(intent: Intent?) {
        val url = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.trim()

        if (!url.isNullOrBlank() && url.startsWith("magnet:", ignoreCase = true)) {
            DownloadController.pushIncoming(url)
        }
    }

    private fun requiredPermissions(): List<String> {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return needed
    }

    private fun ensurePermissions() {
        val needed = requiredPermissions()
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun openFile(uriString: String) {
        val uri = Uri.parse(uriString)
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
    }
}

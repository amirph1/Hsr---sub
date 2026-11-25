package com.amir.hsr_translator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "hsr_translator"
    private val REQUEST_MEDIA_PROJECTION = 1001
    private var pendingArgs: Map<String, Any>? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startAutoTranslator" -> {
                    @Suppress("UNCHECKED_CAST")
                    pendingArgs = call.arguments as? Map<String, Any>

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
                    }

                    if (!Settings.canDrawOverlays(this)) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                        result.success("PERMISSION_NEEDED")
                    } else {
                        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
                        result.success("STARTED")
                    }
                }
                "stopAutoTranslator" -> {
                    stopService(Intent(this, OverlayService::class.java))
                    result.success("STOPPED")
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
                putExtra("topPercent", (pendingArgs?.get("topPercent") as? Double) ?: 0.72)
                putExtra("heightPercent", (pendingArgs?.get("heightPercent") as? Double) ?: 0.20)
                putExtra("widthPercent", (pendingArgs?.get("widthPercent") as? Double) ?: 0.76)
                putExtra("fontSize", (pendingArgs?.get("fontSize") as? Double) ?: 16.0)
                putExtra("geminiApiKey", (pendingArgs?.get("geminiApiKey") as? String) ?: "")
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        }
    }
}

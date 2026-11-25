package com.amir.hsr_translator

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.TypedValue
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var overlayContainer: FrameLayout
    private lateinit var subtitleTextView: TextView
    private lateinit var overlayParams: WindowManager.LayoutParams

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var currentWidth = 0
    private var currentHeight = 0

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var topPercent = 0.72
    private var heightPercent = 0.20
    private var widthPercent = 0.76
    private var fontSize = 16.0f
    private var geminiApiKey = ""

    private var lastRecognizedText = ""
    private var isTranslating = false
    private var hasVisibleSubtitle = false
    private var isRunning = false

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            captureAndProcessFrame()
            mainHandler.postDelayed(this, 1050)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        setupSubtitleOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        topPercent = intent?.getDoubleExtra("topPercent", 0.72) ?: 0.72
        heightPercent = intent?.getDoubleExtra("heightPercent", 0.20) ?: 0.20
        widthPercent = intent?.getDoubleExtra("widthPercent", 0.76) ?: 0.76
        fontSize = (intent?.getDoubleExtra("fontSize", 16.0) ?: 16.0).toFloat()
        geminiApiKey = intent?.getStringExtra("geminiApiKey") ?: ""

        updateOverlayLayout()

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection?.stop()
            mediaProjection = manager.getMediaProjection(resultCode, data)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    isRunning = false
                    stopSelf()
                }
            }, mainHandler)

            initVirtualDisplay()
            isRunning = true
            mainHandler.removeCallbacks(scanRunnable)
            mainHandler.postDelayed(scanRunnable, 800)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "hsr_auto_translator"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "HSR Auto Subtitle", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("مترجم خودکار زیرنویس فعال است")
            .setContentText("در حال ترجمه خودکار دیالوگ‌های بازی")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(202, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(202, notification)
        }
    }

    private fun setupSubtitleOverlay() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        overlayContainer = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DC0B0E17"))
                cornerRadius = 24f
            }
            setPadding(32, 18, 32, 18)
            alpha = 0f
        }

        subtitleTextView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_RTL
            setLineSpacing(6f, 1.15f)
            setShadowLayer(4f, 0f, 2f, Color.BLACK)
            typeface = try {
                Typeface.createFromAsset(assets, "fonts/Vazirmatn.ttf")
            } catch (_: Exception) {
                Typeface.DEFAULT
            }
        }

        overlayContainer.addView(
            subtitleTextView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }

        windowManager.addView(overlayContainer, overlayParams)
        updateOverlayLayout()
    }

    private fun updateOverlayLayout() {
        if (!::overlayContainer.isInitialized) return
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels

        overlayParams.width = (w * widthPercent).toInt()
        overlayParams.y = (h * topPercent).toInt()
        subtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
        windowManager.updateViewLayout(overlayContainer, overlayParams)
    }

    private fun initVirtualDisplay() {
        val metrics = resources.displayMetrics
        currentWidth = metrics.widthPixels
        currentHeight = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader?.close()
        imageReader = ImageReader.newInstance(currentWidth, currentHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay?.release()
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "HSRAutoSub", currentWidth, currentHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun captureAndProcessFrame() {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels

        if (w != currentWidth || h != currentHeight) {
            currentWidth = w
            currentHeight = h
            imageReader?.close()
            imageReader = ImageReader.newInstance(currentWidth, currentHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay?.resize(currentWidth, currentHeight, metrics.densityDpi)
            virtualDisplay?.setSurface(imageReader?.surface)
            updateOverlayLayout()
            return
        }

        // مخفی کردن لحظه‌ای کادر ترجمه برای خواندن دقیق متن زیرنویس زیرین
        overlayContainer.alpha = 0f

        mainHandler.postDelayed({
            val image = try {
                imageReader?.acquireLatestImage()
            } catch (_: Exception) {
                null
            }

            if (hasVisibleSubtitle) {
                overlayContainer.alpha = 1f
            }

            if (image == null) return@postDelayed

            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * currentWidth

                val fullBitmap = Bitmap.createBitmap(
                    currentWidth + rowPadding / pixelStride, currentHeight, Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)
                image.close()

                val cropW = (currentWidth * widthPercent).toInt().coerceIn(50, currentWidth)
                val cropH = (currentHeight * heightPercent).toInt().coerceIn(30, currentHeight)
                val cropX = ((currentWidth - cropW) / 2).coerceIn(0, currentWidth - cropW)
                val cropY = (currentHeight * topPercent).toInt().coerceIn(0, currentHeight - cropH)

                val cropped = Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH)

                recognizer.process(InputImage.fromBitmap(cropped, 0))
                    .addOnSuccessListener { visionText ->
                        val merged = visionText.text
                            .replace(Regex("\\s*\\n\\s*"), " ")
                            .replace(Regex("\\s+"), " ")
                            .trim()

                        if (merged.length < 3) {
                            lastRecognizedText = ""
                            hasVisibleSubtitle = false
                            overlayContainer.alpha = 0f
                        } else if (merged != lastRecognizedText && !isTranslating) {
                            lastRecognizedText = merged
                            translateFluentPersian(merged)
                        }
                    }
            } catch (_: Exception) {
                try { image.close() } catch (_: Exception) {}
            }
        }, 85)
    }

    private fun normalizeGameTerms(text: String): String {
        val terms = mapOf(
            "Trailblazer" to "تریل‌بلیزر",
            "Astral Express" to "قطار آسترال",
            "Stellaron" to "استلارون",
            "Herta Space Station" to "ایستگاه فضایی هرتا",
            "March 7th" to "مارچ سونث",
            "Dan Heng" to "دن هنگ",
            "Silver Wolf" to "سیلور ولف",
            "Himeko" to "هیمکو",
            "Welt" to "ولت",
            "Kafka" to "کافکا",
            "Pom-Pom" to "پام‌پام",
            "Aeon" to "آئون"
        )
        var result = text
        for ((en, fa) in terms) {
            result = result.replace(Regex("\\b${Regex.escape(en)}\\b", RegexOption.IGNORE_CASE), fa)
        }
        return result
    }

    private fun translateFluentPersian(englishText: String) {
        isTranslating = true
        executor.execute {
            val translated = if (geminiApiKey.isNotEmpty()) {
                translateWithGemini(englishText) ?: translateWithWebFallback(englishText)
            } else {
                translateWithWebFallback(englishText)
            }

            mainHandler.post {
                isTranslating = false
                if (!translated.isNullOrBlank()) {
                    subtitleTextView.text = translated
                    hasVisibleSubtitle = true
                    overlayContainer.alpha = 1f
                }
            }
        }
    }

    private fun translateWithGemini(text: String): String? {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$geminiApiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 4500
            conn.readTimeout = 4500

            val prompt = "Translate this Honkai: Star Rail game dialogue into natural, fluent, conversational Persian (فارسی روان، طبیعی و داستانی مناسب زیرنویس بازی، نه ترجمه خشک ماشینی). Keep character names natural in Persian script. Output ONLY the Persian translation:\n$text"
            val body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", prompt)
                    }))
                }))
            }

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val response = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
        } catch (_: Exception) {
            null
        }
    }

    private fun translateWithWebFallback(text: String): String? {
        return try {
            val prepared = normalizeGameTerms(text)
            val encoded = URLEncoder.encode(prepared, "UTF-8")
            val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=fa&dt=t&q=$encoded")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 4000
            conn.readTimeout = 4000

            val response = conn.inputStream.bufferedReader().readText()
            val jsonArray = JSONArray(response).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until jsonArray.length()) {
                sb.append(jsonArray.getJSONArray(i).getString(0))
            }
            sb.toString()
                .replace("ي", "ی")
                .replace("ك", "ک")
                .trim()
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacks(scanRunnable)
        super.onDestroy()
        if (::overlayContainer.isInitialized) windowManager.removeView(overlayContainer)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }
}

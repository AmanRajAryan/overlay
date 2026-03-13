package aman.apptesters

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrackerAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var currentPackageName: String = ""

    private val usageMap = HashMap<String, Long>()
    private val sdf = SimpleDateFormat("MMM dd, yyyy - hh:mm a", Locale.getDefault())

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgThread = HandlerThread("TrackerBgThread").also { it.start() }
    private val bgHandler = Handler(bgThread.looper)

    private var wakeLock: PowerManager.WakeLock? = null

    private var tvAppName: TextView? = null
    private var tvTimer: TextView? = null
    private var tvDateTime: TextView? = null

    private val ignoredPackages = mutableSetOf<String>("com.android.systemui")

    private val timerRunnable = object : Runnable {
        override fun run() {
            val sharedPrefs = getSharedPreferences("AppTesterPrefs", Context.MODE_PRIVATE)
            val isTracking = sharedPrefs.getBoolean("is_tracking", false)

            mainHandler.post {
                if (!isTracking) {
                    overlayView?.visibility = View.GONE
                } else {
                    overlayView?.visibility = View.VISIBLE
                    if (currentPackageName.isNotEmpty()) {
                        usageMap[currentPackageName] = (usageMap[currentPackageName] ?: 0L) + 1
                        updateOverlayUI()
                    }
                }
            }

            bgHandler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        startForegroundService(Intent(this, TrackerForegroundService::class.java))

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AppTracker::BgWakeLock")
            .also { it.acquire() }

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.inputMethodList.forEach {
            ignoredPackages.add(it.packageName)
        }

        setupOverlay()
        bgHandler.post(timerRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        val eventType = event.eventType

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            if (ignoredPackages.contains(packageName)) return

            currentPackageName = packageName
            mainHandler.post { updateOverlayUI() }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, TrackerForegroundService::class.java))
        bgHandler.removeCallbacks(timerRunnable)
        bgThread.quitSafely()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        
        tvAppName = null
        tvTimer = null
        tvDateTime = null
        
        overlayView?.let { windowManager?.removeView(it) }
    }

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 100
        }

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.layout_overlay, null)

        tvAppName = overlayView?.findViewById(R.id.tvAppName)
        tvTimer = overlayView?.findViewById(R.id.tvTimer)
        tvDateTime = overlayView?.findViewById(R.id.tvDateTime)

        windowManager?.addView(overlayView, params)
    }

    private fun updateOverlayUI() {
        val seconds = usageMap[currentPackageName] ?: 0L
        val timeString = String.format(Locale.getDefault(), "%02dm %02ds", seconds / 60, seconds % 60)

        tvAppName?.text = currentPackageName
        tvTimer?.text = timeString
        tvDateTime?.text = sdf.format(Date())
    }
}

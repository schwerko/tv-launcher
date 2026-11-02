package com.azura.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.TransitionDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.window.OnBackInvokedDispatcher
import android.widget.EditText
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {

    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var adapter: AppAdapter

    private lateinit var grid: RecyclerView
    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherDesc: TextView
    private lateinit var agendaRow: LinearLayout
    private lateinit var agendaStatus: TextView
    private lateinit var settingsButton: TextView
    private lateinit var wallpaperCredit: TextView

    private var allApps: List<AppInfo> = emptyList()
    private var lastWeatherAt = 0L
    private var lastAgendaAt = 0L

    private var assistant: Assistant? = null
    private var askDialog: AskDialog? = null

    private var cielo: CieloClient? = null
    private var acDevice: CieloClient.Device? = null
    private var acDevices: List<CieloClient.Device> = emptyList()
    private var lastAcAt = 0L
    private var acBusy = false

    private var wallpaperPhotos: List<WorldPhotos.Photo> = emptyList()
    private var wallpaperIndex = -1
    private var wallpaperListAt = 0L
    private var wallpaperSource = ""
    private var currentBackground: Drawable? = null

    private val acTick = object : Runnable {
        override fun run() {
            refreshAc(force = false)
            handler.postDelayed(this, AC_TICK_MS)
        }
    }
    private val acResync = Runnable { refreshAc(force = true) }

    private val wallpaperRetry = Runnable { refreshWallpaper(force = true) }

    private val slide = object : Runnable {
        override fun run() {
            advanceWallpaper()
            handler.postDelayed(this, SLIDE_INTERVAL_MS)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = loadApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        grid = findViewById(R.id.grid)
        clockView = findViewById(R.id.clock)
        dateView = findViewById(R.id.date)
        weatherTemp = findViewById(R.id.weatherTemp)
        weatherDesc = findViewById(R.id.weatherDesc)
        agendaRow = findViewById(R.id.agendaRow)
        agendaStatus = findViewById(R.id.agendaStatus)
        settingsButton = findViewById(R.id.settingsButton)
        wallpaperCredit = findViewById(R.id.wallpaperCredit)

        adapter = AppAdapter(onClick = ::launchApp, onLongClick = ::showAppOptions)
        grid.layoutManager = GridLayoutManager(this, spanCount())
        grid.adapter = adapter
        grid.setHasFixedSize(true)
        grid.itemAnimator = null

        settingsButton.setOnClickListener { showSettings() }
        findViewById<TextView>(R.id.askButton).apply {
            setOnClickListener { openAsk() }
            setOnFocusChangeListener { v, focused ->
                val scale = if (focused) 1.08f else 1f
                v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            }
        }
        setupAcCard()
        settingsButton.setOnFocusChangeListener { v, focused ->
            val scale = if (focused) 1.08f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        // From Android 13 the system routes Back through this callback instead of onBackPressed()
        // once the app targets 33+ (and predictive back is on by default when targeting 36).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                focusFirstTile()
            }
        }

        loadApps()
        showFirstRunHint()
    }

    /** Once, when nothing is configured yet: say where the optional features are set up. */
    private fun showFirstRunHint() {
        if (prefs.hintShown) return
        val configured = prefs.icsUrl.isNotBlank() || prefs.geminiApiKey.isNotBlank() ||
            prefs.claudeApiKey.isNotBlank() || prefs.cieloApiKey.isNotBlank() || prefs.city.isNotBlank()
        prefs.hintShown = true
        if (configured) return
        dialog().setTitle(R.string.hint_title)
            .setMessage(getString(R.string.hint_body, WeatherService.defaultCity()))
            .setPositiveButton(R.string.hint_open_settings) { _, _ -> showSettings() }
            .setNegativeButton(R.string.hint_later, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        refreshWidgets(force = false)
        refreshWallpaper(force = false)
        handler.removeCallbacks(acTick)
        handler.post(acTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(slide)
        handler.removeCallbacks(wallpaperRetry)
        handler.removeCallbacks(acTick)
        handler.removeCallbacks(acResync)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(packageReceiver)
        askDialog?.release()
        scope.cancel()
    }

    /** The launcher is home: Back should not close it (pre-33 path; see onCreate for 33+). */
    @Deprecated("Deprecated in Java")
    @Suppress("GestureBackNavigation")
    override fun onBackPressed() {
        focusFirstTile()
    }

    private fun focusFirstTile() {
        grid.getChildAt(0)?.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            (currentFocus?.tag as? AppInfo)?.let { showAppOptions(it); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- apps -----------------------------------------------------------------------------

    private fun spanCount(): Int {
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        val tileDp = resources.getDimension(R.dimen.tile_width) / dm.density
        val paddingDp = 2 * resources.getDimension(R.dimen.screen_padding_h) / dm.density
        return ((widthDp - paddingDp) / tileDp).toInt().coerceAtLeast(3)
    }

    private fun loadApps() {
        scope.launch {
            allApps = withContext(Dispatchers.IO) { AppRepository.load(this@MainActivity) }
            applyFilter()
        }
    }

    private fun applyFilter() {
        val hidden = prefs.hidden
        val pinned = prefs.pinned
        val visible = allApps.filter { it.packageName !in hidden }
            .sortedWith(compareBy<AppInfo> { it.packageName !in pinned }.thenBy { it.label.lowercase() })
        val focusInGrid = currentFocus?.let { grid.findContainingItemView(it) != null } ?: false
        adapter.submitList(visible) {
            if (!focusInGrid) grid.post { (grid.getChildAt(0) ?: grid).requestFocus() }
        }
    }

    private fun launchApp(app: AppInfo) {
        val pm = packageManager
        val candidates = listOf(
            app.launchIntent,
            pm.getLeanbackLaunchIntentForPackage(app.packageName),
            pm.getLaunchIntentForPackage(app.packageName),
        )
        for (intent in candidates) {
            if (intent == null) continue
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, getString(R.string.cannot_open, app.label), Toast.LENGTH_SHORT).show()
    }

    private fun showAppOptions(app: AppInfo) {
        val pinned = app.packageName in prefs.pinned
        val items = arrayOf(
            getString(if (pinned) R.string.unpin else R.string.pin),
            getString(R.string.hide_app),
            getString(R.string.app_info),
            getString(R.string.uninstall),
        )
        dialog().setTitle(app.label).setItems(items) { _, which ->
            when (which) {
                0 -> {
                    prefs.pinned = if (pinned) prefs.pinned - app.packageName else prefs.pinned + app.packageName
                    applyFilter()
                }
                1 -> {
                    prefs.hidden = prefs.hidden + app.packageName
                    applyFilter()
                }
                2 -> startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
                )
                3 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
            }
        }.show()
    }

    // ---- settings -------------------------------------------------------------------------

    private fun showSettings() {
        val city = prefs.city.ifBlank { getString(R.string.city_default, WeatherService.defaultCity()) }
        val unit = if (prefs.fahrenheit) "°F" else "°C"
        val background = when (prefs.wallpaper) {
            WallpaperService.NONE -> getString(R.string.background_none)
            WallpaperService.COMMONS -> getString(R.string.background_commons)
            WallpaperService.UNSPLASH -> getString(R.string.background_unsplash)
            else -> getString(R.string.background_custom)
        }
        val items: List<Pair<String, () -> Unit>> = listOf(
            getString(R.string.setting_city, city) to {
                promptText(getString(R.string.city_prompt), prefs.city, getString(R.string.city_hint)) {
                    prefs.city = it
                    refreshWidgets(force = true)
                }
            },
            getString(R.string.setting_unit, unit) to {
                prefs.fahrenheit = !prefs.fahrenheit
                refreshWidgets(force = true)
            },
            getString(R.string.setting_ics, if (prefs.icsUrl.isBlank()) getString(R.string.not_set) else getString(R.string.configured)) to {
                promptText(getString(R.string.ics_prompt), prefs.icsUrl, "https://calendar.google.com/calendar/ical/.../basic.ics") {
                    prefs.icsUrl = it
                    refreshWidgets(force = true)
                }
            },
            getString(R.string.setting_background, background) to { showBackgroundChooser() },
            getString(R.string.setting_assistant, if (prefs.assistant == "gemini") "Gemini (free)" else "Claude") to {
                val choices = arrayOf(getString(R.string.assistant_gemini), getString(R.string.assistant_claude))
                dialog().setTitle(R.string.setting_assistant_title).setItems(choices) { _, which ->
                    prefs.assistant = if (which == 0) "gemini" else "claude"
                    resetAssistant()
                }.show()
            },
            getString(R.string.setting_gemini, if (prefs.geminiApiKey.isBlank()) getString(R.string.not_set) else getString(R.string.configured)) to {
                promptText(getString(R.string.gemini_prompt), prefs.geminiApiKey, "") {
                    prefs.geminiApiKey = it
                    resetAssistant()
                }
            },
            getString(R.string.setting_claude, if (prefs.claudeApiKey.isBlank()) getString(R.string.not_set) else getString(R.string.configured)) to {
                promptText(getString(R.string.claude_prompt), prefs.claudeApiKey, "sk-ant-…") {
                    prefs.claudeApiKey = it
                    resetAssistant()
                }
            },
            getString(R.string.setting_cielo, if (prefs.cieloApiKey.isBlank()) getString(R.string.not_set) else getString(R.string.configured)) to {
                promptText(getString(R.string.cielo_prompt), prefs.cieloApiKey, "") {
                    if (it != prefs.cieloApiKey) {
                        prefs.cieloApiKey = it
                        prefs.cieloToken = ""
                        prefs.cieloDevice = ""
                        cielo = null
                        acDevice = null
                        acDevices = emptyList()
                        acCard.visibility = View.GONE
                        refreshAc(force = true)
                    }
                }
            },
            getString(R.string.setting_choose_apps, allApps.size - prefs.hidden.count { h -> allApps.any { it.packageName == h } }, allApps.size) to {
                showAppChooser()
            },
            getString(R.string.setting_unhide, prefs.hidden.size) to {
                prefs.hidden = emptySet()
                applyFilter()
            },
            getString(R.string.setting_android) to { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }; Unit },
            getString(R.string.setting_default_home) to {
                runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                    .onFailure { Toast.makeText(this, R.string.no_home_settings, Toast.LENGTH_LONG).show() }
                Unit
            },
        )
        dialog().setTitle(R.string.settings)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    /** Checklist of every installed app; unticked ones are hidden from the grid. */
    private fun showAppChooser() {
        val apps = allApps
        if (apps.isEmpty()) return
        val hidden = prefs.hidden.toMutableSet()
        val checked = BooleanArray(apps.size) { apps[it].packageName !in hidden }
        dialog().setTitle(R.string.choose_apps_title)
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, which, isChecked ->
                if (isChecked) hidden.remove(apps[which].packageName) else hidden.add(apps[which].packageName)
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.hidden = hidden
                applyFilter()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBackgroundChooser() {
        val choices = arrayOf(
            getString(R.string.background_none),
            getString(R.string.background_commons),
            getString(R.string.background_unsplash),
            getString(R.string.background_custom),
        )
        dialog().setTitle(R.string.setting_background_title).setItems(choices) { _, which ->
            when (which) {
                0 -> { prefs.wallpaper = WallpaperService.NONE; refreshWallpaper(force = true) }
                1 -> { prefs.wallpaper = WallpaperService.COMMONS; refreshWallpaper(force = true) }
                2 -> promptText(getString(R.string.unsplash_prompt), prefs.unsplashKey, "") {
                    prefs.unsplashKey = it
                    if (it.isBlank()) {
                        Toast.makeText(this, R.string.unsplash_key_needed, Toast.LENGTH_LONG).show()
                    } else {
                        prefs.wallpaper = WallpaperService.UNSPLASH
                        refreshWallpaper(force = true)
                    }
                }
                3 -> {
                    val current = prefs.wallpaper.takeUnless { it == WallpaperService.COMMONS || it == WallpaperService.UNSPLASH } ?: ""
                    promptText(getString(R.string.background_prompt), current, "https://example.com/photo.jpg") {
                        prefs.wallpaper = it
                        refreshWallpaper(force = true)
                    }
                }
            }
        }.show()
    }

    private fun promptText(title: String, current: String, hint: String, onDone: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setHint(hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrapper = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        dialog().setTitle(title).setView(wrapper)
            .setPositiveButton(android.R.string.ok) { _, _ -> onDone(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    // ---- widgets --------------------------------------------------------------------------

    private fun updateClock() {
        val now = LocalDateTime.now()
        val timePattern = if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
        clockView.text = now.format(DateTimeFormatter.ofPattern(timePattern, Locale.getDefault()))
        dateView.text = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
    }

    private fun refreshWidgets(force: Boolean) {
        val now = System.currentTimeMillis()
        if (force || now - lastWeatherAt > 30 * 60_000L) {
            lastWeatherAt = now
            scope.launch {
                val w = WeatherService.fetch(prefs.city)
                if (w == null) {
                    weatherTemp.text = ""
                    weatherDesc.text = getString(R.string.weather_unavailable)
                } else {
                    weatherTemp.text = "${w.symbol} ${w.temperature(prefs.fahrenheit)}"
                    weatherDesc.text = if (w.place.isBlank()) w.description else "${w.description} · ${w.place}"
                }
            }
        }
        if (force || now - lastAgendaAt > 15 * 60_000L) {
            lastAgendaAt = now
            val url = prefs.icsUrl
            if (url.isBlank()) {
                agendaRow.visibility = View.GONE
                agendaStatus.visibility = View.GONE
                return
            }
            val urls = url.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }
            scope.launch {
                val events = CalendarService.upcoming(urls, max = 30)
                when {
                    events == null -> showAgendaStatus(getString(R.string.agenda_error))
                    events.isEmpty() -> showAgendaStatus(getString(R.string.agenda_empty))
                    else -> renderAgenda(events)
                }
            }
        }
    }

    private fun showAgendaStatus(text: String) {
        agendaRow.visibility = View.GONE
        agendaStatus.text = text
        agendaStatus.visibility = View.VISIBLE
    }

    /** One card per event, for the next two days that have anything scheduled. */
    private fun renderAgenda(events: List<CalendarService.Event>) {
        val days = events.map { it.start.toLocalDate() }.distinct().take(2)
        val shown = events.filter { it.start.toLocalDate() in days }.take(MAX_EVENT_CARDS)
        val inflater = LayoutInflater.from(this)
        agendaRow.removeAllViews()
        for (e in shown) {
            val card = inflater.inflate(R.layout.item_event, agendaRow, false)
            card.findViewById<TextView>(R.id.eventDay).text = dayLabel(e)
            card.findViewById<TextView>(R.id.eventTime).text = timeLabel(e)
            card.findViewById<TextView>(R.id.eventTitle).text = e.title
            agendaRow.addView(card)
        }
        agendaStatus.visibility = View.GONE
        agendaRow.visibility = View.VISIBLE
    }

    private fun dayLabel(e: CalendarService.Event): String {
        val today = LocalDateTime.now().toLocalDate()
        return when (e.start.toLocalDate()) {
            today -> getString(R.string.today)
            today.plusDays(1) -> getString(R.string.tomorrow)
            else -> e.start.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        }
    }

    private fun timeLabel(e: CalendarService.Event): String {
        if (e.allDay) return getString(R.string.all_day)
        val pattern = if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
        return e.start.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    // ---- background slideshow -------------------------------------------------------------

    private fun refreshWallpaper(force: Boolean) {
        val source = prefs.wallpaper
        handler.removeCallbacks(slide)
        if (source.isBlank()) {
            wallpaperPhotos = emptyList()
            wallpaperSource = source
            currentBackground = null
            wallpaperCredit.visibility = View.GONE
            window.setBackgroundDrawableResource(R.drawable.bg_gradient)
            return
        }
        val now = System.currentTimeMillis()
        val listStale = now - wallpaperListAt > WALLPAPER_LIST_MAX_AGE_MS
        if (force || source != wallpaperSource || wallpaperPhotos.isEmpty() || listStale) {
            wallpaperSource = source
            wallpaperListAt = now
            scope.launch {
                val photos = WallpaperService.sources(this@MainActivity, source, prefs.unsplashKey)
                if (photos.isEmpty()) {
                    val msg = if (source == WallpaperService.UNSPLASH) R.string.background_failed_unsplash else R.string.background_failed
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    // Usually a network blip or a rate limit at start-up: try once more in a while.
                    handler.removeCallbacks(wallpaperRetry)
                    handler.postDelayed(wallpaperRetry, WALLPAPER_RETRY_MS)
                    return@launch
                }
                wallpaperPhotos = photos
                wallpaperIndex = -1
                withContext(Dispatchers.IO) { WallpaperService.prune(this@MainActivity, photos.map { it.url }) }
                advanceWallpaper()
                handler.removeCallbacks(slide)
                handler.postDelayed(slide, SLIDE_INTERVAL_MS)
            }
        } else {
            if (currentBackground == null) advanceWallpaper()
            handler.postDelayed(slide, SLIDE_INTERVAL_MS)
        }
    }

    private fun advanceWallpaper() {
        val photos = wallpaperPhotos
        if (photos.isEmpty()) return
        wallpaperIndex = (wallpaperIndex + 1) % photos.size
        val photo = photos[wallpaperIndex]
        val dm = resources.displayMetrics
        scope.launch {
            val bitmap = WallpaperService.load(this@MainActivity, photo, dm.widthPixels, dm.heightPixels)
            if (bitmap != null && prefs.wallpaper == wallpaperSource) {
                showBackground(bitmap)
                // Free-licence and Unsplash photos must carry their credit wherever they are shown.
                wallpaperCredit.text = photo.credit
                wallpaperCredit.visibility = if (photo.credit.isBlank()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun showBackground(bitmap: Bitmap) {
        val next = LayerDrawable(arrayOf(
            BitmapDrawable(resources, bitmap),
            ContextCompat.getDrawable(this, R.drawable.scrim)!!,
        ))
        val previous = currentBackground
        if (previous == null) {
            window.setBackgroundDrawable(next)
        } else {
            val transition = TransitionDrawable(arrayOf(previous, next)).apply { isCrossFadeEnabled = false }
            window.setBackgroundDrawable(transition)
            transition.startTransition(CROSSFADE_MS)
        }
        currentBackground = next
    }

    // ---- Ask (Claude) ---------------------------------------------------------------------

    private fun openAsk() {
        val useGemini = prefs.assistant == "gemini"
        val key = if (useGemini) prefs.geminiApiKey else prefs.claudeApiKey
        if (key.isBlank()) {
            dialog().setTitle(R.string.ask)
                .setMessage(if (useGemini) R.string.ask_not_configured_gemini else R.string.ask_not_configured)
                .setPositiveButton(R.string.hint_open_settings) { _, _ -> showSettings() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val where = weatherDesc.text.toString().substringAfter("·", "").trim().ifBlank { WeatherService.defaultCity() }
        val client = assistant ?: (if (useGemini) GeminiClient(key, where) else ClaudeClient(key, where)).also { assistant = it }
        val dlg = askDialog ?: AskDialog(this, scope, client, ::startSpeech).also { askDialog = it }
        dlg.show()
    }

    private fun resetAssistant() {
        assistant = null
        askDialog?.release()
        askDialog = null
    }

    private fun startSpeech() {
        val dlg = askDialog ?: return
        if (dlg.canListenLocally()) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                dlg.startListening()
            } else {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_REQUEST)
            }
            return
        }
        for (intent in AskDialog.speechIntents()) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, AskDialog.SPEECH_REQUEST)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, R.string.ask_no_voice, Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) askDialog?.startListening()
            else askDialog?.showStatus(getString(R.string.ask_mic_permission))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == AskDialog.SPEECH_REQUEST) {
            val results = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            android.util.Log.d("Ask", "speech result code=$resultCode results=$results extras=${data?.extras?.keySet()}")
            val spoken = results?.firstOrNull()
            if (resultCode == RESULT_OK && !spoken.isNullOrBlank()) askDialog?.askSpoken(spoken)
            else askDialog?.showStatus(getString(R.string.ask_no_speech))
        }
    }

    // ---- Cielo air conditioner ------------------------------------------------------------

    private val acCard: View get() = findViewById(R.id.acCard)

    private fun setupAcCard() {
        val scaleOnFocus = View.OnFocusChangeListener { v, focused ->
            val scale = if (focused) 1.1f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(100).start()
        }
        listOf(R.id.acPower, R.id.acMinus, R.id.acPlus, R.id.acMode, R.id.acFan).forEach {
            findViewById<View>(it).onFocusChangeListener = scaleOnFocus
        }
        findViewById<View>(R.id.acName).setOnClickListener {
            val list = acDevices
            if (list.size < 2) return@setOnClickListener
            val current = acDevice
            val next = list[(list.indexOfFirst { it.mac == current?.mac } + 1).mod(list.size)]
            acDevice = next
            prefs.cieloDevice = next.mac
            renderAc()
        }
        findViewById<View>(R.id.acPower).setOnClickListener {
            val d = acDevice ?: return@setOnClickListener
            sendAc("power", mapOf("power" to if (d.power) "off" else "on"))
        }
        findViewById<View>(R.id.acMinus).setOnClickListener { nudgeTemp(-1) }
        findViewById<View>(R.id.acPlus).setOnClickListener { nudgeTemp(+1) }
        findViewById<View>(R.id.acMode).setOnClickListener {
            val d = acDevice ?: return@setOnClickListener
            // Cielo's API silently ignores "auto" on these units, so the button is a cool/heat toggle.
            val next = if (d.mode.equals("cool", ignoreCase = true)) "heat" else "cool"
            val changes = HashMap<String, Any>()
            changes["mode"] = next
            changes["power"] = "on"
            changes["preset"] = 0
            // Snap the set point and fan speed to values the new mode accepts.
            val temps = d.temperaturesFor(next)
            val sp = d.setPoint
            if (temps.isNotEmpty() && (sp == null || sp !in temps)) {
                changes["set_point"] = asNumber(temps.minByOrNull { Math.abs(it - (sp ?: temps.first())) }!!)
            }
            val fans = d.fanLevelsFor(next)
            if (fans.isNotEmpty() && d.fan.lowercase() !in fans) changes["fan_speed"] = fans.first()
            sendAc("mode", changes)
        }
        findViewById<View>(R.id.acFan).setOnClickListener {
            val d = acDevice ?: return@setOnClickListener
            val order = d.fanLevelsFor(d.mode).ifEmpty { CieloClient.FAN_ORDER }
            val next = order[(order.indexOf(d.fan.lowercase()) + 1).mod(order.size)]
            sendAc("fan_speed", mapOf("fan_speed" to next, "preset" to 0))
        }
    }

    private fun nudgeTemp(delta: Int) {
        val d = acDevice ?: return
        if (!d.power) return
        val current = d.setPoint ?: return
        val allowed = d.temperaturesFor(d.mode)
        val next = if (allowed.isNotEmpty()) {
            val i = allowed.indexOfFirst { it >= current }.let { if (it < 0) allowed.size - 1 else it }
            allowed[(i + delta).coerceIn(0, allowed.size - 1)]
        } else {
            val (lo, hi) = if (d.unit == "C") 16.0 to 30.0 else 60.0 to 86.0
            (current + delta).coerceIn(lo, hi)
        }
        if (next == current) return
        sendAc("set_point", mapOf("set_point" to asNumber(next), "preset" to 0))
    }

    private fun asNumber(v: Double): Any = if (v == Math.floor(v)) v.toInt() else v

    private fun refreshAc(force: Boolean) {
        val key = prefs.cieloApiKey
        if (key.isBlank()) {
            acCard.visibility = View.GONE
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastAcAt < AC_REFRESH_MS) return
        lastAcAt = now
        val client = cielo ?: CieloClient(key, prefs.cieloToken) { prefs.cieloToken = it }.also { cielo = it }
        scope.launch {
            try {
                val devices = client.devices()
                if (devices.isEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.cielo_no_devices, Toast.LENGTH_LONG).show()
                    acCard.visibility = View.GONE
                    return@launch
                }
                acDevices = devices
                val wanted = acDevice?.mac?.takeIf { m -> devices.any { it.mac == m } } ?: prefs.cieloDevice
                acDevice = devices.firstOrNull { it.mac == wanted } ?: devices.first()
                renderAc()
            } catch (e: CieloClient.AuthException) {
                Toast.makeText(this@MainActivity, getString(R.string.cielo_auth_failed) + ": " + e.message, Toast.LENGTH_LONG).show()
                acCard.visibility = View.GONE
            } catch (e: Exception) {
                android.util.Log.w("Cielo", "refresh failed", e)
                if (acDevice == null) Toast.makeText(this@MainActivity, R.string.cielo_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendAc(actionType: String, changes: Map<String, Any>) {
        val client = cielo ?: return
        val device = acDevice ?: return
        if (acBusy) return
        acBusy = true
        scope.launch {
            try {
                val newState = client.send(device, actionType, changes)
                val merged = JSONObject(device.state.toString())
                if (newState != null) newState.keys().forEach { merged.put(it, newState.opt(it)) }
                else changes.forEach { (k, v) -> merged.put(k, v) }
                acDevice = device.copy(state = merged)
                renderAc()
                // The device list lags a few seconds behind a command; re-sync once it has caught up.
                handler.removeCallbacks(acResync)
                handler.postDelayed(acResync, AC_RESYNC_MS)
            } catch (e: Exception) {
                android.util.Log.w("Cielo", "action failed", e)
                Toast.makeText(this@MainActivity, R.string.cielo_error, Toast.LENGTH_SHORT).show()
            } finally {
                acBusy = false
            }
        }
    }

    private fun renderAc() {
        val d = acDevice ?: return
        acCard.visibility = View.VISIBLE
        findViewById<TextView>(R.id.acName).text = if (acDevices.size > 1) "${d.name}  ▸" else d.name
        val temp = findViewById<TextView>(R.id.acTemp)
        temp.text = when {
            !d.online -> getString(R.string.cielo_offline)
            !d.power -> getString(R.string.cielo_off)
            d.setPoint != null -> "${d.setPoint!!.toInt()}°"
            else -> "—"
        }
        findViewById<TextView>(R.id.acMode).text = d.mode.replaceFirstChar { it.uppercase() }.ifBlank { "Mode" }
        findViewById<TextView>(R.id.acFan).text = getString(R.string.cielo_fan, d.fan.replaceFirstChar { it.uppercase() }.ifBlank { "—" })
        val room = buildString {
            d.roomTemp?.let { append(getString(R.string.cielo_room, "${it.toInt()}°")) }
            d.humidity?.let { if (isNotEmpty()) append(" · "); append("${it.toInt()}%") }
        }
        findViewById<TextView>(R.id.acRoom).text = room
        findViewById<TextView>(R.id.acRoom).visibility = if (room.isBlank()) View.GONE else View.VISIBLE
        val controlsEnabled = d.power
        listOf(R.id.acMinus, R.id.acPlus).forEach { findViewById<View>(it).alpha = if (controlsEnabled) 1f else 0.4f }
    }

    private companion object {
        const val MIC_PERMISSION_REQUEST = 4712
        const val AC_REFRESH_MS = 2 * 60_000L
        const val AC_TICK_MS = 30_000L
        const val AC_RESYNC_MS = 8_000L
        const val MAX_EVENT_CARDS = 6
        const val SLIDE_INTERVAL_MS = 10 * 60_000L
        const val WALLPAPER_LIST_MAX_AGE_MS = 6 * 60 * 60_000L
        const val WALLPAPER_RETRY_MS = 3 * 60_000L
        const val CROSSFADE_MS = 1500
    }
}

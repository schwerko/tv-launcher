package com.azura.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.widget.ImageView
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
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
    private lateinit var sunTimes: TextView
    private lateinit var newsScroll: View
    private lateinit var newsRow: LinearLayout
    private lateinit var haScroll: View
    private lateinit var haRow: LinearLayout
    private lateinit var tonightCard: View
    private lateinit var agendaScroll: View
    private lateinit var agendaRow: LinearLayout
    private lateinit var agendaStatus: TextView
    private lateinit var settingsButton: TextView
    private lateinit var wallpaperCredit: TextView

    private var allApps: List<AppInfo> = emptyList()
    private var lastWeatherAt = 0L
    private var lastAgendaAt = 0L
    private var lastNewsAt = 0L
    private var lastTonightAt = 0L
    private var tonightBusy = false

    private var ha: HomeAssistant? = null
    private var haEntities: List<HomeAssistant.Entity> = emptyList()
    private var lastHaAt = 0L
    private var haBusy = false
    private var haFailed = false
    private val haTick = object : Runnable {
        override fun run() {
            refreshHa(force = false)
            handler.postDelayed(this, HA_TICK_MS)
        }
    }
    private val haResync = Runnable { refreshHa(force = true) }

    private var assistant: Assistant? = null
    private var askDialog: AskDialog? = null

    private var cielo: CieloClient? = null

    // Mitsubishi Comfort (Kumo) units: when any are set up, the AC card controls them instead of Cielo.
    private var kumoUnits: List<KumoClient.Unit> = emptyList()
    private var kumoStatus: Map<String, KumoClient.Status> = emptyMap()
    private var kumoProfiles: Map<String, KumoClient.Profile> = emptyMap()
    private var kumoLastMode: MutableMap<String, String> = HashMap()
    private var kumoBusy = false
    private var kumoRelocating = false
    private var kumoCloud: KumoClient.Cloud? = null
    private var kumoCloudExpired = false

    /** Units without a local key are controlled through Mitsubishi's cloud. */
    private fun KumoClient.Unit.viaCloud() = password.isBlank() || cryptoSerial.isBlank()

    private fun cloud(): KumoClient.Cloud? =
        kumoCloud ?: prefs.kumoRefresh.takeIf { it.isNotBlank() }?.let { r ->
            KumoClient.Cloud(r) { prefs.kumoRefresh = it }.also { kumoCloud = it }
        }

    private suspend fun kumoStatusOf(u: KumoClient.Unit): KumoClient.Status? =
        if (!u.viaCloud()) KumoClient.status(u) else try {
            cloud()?.status(u.serial)
        } catch (e: KumoClient.AuthException) {
            kumoCloudExpired = true; null
        }

    private suspend fun kumoProfileOf(u: KumoClient.Unit): KumoClient.Profile? =
        if (!u.viaCloud()) KumoClient.profile(u) else runCatching { cloud()?.profile(u.serial) }.getOrNull()
    private val kumoActive: Boolean get() = kumoUnits.isNotEmpty()
    private val kumoUnit: KumoClient.Unit? get() = kumoUnits.firstOrNull { it.serial == prefs.kumoSelected } ?: kumoUnits.firstOrNull()
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
        prefs = Prefs(this)
        // The theme has to be chosen before any view is created.
        if (isDark) setTheme(R.style.Theme_Launcher_Dark)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        grid = findViewById(R.id.grid)
        clockView = findViewById(R.id.clock)
        dateView = findViewById(R.id.date)
        weatherTemp = findViewById(R.id.weatherTemp)
        weatherDesc = findViewById(R.id.weatherDesc)
        sunTimes = findViewById(R.id.sunTimes)
        newsScroll = findViewById(R.id.newsScroll)
        newsRow = findViewById(R.id.newsRow)
        haScroll = findViewById(R.id.haScroll)
        haRow = findViewById(R.id.haRow)
        tonightCard = findViewById(R.id.tonightCard)
        agendaScroll = findViewById(R.id.agendaScroll)
        agendaRow = findViewById(R.id.agendaRow)
        agendaStatus = findViewById(R.id.agendaStatus)
        settingsButton = findViewById(R.id.settingsButton)
        wallpaperCredit = findViewById(R.id.wallpaperCredit)
        if (isDark) {
            // The weather symbol is a colour emoji; draw it in greyscale to match the theme.
            val grey = android.graphics.Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
            }
            weatherTemp.setLayerType(View.LAYER_TYPE_HARDWARE, grey)
        }

        adapter = AppAdapter(onClick = ::launchApp, onLongClick = ::showAppOptions)
        grid.layoutManager = GridLayoutManager(this, spanCount())
        grid.adapter = adapter
        // The grid sits in the page's NestedScrollView and shows all its rows; the page scrolls instead.
        // (A plain ScrollView passes the remaining screen height to the grid, which then lays out
        // only the rows that fit and scrolls inside itself, drawing over the rows above.)
        grid.isNestedScrollingEnabled = false
        grid.itemAnimator = null

        settingsButton.setOnClickListener { showSettings() }
        findViewById<TextView>(R.id.askButton).apply {
            setOnClickListener { openAsk() }
            setOnFocusChangeListener { v, focused ->
                val scale = if (focused) 1.08f else 1f
                v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            }
        }
        kumoUnits = prefs.kumoUnits
        setupAcCard()
        setupMediaCard()
        tonightCard.setOnClickListener { showTonightDetails() }
        tonightCard.setOnFocusChangeListener { v, focused ->
            val scale = if (focused) 1.02f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
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

        // The header stays fixed; when focus returns to it, bring the rows back to the top.
        val page = findViewById<View>(R.id.page)
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            var v: View? = now
            while (v != null && v !== page) v = v.parent as? View
            if (now != null && v == null) (page as androidx.core.widget.NestedScrollView).smoothScrollTo(0, 0)
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
        handler.removeCallbacks(haTick)
        handler.post(haTick)
        startMediaWatch()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(slide)
        handler.removeCallbacks(wallpaperRetry)
        handler.removeCallbacks(acTick)
        handler.removeCallbacks(acResync)
        handler.removeCallbacks(haTick)
        handler.removeCallbacks(haResync)
        // The setup page lives only while its screen is open; leaving the launcher closes both.
        setupDialog?.dismiss()
        setupServer?.stop()
        setupServer = null
        stopMediaWatch()
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
            getString(R.string.setting_news, newsSummary()) to { showNewsChooser() },
            getString(R.string.setting_ha, if (prefs.haUrl.isBlank()) getString(R.string.not_set) else prefs.haUrl.removePrefix("http://").removePrefix("https://")) to {
                showHaSettings()
            },
            getString(R.string.setting_tonight, getString(when {
                !prefs.tonightEnabled -> R.string.tonight_off
                prefs.geminiApiKey.isBlank() && prefs.claudeApiKey.isBlank() -> R.string.tonight_needs_key
                else -> R.string.tonight_on
            })) to {
                prefs.tonightEnabled = !prefs.tonightEnabled
                refreshTonight(force = false)
            },
            getString(R.string.setting_tonight_interests,
                prefs.tonightInterests.ifEmpty { listOf(getString(R.string.tonight_interests_any)) }.joinToString(", ").let {
                    if (it.length > 40) it.take(39) + "…" else it
                }) to { showTonightInterests() },
            getString(R.string.setting_theme, getString(if (isDark) R.string.theme_dark else R.string.theme_photo)) to { showThemeChooser() },
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
            getString(R.string.setting_kumo, if (kumoUnits.isEmpty()) getString(R.string.not_set) else resources.getQuantityString(R.plurals.kumo_units, kumoUnits.size, kumoUnits.size)) to {
                showKumoSettings()
            },
            getString(R.string.setting_media, getString(if (mediaListenerEnabled()) R.string.media_on else R.string.media_needs_access)) to {
                showMediaAccessHelp()
            },
            getString(R.string.setting_choose_apps, allApps.size - prefs.hidden.count { h -> allApps.any { it.packageName == h } }, allApps.size) to {
                showAppChooser()
            },
            getString(R.string.setting_unhide, prefs.hidden.size) to {
                prefs.hidden = emptySet()
                applyFilter()
            },
            getString(R.string.setting_phone_setup) to { showPhoneSetup() },
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

    // ---- Set up from your phone -----------------------------------------------------------

    private var setupServer: SetupServer? = null
    private var setupDialog: AlertDialog? = null

    /** Shows the QR code / address / code and runs the local setup page until the dialog closes. */
    private fun showPhoneSetup() {
        setupServer?.stop()
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_phone_setup, null)
        val status = view.findViewById<TextView>(R.id.setupStatus)
        val server = SetupServer(
            port = SETUP_PORT,
            current = {
                mapOf(
                    "city" to prefs.city,
                    "ics" to prefs.icsUrl.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }.joinToString("\n"),
                    "news" to newsUrls().joinToString("\n"),
                    "wallpaper_urls" to prefs.wallpaper.takeUnless { it == WallpaperService.COMMONS || it == WallpaperService.UNSPLASH }
                        .orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString("\n"),
                    "ha_url" to prefs.haUrl,
                    "kumo_email" to prefs.kumoEmail,
                )
            },
            secretsSet = {
                buildSet {
                    if (prefs.geminiApiKey.isNotBlank()) add("gemini_key")
                    if (prefs.claudeApiKey.isNotBlank()) add("claude_key")
                    if (prefs.cieloApiKey.isNotBlank()) add("cielo_key")
                    if (prefs.unsplashKey.isNotBlank()) add("unsplash_key")
                    if (prefs.haToken.isNotBlank()) add("ha_token")
                }
            },
            onSave = { values -> runOnUiThread { applyPhoneSetup(values); status.text = getString(R.string.phone_setup_saved, clockText(LocalTime.now())) } },
        )
        if (!server.start()) {
            dialog().setTitle(R.string.phone_setup_title).setMessage(R.string.phone_setup_no_network)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        setupServer = server
        view.findViewById<TextView>(R.id.setupText).setText(R.string.phone_setup_text)
        view.findViewById<TextView>(R.id.setupAddress).text = getString(R.string.phone_setup_address, server.address)
        view.findViewById<TextView>(R.id.setupCode).text = getString(R.string.phone_setup_code, server.code)
        status.setText(R.string.phone_setup_waiting)
        view.findViewById<ImageView>(R.id.setupQr).setImageBitmap(qrBitmap(server.address, 512))
        setupDialog = dialog().setTitle(R.string.phone_setup_title).setView(view)
            .setPositiveButton(R.string.phone_setup_close, null)
            .setOnDismissListener { server.stop(); if (setupServer === server) setupServer = null; setupDialog = null }
            .show()
    }

    /** Applies fields posted from the phone page; blank fields were already dropped for secrets. */
    private fun applyPhoneSetup(v: Map<String, String>) {
        var widgets = false; var news = false; var ha = false; var wallpaper = false; var ask = false
        v["city"]?.let { if (it != prefs.city) { prefs.city = it; widgets = true } }
        v["ics"]?.let { val joined = it.split(Regex("[\\s,;]+")).filter { u -> u.isNotBlank() }.joinToString(" "); if (joined != prefs.icsUrl) { prefs.icsUrl = joined; widgets = true } }
        v["news"]?.let { val joined = NewsService.normalise(it).joinToString(" "); if (joined != prefs.newsUrls) { prefs.newsUrls = joined; news = true } }
        v["wallpaper_urls"]?.let { val joined = it.split(Regex("\\s+")).filter { u -> u.isNotBlank() }.joinToString(" "); if (joined.isNotBlank()) { prefs.wallpaper = joined; wallpaper = true } }
        v["unsplash_key"]?.let { prefs.unsplashKey = it; prefs.wallpaper = WallpaperService.UNSPLASH; wallpaper = true }
        v["gemini_key"]?.let { prefs.geminiApiKey = it; ask = true }
        v["claude_key"]?.let { prefs.claudeApiKey = it; ask = true }
        v["cielo_key"]?.let {
            if (it != prefs.cieloApiKey) {
                prefs.cieloApiKey = it; prefs.cieloToken = ""; prefs.cieloDevice = ""
                cielo = null; acDevice = null; acDevices = emptyList(); acCard.visibility = View.GONE
                refreshAc(force = true)
            }
        }
        v["ha_url"]?.let { if (it != prefs.haUrl) { prefs.haUrl = it; ha = true } }
        v["ha_token"]?.let { prefs.haToken = it; ha = true }
        if (ha) { this.ha = null; haFailed = false; refreshHa(force = true) }
        val kumoPassword = v["kumo_password"].orEmpty()
        val kumoEmail = v["kumo_email"].orEmpty().ifBlank { prefs.kumoEmail }
        if (kumoPassword.isNotEmpty() && kumoEmail.isNotBlank()) setupKumo(kumoEmail, kumoPassword)
        else v["kumo_email"]?.let { prefs.kumoEmail = it }
        if (ask) resetAssistant()
        if (widgets) refreshWidgets(force = true)
        if (news) refreshNews(force = true)
        if (wallpaper) refreshWallpaper(force = true)
        Toast.makeText(this, R.string.phone_setup_title, Toast.LENGTH_SHORT).show()
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (y in 0 until size) for (x in 0 until size) bmp.setPixel(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        return bmp
    }

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
                    sunTimes.visibility = View.GONE
                } else {
                    weatherTemp.text = "${w.symbol} ${w.temperature(prefs.fahrenheit)}"
                    weatherDesc.text = if (w.place.isBlank()) w.description else "${w.description} · ${w.place}"
                    if (w.sunrise != null && w.sunset != null) {
                        sunTimes.text = getString(R.string.sun_times, clockText(w.sunrise), clockText(w.sunset))
                        sunTimes.visibility = View.VISIBLE
                    } else {
                        sunTimes.visibility = View.GONE
                    }
                }
            }
        }
        refreshNews(force)
        refreshTonight(force)
        if (force || now - lastAgendaAt > 15 * 60_000L) {
            lastAgendaAt = now
            val url = prefs.icsUrl
            if (url.isBlank()) {
                agendaScroll.visibility = View.GONE
                agendaStatus.visibility = View.GONE
            } else {
                val urls = url.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }
                scope.launch {
                    val events = CalendarService.upcoming(urls, max = MAX_EVENT_CARDS)
                    when {
                        events == null -> showAgendaStatus(getString(R.string.agenda_error))
                        events.isEmpty() -> showAgendaStatus(getString(R.string.agenda_empty))
                        else -> renderAgenda(events)
                    }
                }
            }
        }
    }

    private fun clockText(t: LocalTime): String {
        val pattern = if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
        return t.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    /** Calendar and news cards: see-through at rest, nearly solid and slightly larger when focused. */
    private val glassCardFocus = View.OnFocusChangeListener { v, focused ->
        val scale = if (focused) 1.06f else 1f
        v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        (v as? androidx.cardview.widget.CardView)?.setCardBackgroundColor(
            themeColor(if (focused) R.attr.tlCardGlassFocused else R.attr.tlCardGlass))
    }

    private val isDark: Boolean get() = prefs.theme == THEME_DARK

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun showThemeChooser() {
        val choices = arrayOf(getString(R.string.theme_photo), getString(R.string.theme_dark))
        val current = if (isDark) 1 else 0
        dialog().setTitle(R.string.theme_title)
            .setSingleChoiceItems(choices, current) { d, which ->
                d.dismiss()
                val next = if (which == 1) THEME_DARK else THEME_PHOTO
                if (next != prefs.theme) {
                    prefs.theme = next
                    // Views and drawables pick their colours up when created, so start afresh.
                    recreate()
                }
            }
            .show()
    }

    private fun showAgendaStatus(text: String) {
        agendaScroll.visibility = View.GONE
        agendaStatus.text = text
        agendaStatus.visibility = View.VISIBLE
    }

    /** One card per event for the next 7 days; the row scrolls sideways and each card opens details. */
    private fun renderAgenda(events: List<CalendarService.Event>) {
        val inflater = LayoutInflater.from(this)
        val hadFocus = currentFocus?.let { agendaRow.indexOfChild(it) } ?: -1
        agendaRow.removeAllViews()
        for (e in events) {
            val card = inflater.inflate(R.layout.item_event, agendaRow, false)
            card.findViewById<TextView>(R.id.eventDay).text = dayLabel(e)
            card.findViewById<TextView>(R.id.eventTime).text = timeLabel(e)
            card.findViewById<TextView>(R.id.eventTitle).text = e.title
            card.setOnFocusChangeListener(glassCardFocus)
            card.setOnClickListener { showEventDetails(e) }
            agendaRow.addView(card)
        }
        agendaStatus.visibility = View.GONE
        agendaScroll.visibility = View.VISIBLE
        if (hadFocus in 0 until agendaRow.childCount) agendaRow.getChildAt(hadFocus).requestFocus()
    }

    private fun showEventDetails(e: CalendarService.Event) {
        val datePattern = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())
        val timePattern = DateTimeFormatter.ofPattern(
            if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a", Locale.getDefault())
        val whenLine = buildString {
            append(e.start.format(datePattern))
            if (e.allDay) {
                append(" · ").append(getString(R.string.all_day))
            } else {
                append(" · ").append(e.start.format(timePattern))
                e.end?.let { end -> if (end.isAfter(e.start)) append(" – ").append(end.format(timePattern)) }
            }
        }
        val body = buildString {
            append(whenLine)
            if (e.location.isNotBlank()) append("\n").append(e.location)
            if (e.description.isNotBlank()) append("\n\n").append(e.description.take(1500))
        }
        dialog().setTitle(e.title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
            if (isDark) window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
            else window.setBackgroundDrawableResource(R.drawable.bg_gradient)
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
        val photo = BitmapDrawable(resources, bitmap).apply {
            // The Dark theme shows the same photos in black and white.
            if (isDark) colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
        }
        val next = LayerDrawable(arrayOf(
            photo,
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
        assistantAndPanel()?.second?.show()
    }

    private fun resetAssistant() {
        assistant = null
        askDialog?.release()
        askDialog = null
        prefs.tonightDate = ""
        refreshTonight(force = false)
    }

    /** The configured assistant and its panel (created but not shown), or null without a key. */
    private fun assistantAndPanel(): Pair<Assistant, AskDialog>? {
        val useGemini = prefs.assistant == "gemini"
        val key = if (useGemini) prefs.geminiApiKey else prefs.claudeApiKey
        if (key.isBlank()) return null
        val where = weatherDesc.text.toString().substringAfter("·", "").trim().ifBlank { WeatherService.defaultCity() }
        val client = assistant ?: (if (useGemini) GeminiClient(key, where) else ClaudeClient(key, where)).also { assistant = it }
        val dlg = askDialog ?: AskDialog(this, scope, client, ::startSpeech).also { askDialog = it }
        return client to dlg
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

    // ---- Now playing (media sessions) -----------------------------------------------------

    private val mediaCard: View get() = findViewById(R.id.mediaCard)
    private var mediaManager: MediaSessionManager? = null
    private var mediaController: MediaController? = null
    private val mediaComponent by lazy { ComponentName(this, MediaListener::class.java) }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = renderMedia()
        override fun onMetadataChanged(metadata: MediaMetadata?) = renderMedia()
        override fun onSessionDestroyed() = pickMediaSession(mediaManager?.let { safeSessions(it) })
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pickMediaSession(it) }

    /** True once the user (or adb) has granted notification access to MediaListener. */
    private fun mediaListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    private fun safeSessions(mgr: MediaSessionManager): List<MediaController>? =
        runCatching { mgr.getActiveSessions(mediaComponent) }.getOrNull()

    private fun setupMediaCard() {
        val scaleOnFocus = View.OnFocusChangeListener { v, focused ->
            val scale = if (focused) 1.1f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(100).start()
        }
        listOf(R.id.mediaTitle, R.id.mediaPrev, R.id.mediaPlayPause, R.id.mediaNext).forEach {
            findViewById<View>(it).onFocusChangeListener = scaleOnFocus
        }
        findViewById<View>(R.id.mediaPrev).setOnClickListener { mediaController?.transportControls?.skipToPrevious() }
        findViewById<View>(R.id.mediaNext).setOnClickListener { mediaController?.transportControls?.skipToNext() }
        findViewById<View>(R.id.mediaPlayPause).setOnClickListener {
            val c = mediaController ?: return@setOnClickListener
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
        }
        findViewById<View>(R.id.mediaTitle).setOnClickListener {
            val pkg = mediaController?.packageName ?: return@setOnClickListener
            val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) runCatching { startActivity(intent) }
        }
    }

    private fun startMediaWatch() {
        if (!mediaListenerEnabled()) {
            mediaCard.visibility = View.GONE
            return
        }
        val mgr = mediaManager ?: (getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager).also { mediaManager = it }
        runCatching { mgr.addOnActiveSessionsChangedListener(sessionsListener, mediaComponent) }
            .onFailure { android.util.Log.w("Media", "no session access", it) }
        pickMediaSession(safeSessions(mgr))
    }

    private fun stopMediaWatch() {
        runCatching { mediaManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        mediaController?.unregisterCallback(mediaCallback)
        mediaController = null
    }

    /** Prefers a session that is playing, then one that is paused; otherwise hides the card. */
    private fun pickMediaSession(list: List<MediaController>?) {
        val sessions = list ?: emptyList()
        val best = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
        if (best?.sessionToken != mediaController?.sessionToken) {
            mediaController?.unregisterCallback(mediaCallback)
            mediaController = best
            best?.registerCallback(mediaCallback)
        }
        renderMedia()
    }

    private fun renderMedia() {
        val c = mediaController
        val state = c?.playbackState?.state
        if (c == null || state == null || state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_STOPPED) {
            mediaCard.visibility = View.GONE
            return
        }
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            .ifBlank { md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty() }
        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault(c.packageName)
        findViewById<TextView>(R.id.mediaTitle).text = title.ifBlank { appName }
        findViewById<TextView>(R.id.mediaArtist).text = if (artist.isBlank()) appName else "$artist · $appName"
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        findViewById<ImageView>(R.id.mediaArt).apply {
            if (art != null) setImageBitmap(art) else setImageDrawable(null)
        }
        findViewById<ImageView>(R.id.mediaPlayPause).setImageResource(
            if (state == PlaybackState.STATE_PLAYING) R.drawable.ic_pause else R.drawable.ic_play)
        mediaCard.visibility = View.VISIBLE
    }

    private fun showMediaAccessHelp() {
        val b = dialog().setTitle(R.string.media_access_title).setMessage(R.string.media_access_body)
            .setNegativeButton(android.R.string.cancel, null)
        val settingsIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        if (settingsIntent.resolveActivity(packageManager) != null) {
            b.setPositiveButton(R.string.media_access_open) { _, _ -> runCatching { startActivity(settingsIntent) } }
        }
        b.show()
    }

    // ---- News headlines (RSS/Atom) ----------------------------------------------------------

    private fun newsUrls(): List<String> = prefs.newsUrls.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }

    private fun newsSummary(): String {
        val n = newsUrls().size
        return if (n == 0) getString(R.string.not_set) else getString(R.string.news_feeds_count, n)
    }

    private fun refreshNews(force: Boolean) {
        val urls = newsUrls()
        if (urls.isEmpty()) {
            newsScroll.visibility = View.GONE
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastNewsAt < NEWS_REFRESH_MS) return
        lastNewsAt = now
        scope.launch {
            val items = NewsService.fetch(this@MainActivity, urls, MAX_NEWS_CARDS)
            if (items == null) {
                if (newsRow.childCount == 0) Toast.makeText(this@MainActivity, R.string.news_error, Toast.LENGTH_SHORT).show()
                return@launch
            }
            renderNews(items)
        }
    }

    private fun renderNews(items: List<NewsService.Item>) {
        val inflater = LayoutInflater.from(this)
        val hadFocus = currentFocus?.let { newsRow.indexOfChild(it) } ?: -1
        newsRow.removeAllViews()
        val thumbW = resources.getDimensionPixelSize(R.dimen.news_card_width)
        val thumbH = resources.getDimensionPixelSize(R.dimen.news_thumb_height)
        for (item in items) {
            val card = inflater.inflate(R.layout.item_news, newsRow, false)
            card.findViewById<TextView>(R.id.newsSource).text = item.source
            card.findViewById<TextView>(R.id.newsTitle).text =
                if (item.channelOnly) getString(R.string.news_channel_latest) else item.title
            card.findViewById<TextView>(R.id.newsAge).text = ageLabel(item.published)
            val thumb = card.findViewById<ImageView>(R.id.newsThumb)
            thumb.setImageDrawable(null)
            thumb.visibility = if (item.thumbnailUrl.isBlank()) View.GONE else View.VISIBLE
            if (item.thumbnailUrl.isNotBlank()) {
                scope.launch {
                    val bmp = WallpaperService.load(this@MainActivity, WorldPhotos.Photo(item.thumbnailUrl, "", "", ""), thumbW, thumbH, "news")
                    if (bmp != null && card.parent === newsRow) thumb.setImageBitmap(bmp)
                }
            }
            card.setOnFocusChangeListener(glassCardFocus)
            card.setOnClickListener { openNews(item) }
            newsRow.addView(card)
        }
        newsScroll.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (hadFocus in 0 until newsRow.childCount) newsRow.getChildAt(hadFocus).requestFocus()
        scope.launch(Dispatchers.IO) { WallpaperService.prune(this@MainActivity, items.map { it.thumbnailUrl }, "news") }
    }

    /** Videos play in YouTube; a channel card opens the channel; anything else becomes a YouTube search. */
    private fun openNews(item: NewsService.Item) {
        val url = when {
            item.videoId.isNotBlank() -> "https://www.youtube.com/watch?v=${item.videoId}"
            item.channelOnly -> item.link
            // YouTube's TV app shows '+' literally, so spaces go as %20.
            else -> "https://www.youtube.com/results?search_query=" +
                java.net.URLEncoder.encode(item.title, "UTF-8").replace("+", "%20")
        }
        val youtube = listOf("com.google.android.youtube.tv", "com.google.android.youtube")
            .firstOrNull { runCatching { packageManager.getPackageInfo(it, 0) }.isSuccess }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { if (youtube != null) setPackage(youtube) }
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { Toast.makeText(this, R.string.news_no_youtube, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun ageLabel(published: Long): String {
        if (published <= 0) return ""
        val minutes = ((System.currentTimeMillis() - published) / 60_000L).coerceAtLeast(0)
        return when {
            minutes < 2 -> getString(R.string.news_just_now)
            minutes < 60 -> getString(R.string.news_minutes_ago, minutes.toInt())
            minutes < 24 * 60 -> getString(R.string.news_hours_ago, (minutes / 60).toInt())
            else -> getString(R.string.news_days_ago, (minutes / (24 * 60)).toInt())
        }
    }

    /** Tick the well-known feeds, or type other URLs. */
    private fun showNewsChooser() {
        val current = newsUrls().toMutableList()
        val presets = NewsService.PRESETS
        val checked = BooleanArray(presets.size) { presets[it].url in current }
        dialog().setTitle(R.string.news_title)
            .setMultiChoiceItems(presets.map { it.label }.toTypedArray(), checked) { _, which, isChecked ->
                val url = presets[which].url
                if (isChecked) { if (url !in current) current.add(url) } else current.remove(url)
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.newsUrls = current.joinToString(" ")
                refreshNews(force = true)
            }
            .setNeutralButton(R.string.news_custom) { _, _ ->
                val custom = current.filter { url -> presets.none { it.url == url } }.joinToString(" ")
                val shown = custom.split(' ').filter { it.isNotBlank() }
                    .joinToString(" ") { NewsService.channelIdOf(it) ?: it }
                promptText(getString(R.string.news_custom_prompt), shown, "https://example.com/feed.xml") { typed ->
                    val keep = current.filter { url -> presets.any { it.url == url } }
                    prefs.newsUrls = (keep + NewsService.normalise(typed)).joinToString(" ")
                    refreshNews(force = true)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- What's on tonight (written by the Ask assistant once a day) -----------------------

    private fun refreshTonight(force: Boolean) {
        if (!prefs.tonightEnabled) {
            tonightCard.visibility = View.GONE
            return
        }
        val (client, _) = assistantAndPanel() ?: run { tonightCard.visibility = View.GONE; return }
        val today = LocalDate.now().toString()
        if (prefs.tonightDate == today && prefs.tonightText.isNotBlank()) {
            renderTonight(prefs.tonightText)
            return
        }
        val now = System.currentTimeMillis()
        if (tonightBusy || (!force && now - lastTonightAt < TONIGHT_RETRY_MS)) return
        lastTonightAt = now
        tonightBusy = true
        if (prefs.tonightText.isBlank()) renderTonight(getString(R.string.tonight_loading, client.name))
        val date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault()))
        val interests = prefs.tonightInterests
        val taste = if (interests.isEmpty()) "" else
            " The viewer likes: ${interests.joinToString(", ")}. Choose picks that fit those interests" +
            (if ("Sports" in interests) "." else "; skip live sport unless it is a major event.")
        val question = "It is $date. What is worth watching tonight?$taste Answer with exactly three lines and nothing " +
            "else, each at most fourteen words, in this order: a live sport, game or TV event on tonight with its " +
            "channel or service and local start time; a new or much talked-about series or film on a streaming " +
            "service; a great film for a rewatch. If you cannot be sure of tonight's live schedule, or live events " +
            "don't fit the viewer's interests, make the first line another streaming pick instead of guessing. " +
            "Write each line as the title, a colon, then why. No numbering, no introduction."
        scope.launch {
            try {
                val a = client.ask(question)
                if (a.refused || a.text.isBlank()) throw IllegalStateException(getString(R.string.ask_empty))
                prefs.tonightText = a.text.lines().map { it.trim().trimStart('-', '•', '*', ' ') }
                    .filter { it.isNotBlank() }.take(3).joinToString("\n")
                prefs.tonightTitles = a.titles
                prefs.tonightDate = today
                renderTonight(prefs.tonightText)
            } catch (e: Exception) {
                android.util.Log.w("Tonight", "failed", e)
                if (prefs.tonightText.isBlank()) {
                    renderTonight(getString(R.string.tonight_failed, e.message ?: client.name))
                } else {
                    renderTonight(prefs.tonightText)
                }
            } finally {
                tonightBusy = false
            }
        }
    }

    private fun showTonightInterests() {
        val all = resources.getStringArray(R.array.tonight_interests)
        val chosen = prefs.tonightInterests.toMutableList()
        val checked = BooleanArray(all.size) { all[it] in chosen }
        dialog().setTitle(R.string.tonight_interests_title)
            .setMultiChoiceItems(all, checked) { _, which, isChecked ->
                if (isChecked) { if (all[which] !in chosen) chosen.add(all[which]) } else chosen.remove(all[which])
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ordered = all.filter { it in chosen }
                if (ordered != prefs.tonightInterests) {
                    prefs.tonightInterests = ordered
                    prefs.tonightDate = ""
                    refreshTonight(force = true)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderTonight(text: String) {
        findViewById<TextView>(R.id.tonightText).text = text
        tonightCard.visibility = View.VISIBLE
    }

    private fun showTonightDetails() {
        val (client, panel) = assistantAndPanel() ?: return
        val text = prefs.tonightText.ifBlank { findViewById<TextView>(R.id.tonightText).text.toString() }
        val titles = prefs.tonightTitles
        val b = dialog().setTitle(R.string.tonight_heading)
            .setNeutralButton(R.string.tonight_refresh) { _, _ ->
                prefs.tonightDate = ""
                refreshTonight(force = true)
            }
            .setPositiveButton(R.string.tonight_more) { _, _ -> panel.show() }
            .setNegativeButton(android.R.string.cancel, null)
        if (titles.isEmpty()) {
            b.setMessage(text)
        } else {
            // The lines, then one row per title that can be opened in a streaming app.
            val items = (text.lines().map { it } + titles.map { getString(R.string.ask_open_title, it) }).toTypedArray()
            val lineCount = text.lines().size
            b.setItems(items) { _, which -> if (which >= lineCount) panel.openTitle(titles[which - lineCount]) }
        }
        b.show()
        android.util.Log.d("Tonight", "by ${client.name}")
    }

    // ---- Home Assistant -------------------------------------------------------------------

    private fun haClient(): HomeAssistant? {
        val url = prefs.haUrl
        val token = prefs.haToken
        if (url.isBlank() || token.isBlank()) return null
        return ha ?: HomeAssistant(url, token).also { ha = it }
    }

    private fun refreshHa(force: Boolean) {
        val client = haClient()
        val wanted = prefs.haEntities
        if (client == null || wanted.isEmpty()) {
            haScroll.visibility = View.GONE
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastHaAt < HA_REFRESH_MS) return
        lastHaAt = now
        scope.launch {
            try {
                val byId = client.states().associateBy { it.id }
                haEntities = wanted.mapNotNull { byId[it] }
                haFailed = false
                haScroll.alpha = 1f
                renderHa()
            } catch (e: HomeAssistant.AuthException) {
                if (!haFailed) Toast.makeText(this@MainActivity, R.string.ha_auth_failed, Toast.LENGTH_LONG).show()
                haFailed = true
                haScroll.visibility = View.GONE
            } catch (e: Exception) {
                android.util.Log.w("HA", "refresh failed", e)
                if (!haFailed && haEntities.isEmpty()) {
                    Toast.makeText(this@MainActivity, getString(R.string.ha_error, prefs.haUrl), Toast.LENGTH_SHORT).show()
                }
                haFailed = true
                // Keep the last known chips but dim them: the server is unreachable right now.
                haScroll.alpha = 0.5f
            }
        }
    }

    private fun renderHa() {
        val inflater = LayoutInflater.from(this)
        val hadFocus = currentFocus?.let { haRow.indexOfChild(it) } ?: -1
        haRow.removeAllViews()
        for (e in haEntities) {
            val chip = inflater.inflate(R.layout.item_ha, haRow, false)
            chip.findViewById<TextView>(R.id.haName).text = e.name
            chip.findViewById<TextView>(R.id.haState).text = e.stateLabel()
            chip.alpha = if (e.state == "unavailable") 0.5f else 1f
            chip.setOnFocusChangeListener { v, focused ->
                val scale = if (focused) 1.08f else 1f
                v.animate().scaleX(scale).scaleY(scale).setDuration(100).start()
            }
            chip.setOnClickListener { pressHa(e) }
            haRow.addView(chip)
        }
        haScroll.visibility = if (haEntities.isEmpty()) View.GONE else View.VISIBLE
        if (hadFocus in 0 until haRow.childCount) haRow.getChildAt(hadFocus).requestFocus()
    }

    private fun pressHa(e: HomeAssistant.Entity) {
        val client = haClient() ?: return
        if (!e.actionable) {
            dialog().setTitle(e.name).setMessage("${e.id}\n${e.stateLabel()}").setPositiveButton(android.R.string.ok, null).show()
            return
        }
        if (haBusy) return
        haBusy = true
        scope.launch {
            try {
                client.act(e)
                // Optimistic flip for toggles; the real state arrives with the resync.
                if (e.domain in HomeAssistant.TOGGLE && (e.state == "on" || e.state == "off")) {
                    haEntities = haEntities.map { if (it.id == e.id) it.copy(state = if (e.state == "on") "off" else "on") else it }
                    renderHa()
                }
                handler.removeCallbacks(haResync)
                handler.postDelayed(haResync, HA_RESYNC_MS)
            } catch (ex: HomeAssistant.AuthException) {
                Toast.makeText(this@MainActivity, R.string.ha_auth_failed, Toast.LENGTH_LONG).show()
            } catch (ex: Exception) {
                android.util.Log.w("HA", "action failed", ex)
                Toast.makeText(this@MainActivity, getString(R.string.ha_action_failed, ex.message ?: ""), Toast.LENGTH_SHORT).show()
            } finally {
                haBusy = false
            }
        }
    }

    private fun showHaSettings() {
        val items: List<Pair<String, () -> Unit>> = listOf(
            getString(R.string.ha_url_item, prefs.haUrl.ifBlank { getString(R.string.not_set) }) to {
                promptText(getString(R.string.ha_url_prompt), prefs.haUrl, "http://homeassistant.local:8123") {
                    prefs.haUrl = it
                    ha = null
                    haFailed = false
                    refreshHa(force = true)
                }
            },
            getString(R.string.ha_token_item, if (prefs.haToken.isBlank()) getString(R.string.not_set) else getString(R.string.configured)) to {
                promptText(getString(R.string.ha_token_prompt), prefs.haToken, "") {
                    prefs.haToken = it
                    ha = null
                    haFailed = false
                    refreshHa(force = true)
                }
            },
            getString(R.string.ha_entities_item, prefs.haEntities.size) to { showHaEntityChooser() },
        )
        // (No setMessage here: an AlertDialog shows either a message or a list, not both.)
        dialog().setTitle(R.string.ha_title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    /** Lists the server's lights, switches, scenes, scripts and sensors; ticked ones go on the row. */
    private fun showHaEntityChooser() {
        val client = haClient()
        if (client == null) {
            Toast.makeText(this, R.string.ha_help, Toast.LENGTH_LONG).show()
            return
        }
        scope.launch {
            val all = try {
                client.states().filter { it.supported }.sortedWith(compareBy({ it.domain }, { it.name.lowercase() }))
            } catch (e: HomeAssistant.AuthException) {
                Toast.makeText(this@MainActivity, R.string.ha_auth_failed, Toast.LENGTH_LONG).show(); return@launch
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.ha_error, prefs.haUrl), Toast.LENGTH_LONG).show(); return@launch
            }
            if (all.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.ha_entities_none, Toast.LENGTH_LONG).show(); return@launch
            }
            val chosen = prefs.haEntities.toMutableList()
            val checked = BooleanArray(all.size) { all[it].id in chosen }
            val labels = all.map { "${it.name}  ·  ${it.domain.replace('_', ' ')}" }.toTypedArray()
            dialog().setTitle(R.string.ha_entities_title)
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    val id = all[which].id
                    if (isChecked) { if (id !in chosen) chosen.add(id) } else chosen.remove(id)
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    // Keep the list's own order (by kind, then name) so the row is predictable.
                    prefs.haEntities = all.map { it.id }.filter { it in chosen }
                    refreshHa(force = true)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // ---- Mitsubishi Comfort (Kumo) ---------------------------------------------------------

    private fun refreshKumo(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastAcAt < KUMO_REFRESH_MS) return
        lastAcAt = now
        val units = kumoUnits
        scope.launch {
            val statuses = HashMap(kumoStatus)
            var missing = false
            for (u in units) {
                val st = if (!u.viaCloud() && u.address.isBlank()) null else kumoStatusOf(u)
                if (st == null) { statuses.remove(u.serial); if (!u.viaCloud()) missing = true; continue }
                statuses[u.serial] = st
                if (st.power) kumoLastMode[u.serial] = KumoClient.baseMode(st.mode)
                if (u.serial !in kumoProfiles) kumoProfileOf(u)?.let { kumoProfiles = kumoProfiles + (u.serial to it) }
            }
            kumoStatus = statuses
            renderKumo()
            // An adapter that stopped answering has usually moved to a new address: look again.
            if (missing && !kumoRelocating) relocateKumo(units)
            if (kumoCloudExpired) {
                kumoCloudExpired = false
                Toast.makeText(this@MainActivity, R.string.kumo_signin_expired, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun relocateKumo(units: List<KumoClient.Unit>, onDone: (() -> Unit)? = null) {
        kumoRelocating = true
        scope.launch {
            try {
                val local = units.filterNot { it.viaCloud() }
                val found = KumoClient.locate(local, prefer = local.map { it.address }).associateBy { it.serial }
                val located = units.map { found[it.serial] ?: it }
                if (located != units) {
                    kumoUnits = located
                    prefs.kumoUnits = located
                }
            } finally {
                kumoRelocating = false
                onDone?.invoke()
            }
        }
    }

    private fun renderKumo() {
        val u = kumoUnit ?: run { acCard.visibility = View.GONE; return }
        val st = kumoStatus[u.serial]
        acCard.visibility = View.VISIBLE
        findViewById<TextView>(R.id.acName).text = if (kumoUnits.size > 1) "${u.label}  ▸" else u.label
        val temp = findViewById<TextView>(R.id.acTemp)
        temp.text = when {
            st == null -> getString(if (!u.viaCloud() && (u.address.isBlank() || kumoRelocating)) R.string.kumo_searching else R.string.cielo_offline)
            !st.power -> getString(R.string.cielo_off)
            else -> st.setPoint()?.let { kumoTempText(it) } ?: "—"
        }
        val mode = st?.mode?.takeIf { it != "off" } ?: kumoLastMode[u.serial] ?: "cool"
        findViewById<TextView>(R.id.acMode).text = KumoClient.modeLabel(mode)
        findViewById<TextView>(R.id.acFan).text = getString(R.string.cielo_fan, st?.fanSpeed?.let { KumoClient.fanLabel(it) } ?: "—")
        val room = buildString {
            st?.roomTemp?.let { append(getString(R.string.cielo_room, kumoTempText(it))) }
            st?.humidity?.let { if (isNotEmpty()) append(" · "); append("${it.toInt()}%") }
        }
        findViewById<TextView>(R.id.acRoom).text = room
        findViewById<TextView>(R.id.acRoom).visibility = if (room.isBlank()) View.GONE else View.VISIBLE
        val hasSetPoint = st?.power == true && st.setPoint() != null
        listOf(R.id.acMinus, R.id.acPlus).forEach { findViewById<View>(it).alpha = if (hasSetPoint) 1f else 0.4f }
    }

    private fun kumoTempText(celsius: Double): String =
        if (prefs.fahrenheit) "${KumoClient.cToF(celsius)}°"
        else if (celsius % 1.0 == 0.0) "${celsius.toInt()}°" else "%.1f°".format(Locale.getDefault(), celsius)

    private fun kumoNextUnit() {
        if (kumoUnits.size < 2) return
        val i = kumoUnits.indexOfFirst { it.serial == kumoUnit?.serial }
        prefs.kumoSelected = kumoUnits[(i + 1).mod(kumoUnits.size)].serial
        renderKumo()
    }

    /** Runs one command against the selected unit, then shows its real state a moment later. */
    private fun kumoCommand(optimistic: (KumoClient.Status) -> KumoClient.Status, local: suspend (KumoClient.Unit) -> Boolean, viaCloud: suspend (KumoClient.Cloud, String) -> Boolean) {
        val u = kumoUnit ?: return
        if (kumoBusy || (!u.viaCloud() && u.address.isBlank())) return
        kumoBusy = true
        kumoStatus[u.serial]?.let { kumoStatus = kumoStatus + (u.serial to optimistic(it)); renderKumo() }
        scope.launch {
            try {
                val ok = if (!u.viaCloud()) local(u) else {
                    val c = cloud()
                    if (c == null) false else try { viaCloud(c, u.serial) } catch (e: KumoClient.AuthException) {
                        Toast.makeText(this@MainActivity, R.string.kumo_signin_expired, Toast.LENGTH_LONG).show(); true
                    }
                }
                if (!ok) Toast.makeText(this@MainActivity, R.string.kumo_error, Toast.LENGTH_SHORT).show()
            } finally {
                kumoBusy = false
                handler.removeCallbacks(acResync)
                handler.postDelayed(acResync, KUMO_RESYNC_MS)
            }
        }
    }

    private fun kumoPower() {
        val u = kumoUnit ?: return
        val st = kumoStatus[u.serial] ?: return
        val next = if (st.power) "off" else (kumoLastMode[u.serial] ?: "cool")
        kumoCommand({ it.copy(mode = next) }, { KumoClient.setMode(it, next) }) { c, s -> c.setMode(s, next) }
    }

    private fun kumoNextMode() {
        val u = kumoUnit ?: return
        val current = kumoStatus[u.serial]?.mode?.takeIf { it != "off" }?.let { KumoClient.baseMode(it) } ?: kumoLastMode[u.serial] ?: "cool"
        val known = kumoProfiles[u.serial]?.modes ?: listOf("cool", "heat")
        // A unit running auto supports it even when its profile doesn't say so.
        val modes = if (current == "auto" && "auto" !in known) known + "auto" else known
        val next = modes[(modes.indexOf(current) + 1).mod(modes.size)]
        kumoLastMode[u.serial] = next
        kumoCommand({ it.copy(mode = next) }, { KumoClient.setMode(it, next) }) { c, s -> c.setMode(s, next) }
    }

    private fun kumoNextFan() {
        val u = kumoUnit ?: return
        val speeds = kumoProfiles[u.serial]?.fanSpeeds ?: listOf("superQuiet", "quiet", "low", "powerful", "superPowerful")
        val current = kumoStatus[u.serial]?.fanSpeed.orEmpty()
        val next = speeds[(speeds.indexOf(current) + 1).mod(speeds.size)]
        kumoCommand({ it.copy(fanSpeed = next) }, { KumoClient.setFan(it, next) }) { c, s -> c.setFan(s, next) }
    }

    private fun kumoNudge(delta: Int) {
        val u = kumoUnit ?: return
        val st = kumoStatus[u.serial] ?: return
        if (!st.power) return
        val current = st.setPoint() ?: return
        val p = kumoProfiles[u.serial]
        val (lo, hi) = if (st.heating) (p?.minHeat ?: 10.0) to (p?.maxHeat ?: 31.0) else (p?.minCool ?: 16.0) to (p?.maxCool ?: 31.0)
        val next = (if (prefs.fahrenheit) KumoClient.fToC((KumoClient.cToF(current) + delta).toDouble()) else current + delta * 0.5)
            .coerceIn(lo, hi)
        if (next == current) return
        kumoCommand({ if (st.heating) it.copy(spHeat = next) else it.copy(spCool = next) },
            { KumoClient.setSetPoint(it, st.mode, next) }) { c, s -> c.setSetPoint(s, st.mode, next) }
    }

    private fun showKumoSettings() {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += getString(R.string.kumo_sign_in) to {
            promptText(getString(R.string.kumo_email_prompt), prefs.kumoEmail, "name@example.com") { email ->
                if (email.isBlank()) return@promptText
                promptSecret(getString(R.string.kumo_password_prompt)) { pw -> if (pw.isNotEmpty()) setupKumo(email, pw) }
            }
        }
        if (kumoUnits.isNotEmpty()) {
            items += getString(R.string.kumo_find_again) to {
                Toast.makeText(this, R.string.kumo_searching_long, Toast.LENGTH_LONG).show()
                relocateKumo(kumoUnits.map { it.copy(address = "") }) { refreshKumo(force = true); kumoReport() }
            }
            items += getString(R.string.kumo_forget) to {
                kumoUnits = emptyList(); prefs.kumoUnits = emptyList(); kumoStatus = emptyMap(); kumoProfiles = emptyMap()
                prefs.kumoRefresh = ""; kumoCloud = null
                acCard.visibility = View.GONE
                refreshAc(force = true)
            }
        }
        val title = if (kumoUnits.isEmpty()) getString(R.string.kumo_title)
        else getString(R.string.kumo_title_units, kumoUnits.joinToString(", ") { it.label })
        dialog().setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    /** Signs in once, fetches each unit's local key, finds the units on the network, forgets the password. */
    private fun setupKumo(email: String, password: String) {
        prefs.kumoEmail = email
        // A dialog rather than a toast: setup takes up to a minute and its outcome must not be missed.
        val progress = dialog().setTitle(R.string.kumo_title).setMessage(R.string.kumo_signing_in).setCancelable(false).show()
        scope.launch {
            try {
                android.util.Log.i("Kumo", "setup: signing in")
                val account = KumoClient.fetchUnits(email, password)
                val fetched = account.units
                val localCount = fetched.count { !it.viaCloud() }
                android.util.Log.i("Kumo", "setup: ${fetched.size} unit(s) on the account, $localCount with local keys")
                if (localCount < fetched.size && account.refreshToken.isBlank()) {
                    dialog().setTitle(R.string.kumo_title).setMessage(R.string.kumo_no_keys)
                        .setPositiveButton(android.R.string.ok, null).show()
                    return@launch
                }
                prefs.kumoRefresh = account.refreshToken
                kumoCloud = null
                progress.setMessage(getString(R.string.kumo_searching_long))
                val found = KumoClient.locate(fetched.filterNot { it.viaCloud() }).associateBy { it.serial }
                val located = fetched.map { found[it.serial] ?: it }
                android.util.Log.i("Kumo", "setup: located ${located.count { it.address.isNotBlank() }}/${located.size}")
                kumoUnits = located
                prefs.kumoUnits = located
                if (located.none { it.serial == prefs.kumoSelected }) prefs.kumoSelected = located.first().serial
                kumoStatus = emptyMap(); kumoProfiles = emptyMap()
                refreshKumo(force = true)
                kumoReport()
            } catch (e: KumoClient.AuthException) {
                android.util.Log.w("Kumo", "setup: sign-in rejected")
                dialog().setTitle(R.string.kumo_title).setMessage(getString(R.string.kumo_rejected, email))
                    .setPositiveButton(android.R.string.ok, null).show()
            } catch (e: Exception) {
                android.util.Log.w("Kumo", "setup failed", e)
                dialog().setTitle(R.string.kumo_title).setMessage(getString(R.string.kumo_setup_failed, e.message ?: e.javaClass.simpleName))
                    .setPositiveButton(android.R.string.ok, null).show()
            } finally {
                runCatching { progress.dismiss() }
            }
        }
    }

    private fun kumoReport(skipped: Int = 0) {
        val lines = kumoUnits.joinToString("\n") { u ->
            "• ${u.label}: " + when {
                u.viaCloud() -> getString(R.string.kumo_via_cloud)
                u.address.isNotBlank() -> getString(R.string.kumo_found_at, u.address.substringBefore(':'))
                else -> getString(R.string.kumo_not_found)
            }
        }
        val extra = if (skipped > 0) "\n\n" + getString(R.string.kumo_skipped, skipped) else ""
        dialog().setTitle(R.string.kumo_title).setMessage(lines + extra).setPositiveButton(android.R.string.ok, null).show()
    }

    /** Like promptText, but the field hides what is typed and starts empty. */
    private fun promptSecret(title: String, onDone: (String) -> Unit) {
        val input = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrapper = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        dialog().setTitle(title).setView(wrapper)
            .setPositiveButton(android.R.string.ok) { _, _ -> onDone(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        input.requestFocus()
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
            if (kumoActive) { kumoNextUnit(); return@setOnClickListener }
            val list = acDevices
            if (list.size < 2) return@setOnClickListener
            val current = acDevice
            val next = list[(list.indexOfFirst { it.mac == current?.mac } + 1).mod(list.size)]
            acDevice = next
            prefs.cieloDevice = next.mac
            renderAc()
        }
        findViewById<View>(R.id.acPower).setOnClickListener {
            if (kumoActive) { kumoPower(); return@setOnClickListener }
            val d = acDevice ?: return@setOnClickListener
            sendAc("power", mapOf("power" to if (d.power) "off" else "on"))
        }
        findViewById<View>(R.id.acMinus).setOnClickListener { if (kumoActive) kumoNudge(-1) else nudgeTemp(-1) }
        findViewById<View>(R.id.acPlus).setOnClickListener { if (kumoActive) kumoNudge(+1) else nudgeTemp(+1) }
        findViewById<View>(R.id.acMode).setOnClickListener {
            if (kumoActive) { kumoNextMode(); return@setOnClickListener }
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
            if (kumoActive) { kumoNextFan(); return@setOnClickListener }
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
        if (kumoActive) { refreshKumo(force); return }
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
        const val KUMO_REFRESH_MS = 30_000L
        const val KUMO_RESYNC_MS = 2_500L
        const val MAX_EVENT_CARDS = 40
        const val MAX_NEWS_CARDS = 30
        const val NEWS_REFRESH_MS = 30 * 60_000L
        const val TONIGHT_RETRY_MS = 30 * 60_000L
        const val HA_REFRESH_MS = 60_000L
        const val HA_TICK_MS = 30_000L
        const val HA_RESYNC_MS = 1_500L
        const val SLIDE_INTERVAL_MS = 10 * 60_000L
        const val WALLPAPER_LIST_MAX_AGE_MS = 6 * 60 * 60_000L
        const val WALLPAPER_RETRY_MS = 3 * 60_000L
        const val CROSSFADE_MS = 1500
        const val SETUP_PORT = 8765
        const val THEME_PHOTO = "photo"
        const val THEME_DARK = "dark"
    }
}

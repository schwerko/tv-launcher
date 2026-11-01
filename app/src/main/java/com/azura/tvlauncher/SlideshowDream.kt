package com.azura.tvlauncher

import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Screensaver: full-screen nature photos from around the world with a slow zoom and caption. */
class SlideshowDream : DreamService() {

    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var front: ImageView
    private lateinit var back: ImageView
    private lateinit var title: TextView
    private lateinit var place: TextView
    private lateinit var clock: TextView

    private var photos: List<WorldPhotos.Photo> = emptyList()
    private var index = -1
    private var showingFront = true

    private val next = object : Runnable {
        override fun run() {
            showNext()
            handler.postDelayed(this, SLIDE_MS)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            val pattern = if (android.text.format.DateFormat.is24HourFormat(this@SlideshowDream)) "HH:mm" else "h:mm a"
            clock.text = LocalTime.now().format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
            handler.postDelayed(this, 1000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        setContentView(R.layout.dream)
        front = findViewById(R.id.imageFront)
        back = findViewById(R.id.imageBack)
        title = findViewById(R.id.captionTitle)
        place = findViewById(R.id.captionPlace)
        clock = findViewById(R.id.dreamClock)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        handler.post(tick)
        scope.launch {
            // Same source as the home background; a plain-dark home still gets Commons photos.
            val prefs = Prefs(this@SlideshowDream)
            val source = prefs.wallpaper.ifBlank { WallpaperService.COMMONS }
            photos = WorldPhotos.forSource(this@SlideshowDream, source, prefs.unsplashKey, MAX_PHOTOS)
            if (photos.isEmpty()) {
                place.text = getString(R.string.screensaver_offline)
                return@launch
            }
            WallpaperService.prune(this@SlideshowDream, photos.map { it.url }, CACHE)
            showNext()
            handler.postDelayed(next, SLIDE_MS)
        }
    }

    override fun onDreamingStopped() {
        handler.removeCallbacks(next)
        handler.removeCallbacks(tick)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }

    private fun showNext() {
        if (photos.isEmpty()) return
        index = (index + 1) % photos.size
        val photo = photos[index]
        val dm = resources.displayMetrics
        scope.launch {
            val bitmap = WallpaperService.load(this@SlideshowDream, photo, dm.widthPixels, dm.heightPixels, CACHE)
                ?: run { showNext(); return@launch }   // skip anything that fails to load
            val incoming = if (showingFront) back else front
            val outgoing = if (showingFront) front else back
            incoming.setImageBitmap(bitmap)
            incoming.scaleX = 1f
            incoming.scaleY = 1f
            incoming.alpha = 0f
            incoming.visibility = View.VISIBLE
            incoming.animate().alpha(1f).setDuration(FADE_MS).start()
            incoming.animate().scaleX(ZOOM).scaleY(ZOOM).setDuration(SLIDE_MS + FADE_MS).start()
            outgoing.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                outgoing.visibility = View.INVISIBLE
                outgoing.setImageDrawable(null)
            }.start()
            showingFront = !showingFront

            title.text = photo.title
            title.visibility = if (photo.title.isBlank()) View.GONE else View.VISIBLE
            val line = listOf(photo.place, photo.credit).filter { it.isNotBlank() }.joinToString(" · ")
            place.text = line
            place.visibility = if (line.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private companion object {
        const val CACHE = "screensaver"
        const val MAX_PHOTOS = 60
        const val SLIDE_MS = 20_000L
        const val FADE_MS = 1_500L
        const val ZOOM = 1.08f
    }
}

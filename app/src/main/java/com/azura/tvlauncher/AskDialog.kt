package com.azura.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URLEncoder

/** The "Ask" panel: type or speak a question, read Claude's answer, open any titles it names. */
class AskDialog(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val client: Assistant,
    private val onSpeak: () -> Unit,
) {
    private val dialog = Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar)
    private val input: EditText
    private val status: TextView
    private val answer: TextView
    private val scroll: ScrollView
    private val actions: LinearLayout
    private var busy = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    init {
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_ask, null)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0xF0171A20.toInt()))
            setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.72).toInt(),
                (activity.resources.displayMetrics.heightPixels * 0.82).toInt(),
            )
            setGravity(Gravity.CENTER)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        input = root.findViewById(R.id.askInput)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        status = root.findViewById(R.id.askStatus)
        answer = root.findViewById(R.id.askAnswer)
        scroll = root.findViewById(R.id.askScroll)
        actions = root.findViewById(R.id.askActions)

        root.findViewById<View>(R.id.askSend).setOnClickListener { submit() }
        root.findViewById<View>(R.id.askMic).setOnClickListener { onSpeak() }
        // The remote's microphone button reaches the dialog as ASSIST/SEARCH: treat it like Speak.
        dialog.setOnKeyListener { _, keyCode, event ->
            val isMicKey = keyCode == KeyEvent.KEYCODE_ASSIST || keyCode == KeyEvent.KEYCODE_SEARCH ||
                keyCode == KeyEvent.KEYCODE_VOICE_ASSIST
            android.util.Log.d("Ask", "key $keyCode action=${event.action}")
            if (isMicKey && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                onSpeak()
                true
            } else false
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
        }
        answer.text = activity.getString(if (client.hasLiveWeb) R.string.ask_welcome else R.string.ask_welcome_offline)
    }

    fun show() {
        dialog.show()
        dialog.findViewById<View>(R.id.askMic)?.requestFocus()
    }

    fun isShowing() = dialog.isShowing

    fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    /** True when on-device speech recognition exists (the launcher then listens itself). */
    fun canListenLocally(): Boolean = SpeechRecognizer.isRecognitionAvailable(activity)

    /** Listens through the system recogniser; the remote's mic streams while its button is held. */
    fun startListening() {
        if (listening) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(activity).also { rec ->
            recognizer = rec
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { showStatus(activity.getString(R.string.ask_listening)) }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { showStatus(activity.getString(R.string.ask_heard)) }
                override fun onPartialResults(partialResults: Bundle?) {
                    val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!t.isNullOrBlank()) input.setText(t)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onResults(results: Bundle?) {
                    listening = false
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    android.util.Log.d("Ask", "speech results=$t")
                    if (t.isNullOrBlank()) showStatus(activity.getString(R.string.ask_no_speech)) else askSpoken(t)
                }
                override fun onError(error: Int) {
                    listening = false
                    android.util.Log.w("Ask", "speech error $error")
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.ask_no_speech
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.ask_mic_permission
                        else -> R.string.ask_speech_error
                    }
                    showStatus(activity.getString(msg))
                }
            })
        }
        listening = true
        showStatus(activity.getString(R.string.ask_listening))
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
        })
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
        listening = false
    }

    /** Called with the recognised speech; asks straight away. */
    fun askSpoken(text: String) {
        input.setText(text)
        submit()
    }

    private fun submit() {
        val q = input.text.toString().trim()
        if (q.isBlank() || busy) return
        busy = true
        input.setText("")
        actions.removeAllViews()
        actions.visibility = View.GONE
        status.text = activity.getString(R.string.ask_thinking, q)
        status.visibility = View.VISIBLE
        scope.launch {
            try {
                val a = client.ask(q)
                answer.text = when {
                    a.refused -> activity.getString(R.string.ask_refused)
                    a.text.isBlank() -> activity.getString(R.string.ask_empty)
                    else -> a.text
                }
                scroll.scrollTo(0, 0)
                showTitles(a.titles)
                status.visibility = View.GONE
                input.requestFocus()
            } catch (e: Assistant.AuthException) {
                status.text = activity.getString(R.string.ask_auth_failed, client.name)
            } catch (e: Exception) {
                android.util.Log.w("Claude", "ask failed", e)
                status.text = activity.getString(R.string.ask_failed, client.name, e.message ?: "")
            } finally {
                busy = false
            }
        }
    }

    private fun showTitles(titles: List<String>) {
        if (titles.isEmpty()) return
        val inflater = LayoutInflater.from(activity)
        for (title in titles) {
            val b = inflater.inflate(R.layout.ask_action_button, actions, false) as TextView
            b.text = activity.getString(R.string.ask_open_title, title)
            b.setOnClickListener { openTitle(title) }
            actions.addView(b)
        }
        actions.visibility = View.VISIBLE
    }

    /**
     * Streaming apps, shown only if installed. Disney+, Prime Video and YouTube accept a search
     * query in a link; Netflix, HBO Max and Apple TV ignore it, so for those Claude looks up the
     * title's own page and the launcher opens that instead.
     */
    /**
     * [packages] lists the TV edition first, then the phone/tablet edition; whichever is
     * installed is used (the launcher also runs on phones and tablets).
     */
    private data class Service(
        val label: String,
        val packages: List<String>,
        val search: (String) -> String,
        val urlForm: String? = null,
        val hostPattern: Regex? = null,
        val packageName: String = packages.first(),
    )

    private val services = listOf(
        Service("Netflix", listOf("com.netflix.ninja", "com.netflix.mediaclient"), { "https://www.netflix.com/search?q=$it" },
            "www.netflix.com/title/<id>", Regex("""www\.netflix\.com/title/\d+""")),
        Service("Disney+", listOf("com.disney.disneyplus"), { "https://www.disneyplus.com/search?q=$it" }),
        Service("Prime Video", listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"), { "https://app.primevideo.com/search?phrase=$it" }),
        Service("HBO Max", listOf("com.wbd.stream"), { "https://play.max.com/search?q=$it" },
            "play.max.com/show/<id> or play.max.com/movie/<id>", Regex("""play\.(?:hbo)?max\.com/(?:show|movie)/[A-Za-z0-9-]+""")),
        Service("Apple TV", listOf("com.apple.atve.androidtv.appletv", "com.apple.atve.android.appletv"), { "https://tv.apple.com/search?term=$it" },
            "tv.apple.com/us/show/<slug>/<id> or tv.apple.com/us/movie/<slug>/<id>", Regex("""tv\.apple\.com/\S+?umc\.cmc\.[a-z0-9]+""")),
        Service("YouTube", listOf("com.google.android.youtube.tv", "com.google.android.youtube"), { "https://www.youtube.com/results?search_query=$it" }),
    )

    /** Each service that is installed, resolved to the edition (TV or mobile) actually present. */
    private fun installed(): List<Service> = services.mapNotNull { svc ->
        val present = svc.packages.firstOrNull { pkg ->
            runCatching { activity.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        } ?: return@mapNotNull null
        svc.copy(packageName = present)
    }

    private fun openTitle(title: String) {
        val available = installed()
        if (available.isEmpty()) {
            searchYouTube(title)
            return
        }
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(activity.getString(R.string.ask_search_in, title))
            .setItems(available.map { it.label }.toTypedArray()) { _, which -> openIn(available[which], title) }
            .show()
    }

    private fun openIn(svc: Service, title: String) {
        if (svc.urlForm == null || svc.hostPattern == null) {
            launch(svc, svc.search(URLEncoder.encode(title, "UTF-8")), title)
            return
        }
        showStatus(activity.getString(R.string.ask_finding, title, svc.label))
        scope.launch {
            val found = runCatching { client.resolveTitleUrl(svc.label, svc.urlForm, svc.hostPattern, title) }
                .onFailure { android.util.Log.w("Claude", "resolve failed", it) }.getOrNull()
            status.visibility = View.GONE
            if (found != null) {
                launch(svc, "https://$found", title)
            } else {
                Toast.makeText(activity, activity.getString(R.string.ask_not_on_service, title, svc.label), Toast.LENGTH_LONG).show()
                launch(svc, svc.search(URLEncoder.encode(title, "UTF-8")), title)
            }
        }
    }

    private fun launch(svc: Service, url: String, title: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(svc.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            searchYouTube(title)
        }
    }

    private fun searchYouTube(query: String) {
        val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.ask_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val SPEECH_REQUEST = 4711

        /** Google TV's own voice input first (avoids the "complete action using" chooser), else any. */
        fun speechIntents(): List<Intent> = listOf("com.google.android.katniss", null).map { pkg ->
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask anything")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                if (pkg != null) setPackage(pkg)
            }
        }
    }
}

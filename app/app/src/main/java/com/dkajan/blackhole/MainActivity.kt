package com.dkajan.blackhole

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Patterns
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnticipateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.jetradarmobile.snowfall.SnowfallView
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private val viewModel: DownloadViewModel by viewModels()
    private val handler = Handler(Looper.getMainLooper())
    private var infoRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val snowView = findViewById<SnowfallView>(R.id.snowfallView)

        // Získame dnešný dátum
        val today = Calendar.getInstance()

        val month = today.get(Calendar.MONTH)
        val day = today.get(Calendar.DAY_OF_MONTH)

        // Kontrola, či sme v období 15.11. - 6.1.
        val showSnow = (month == Calendar.NOVEMBER && day >= 15) ||
                month == Calendar.DECEMBER ||
                (month == Calendar.JANUARY && day <= 6)

        snowView.visibility = if (showSnow) View.VISIBLE else View.GONE


        val isDarkTheme = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isDarkTheme) {
                window.decorView.systemUiVisibility = 0
            } else {
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!isDarkTheme) {
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }

        setGitHubLink()

        val downloadButton: Button = findViewById(R.id.downloadButton)
        val progressBar: ProgressBar = findViewById(R.id.progressBar)
        val percentText: TextView = findViewById(R.id.percentText)
        val statusText: TextView = findViewById(R.id.statusText)
        val downloadInfoText: TextView = findViewById(R.id.downloadInfoText)
        val loadingGif: ImageView = findViewById(R.id.loadingGif)
        val informationIcon: ImageView = findViewById(R.id.information_icon)
        val apiInfoContainer: LinearLayout = findViewById(R.id.api_info_container)

        Glide.with(this)
            .asGif()
            .load(R.drawable.blackhole)
            .into(loadingGif)

        progressBar.visibility = View.INVISIBLE
        percentText.visibility = View.INVISIBLE
        downloadInfoText.visibility = View.INVISIBLE
        loadingGif.visibility = View.INVISIBLE

        viewModel.downloadStatus.observe(this, Observer { status ->
            statusText.text = status
        })

        viewModel.downloadProgress.observe(this, Observer { progress ->
            if (progress >= 0) {
                progressBar.isIndeterminate = false
                progressBar.progress = progress
                percentText.text = "$progress%"
            } else {
                progressBar.isIndeterminate = true
                percentText.text = ""
            }
        })

        viewModel.downloadInfo.observe(this, Observer { info ->
            downloadInfoText.text = info
        })

        viewModel.isDownloading.observe(this, Observer { isDownloading ->
            downloadButton.isEnabled = !isDownloading

            val viewsToAnimateIn = listOf(loadingGif, progressBar, percentText, downloadInfoText)
            if (isDownloading) {
                viewsToAnimateIn.forEach { view ->
                    view.apply {
                        visibility = View.VISIBLE
                        scaleX = 0f
                        scaleY = 0f
                        alpha = 0f
                        animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(500)
                            .start()
                    }
                }
            } else {
                viewsToAnimateIn.forEach { view ->
                    view.animate()
                        .scaleX(0f)
                        .scaleY(0f)
                        .alpha(0f)
                        .setDuration(500)
                        .withEndAction {
                            view.visibility = View.INVISIBLE
                        }
                        .start()
                }
            }
        })

        downloadButton.setOnClickListener {
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboardManager.hasPrimaryClip() &&
                clipboardManager.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
            ) {
                val clipText = clipboardManager.primaryClip?.getItemAt(0)?.text.toString()

                if (clipText.isNotEmpty()) {
                    val matcher = Patterns.WEB_URL.matcher(clipText)

                    if (matcher.find()) {
                        val foundUrl = matcher.group()

                        lifecycleScope.launch {
                            try {
                                val finalUrl = if (foundUrl.contains("pinterest.com/i/")) {
                                    viewModel.resolveRedirect(foundUrl)
                                } else {
                                    foundUrl
                                }
                                viewModel.downloadVideo(finalUrl)
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Error resolving redirect: ${e.message}")
                                Toast.makeText(this@MainActivity, "Failed to resolve URL.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        Toast.makeText(this, "No valid URL found in clipboard.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Clipboard is empty.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Clipboard does not contain text.", Toast.LENGTH_SHORT).show()
            }
        }


        informationIcon.setOnClickListener {
            infoRunnable?.let { handler.removeCallbacks(it) }

            informationIcon.animate().cancel()
            apiInfoContainer.animate().cancel()

            informationIcon.alpha = 1f
            informationIcon.visibility = View.VISIBLE
            apiInfoContainer.alpha = 0f
            apiInfoContainer.visibility = View.GONE

            informationIcon.animate()
                .alpha(0f)
                .setDuration(300)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        informationIcon.visibility = View.GONE
                        apiInfoContainer.apply {
                            alpha = 0f
                            visibility = View.VISIBLE
                            animate()
                                .alpha(1f)
                                .setDuration(300)
                                .setListener(null)
                                .start()
                        }

                        infoRunnable = Runnable {
                            apiInfoContainer.animate()
                                .alpha(0f)
                                .setDuration(300)
                                .setListener(object : AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(animation: Animator) {
                                        apiInfoContainer.visibility = View.GONE
                                        // Zobrazenie ikonky s animáciou
                                        informationIcon.apply {
                                            alpha = 0f
                                            visibility = View.VISIBLE
                                            animate()
                                                .alpha(1f)
                                                .setDuration(300)
                                                .setListener(null)
                                                .start()
                                        }
                                    }
                                }).start()
                        }

                        handler.postDelayed(infoRunnable!!, 12000)
                    }
                }).start()
        }

        viewModel.isButtonEnabled.observe(this) { enabled ->
            downloadButton.isEnabled = enabled
        }


        handleIncomingShare(intent)


        viewModel.downloadedVideoUri.observe(this) { uri ->
            uri?.let { videoUri ->
                showVideoSnackbar(videoUri)
            }
        }

    }


    @SuppressLint("ResourceType")
    private fun showVideoSnackbar(videoUri: Uri) {
        val rootView = findViewById<View>(android.R.id.content)

        val snackbar = Snackbar.make(rootView, "Tap to view downloaded video.", Snackbar.LENGTH_INDEFINITE)

        val isDarkTheme = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        val backgroundColor = if (!isDarkTheme) {
            ContextCompat.getColor(this, android.R.color.system_accent1_500)
        } else {
            ContextCompat.getColor(this, android.R.color.system_accent1_200)
        }

        val actionTextColor = if (!isDarkTheme) {
            ContextCompat.getColor(this, android.R.color.system_accent1_200)
        } else {
            ContextCompat.getColor(this, android.R.color.system_accent1_500)
        }

        val snackbarView = snackbar.view

        val params = snackbarView.layoutParams as FrameLayout.LayoutParams
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.bottomMargin = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            10f,
            resources.displayMetrics
        ).toInt()
        snackbarView.layoutParams = params

        val backgroundDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 40f
        }

        snackbarView.background = backgroundDrawable
        snackbar.setBackgroundTint(backgroundColor)
        snackbar.setActionTextColor(actionTextColor)

        val closeAnimation = {
            snackbarView.animate()
                .alpha(0f)
                .translationY(100f) // ide dole
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(300)
                .setInterpolator(AnticipateInterpolator()) // opačný puf
                .withEndAction { (snackbarView.parent as? ViewGroup)?.removeView(snackbarView) }
                .start()
        }

        snackbar.setAction("Open") {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(videoUri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
            closeAnimation()
        }

        snackbarView.alpha = 0f
        snackbarView.translationY = 100f // začína trochu nižšie
        snackbarView.scaleX = 0.8f
        snackbarView.scaleY = 0.8f
        snackbar.show()
        snackbarView.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator())
            .start()

        snackbarView.postDelayed({
            closeAnimation()
        }, 5000)
    }

    private fun handleIncomingShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                val matcher = Patterns.WEB_URL.matcher(sharedText)
                if (matcher.find()) {
                    val foundUrl = matcher.group()
                    lifecycleScope.launch {
                        try {
                            val finalUrl = if (foundUrl.contains("pinterest.com/i/")) {
                                viewModel.resolveRedirect(foundUrl)
                            } else foundUrl

                            viewModel.downloadVideo(finalUrl)
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error resolving redirect: ${e.message}")
                            Toast.makeText(this@MainActivity, "Failed to resolve URL.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "Shared text does not contain a valid URL.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Shared text is empty.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncomingShare(intent)
    }

    private fun setGitHubLink() {
        // Nájdi ikonku
        val githubIcon = findViewById<ImageView>(R.id.github_icon)

        // Nájdi text
        val githubText = findViewById<TextView>(R.id.github_text)

        // URL adresa GitHub repozitára
        val githubUrl = "https://github.com/dkajan19/blackhole/" // SEM VLOŽ SVOJ ODKAZ NA REPOZITÁR

        // Nastav OnClickListener pre ikonku
        githubIcon.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
            startActivity(intent)
        }

        // Nastav OnClickListener pre text
        githubText.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
            startActivity(intent)
        }
    }
}
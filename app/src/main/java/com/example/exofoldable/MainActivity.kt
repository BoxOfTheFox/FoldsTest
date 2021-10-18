package com.example.exofoldable

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.*
import androidx.window.layout.WindowInfoRepository.Companion.windowInfoRepository
import com.example.exofoldable.databinding.ActivityMainBinding
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.ui.PlayerControlView
import com.google.android.exoplayer2.util.MimeTypes
import com.google.android.exoplayer2.util.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val TAG = "PlayerActivity"

class MainActivity : AppCompatActivity() {
    private val viewBinding by lazy(LazyThreadSafetyMode.NONE) {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val playbackStateListener: Player.EventListener = playbackStateListener()
    private var player: SimpleExoPlayer? = null

    private var playWhenReady = true
    private var currentWindow = 0
    private var playbackPosition = 0L

    private lateinit var windowInfoRepository: WindowInfoRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        windowInfoRepository = windowInfoRepository()

        obtainWindowMetrics()
        onWindowLayoutInfoChange(windowInfoRepository)
    }

    private fun obtainWindowMetrics() {
        val wmc = WindowMetricsCalculator.getOrCreate()
        val currentWM = wmc.computeCurrentWindowMetrics(this).bounds.flattenToString()
        val maximumWM = wmc.computeMaximumWindowMetrics(this).bounds.flattenToString()
        viewBinding.windowMetrics.text =
            "CurrentWindowMetrics: ${currentWM}\nMaximumWindowMetrics: ${maximumWM}"
    }

    private fun onWindowLayoutInfoChange(windowInfoRepository: WindowInfoRepository) {
        lifecycleScope.launch(Dispatchers.Main) {
            // The block passed to repeatOnLifecycle is executed when the lifecycle
            // is at least STARTED and is cancelled when the lifecycle is STOPPED.
            // It automatically restarts the block when the lifecycle is STARTED again.
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Safely collects from windowInfoRepository when the lifecycle is STARTED
                // and stops collection when the lifecycle is STOPPED.
                windowInfoRepository.windowLayoutInfo
                    .collect { newLayoutInfo ->
                        updateUI(newLayoutInfo)
                        onLayoutInfoChanged(newLayoutInfo)
                    }
            }
        }
    }

    private fun onLayoutInfoChanged(newLayoutInfo: WindowLayoutInfo) {
        if (newLayoutInfo.displayFeatures.isEmpty()) {
            // The display doesn't have a display feature, we may be on a secondary,
            // non foldable-screen, or on the main foldable screen but in a split-view.
            centerPlayer()
        } else {
            newLayoutInfo.displayFeatures.filterIsInstance(FoldingFeature::class.java)
                .firstOrNull { feature -> isTableTopMode(feature) }
                ?.let { foldingFeature ->
                    val fold = foldPosition(viewBinding.root, foldingFeature)
                    foldPlayer(fold)
                } ?: run {
                centerPlayer()
            }
        }
    }


    private fun isTableTopMode(foldFeature: FoldingFeature) =
        foldFeature.state == FoldingFeature.State.HALF_OPENED &&
                foldFeature.orientation == FoldingFeature.Orientation.HORIZONTAL

    private fun centerPlayer() {
        ConstraintLayout.getSharedValues().fireNewValue(R.id.fold, 0)
        viewBinding.videoView.useController = true // use embedded controls
    }

    private fun foldPlayer(fold: Int) {
        ConstraintLayout.getSharedValues().fireNewValue(R.id.fold, fold)
        viewBinding.videoView.useController = false // use custom controls
    }

    /**
     * Returns the position of the fold relative to the view
     */
    private fun foldPosition(view: View, foldingFeature: FoldingFeature): Int {
        val splitRect = getFeatureBoundsInWindow(foldingFeature, view)
        splitRect?.let {
            return view.height.minus(splitRect.top)
        }

        return 0
    }

    /**
     * Get the bounds of the display feature translated to the View's coordinate space and current
     * position in the window. This will also include view padding in the calculations.
     */
    private fun getFeatureBoundsInWindow(
        displayFeature: DisplayFeature,
        view: View,
        includePadding: Boolean = true
    ): Rect? {
        // The location of the view in window to be in the same coordinate space as the feature.
        val viewLocationInWindow = IntArray(2)
        view.getLocationInWindow(viewLocationInWindow)

        // Intersect the feature rectangle in window with view rectangle to clip the bounds.
        val viewRect = Rect(
            viewLocationInWindow[0], viewLocationInWindow[1],
            viewLocationInWindow[0] + view.width, viewLocationInWindow[1] + view.height
        )

        // Include padding if needed
        if (includePadding) {
            viewRect.left += view.paddingLeft
            viewRect.top += view.paddingTop
            viewRect.right -= view.paddingRight
            viewRect.bottom -= view.paddingBottom
        }

        val featureRectInView = Rect(displayFeature.bounds)
        val intersects = featureRectInView.intersect(viewRect)

        // Checks to see if the display feature overlaps with our view at all
        if ((featureRectInView.width() == 0 && featureRectInView.height() == 0) ||
            !intersects
        ) {
            return null
        }

        // Offset the feature coordinates to view coordinate space start point
        featureRectInView.offset(-viewLocationInWindow[0], -viewLocationInWindow[1])

        return featureRectInView
    }

    private fun updateUI(newLayoutInfo: WindowLayoutInfo) {
        viewBinding.layoutChange.text = newLayoutInfo.toString()
        if (newLayoutInfo.displayFeatures.isNotEmpty()) {
            viewBinding.configurationChanged.text = "Spanned across displays"
//            alignViewToFoldingFeatureBounds(newLayoutInfo)
        } else {
            viewBinding.configurationChanged.text = "One logic/physical display - unspanned"
        }
    }

//    private fun alignViewToFoldingFeatureBounds(newLayoutInfo: WindowLayoutInfo) {
//        val constraintLayout = viewBinding.constraintLayout
//        val set = ConstraintSet()
//        set.clone(constraintLayout)
//
//        //We get the folding feature bounds.
//        val foldingFeature = newLayoutInfo.displayFeatures[0] as FoldingFeature
//        val rect = foldingFeature.bounds
//
//        //Some devices have a 0px width folding feature. We set a minimum of 1px so we
//        //can show the view that mirrors the folding feature in the UI and use it as reference.
//        val horizontalFoldingFeatureHeight =
//            if (rect.bottom - rect.top > 0) rect.bottom - rect.top
//            else 1
//        val verticalFoldingFeatureWidth =
//            if (rect.right - rect.left > 0) rect.right - rect.left
//            else 1
//
//        //Sets the view to match the height and width of the folding feature
//        set.constrainHeight(
//            R.id.folding_feature,
//            horizontalFoldingFeatureHeight
//        )
//        set.constrainWidth(
//            R.id.folding_feature,
//            verticalFoldingFeatureWidth
//        )
//
//        set.connect(
//            R.id.folding_feature, ConstraintSet.START,
//            ConstraintSet.PARENT_ID, ConstraintSet.START, 0
//        )
//        set.connect(
//            R.id.folding_feature, ConstraintSet.TOP,
//            ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0
//        )
//
//        if (foldingFeature.orientation == FoldingFeature.Orientation.VERTICAL) {
//            set.setMargin(R.id.folding_feature, ConstraintSet.START, rect.left)
//            set.connect(
//                R.id.layout_change, ConstraintSet.END,
//                R.id.folding_feature, ConstraintSet.START, 0
//            )
//        } else {
//            //FoldingFeature is Horizontal
//            val statusBarHeight = calculateStatusBarHeight()
//            val toolBarHeight = calculateToolbarHeight()
//            set.setMargin(
//                R.id.folding_feature, ConstraintSet.TOP,
//                rect.top - statusBarHeight - toolBarHeight
//            )
//            set.connect(
//                R.id.layout_change, ConstraintSet.TOP,
//                R.id.folding_feature, ConstraintSet.BOTTOM, 0
//            )
//        }
//
//        //Set the view to visible and apply constraints
//        set.setVisibility(R.id.folding_feature, View.VISIBLE)
//        set.applyTo(constraintLayout)
//    }

    private fun calculateToolbarHeight(): Int {
        val typedValue = TypedValue()
        return if (theme.resolveAttribute(android.R.attr.actionBarSize, typedValue, true)) {
            TypedValue.complexToDimensionPixelSize(typedValue.data, resources.displayMetrics)
        } else {
            0
        }
    }

    private fun calculateStatusBarHeight(): Int {
        val rect = Rect()
        window.decorView.getWindowVisibleDisplayFrame(rect)
        return rect.top
    }

    public override fun onStart() {
        super.onStart()
        if (Util.SDK_INT > 23) {
            initializePlayer()
        }
    }

    public override fun onResume() {
        super.onResume()
        hideSystemUi()
        if (Util.SDK_INT <= 23 || player == null) {
            initializePlayer()
        }
    }

    public override fun onPause() {
        super.onPause()
        if (Util.SDK_INT <= 23) {
            releasePlayer()
        }
    }

    public override fun onStop() {
        super.onStop()
        if (Util.SDK_INT > 23) {
            releasePlayer()
        }
    }

    private fun initializePlayer() {
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setMaxVideoSizeSd())
        }
        player = SimpleExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .build()
            .also { exoPlayer ->
                viewBinding.videoView.player = exoPlayer

                val mediaItem = MediaItem.Builder()
                    .setUri(getString(R.string.media_url_dash))
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                    .build()
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.playWhenReady = playWhenReady
                exoPlayer.seekTo(currentWindow, playbackPosition)
                exoPlayer.addListener(playbackStateListener)
                exoPlayer.prepare()
            }

        viewBinding.playerControlView.player = player
    }

    private fun releasePlayer() {
        player?.run {
            playbackPosition = this.currentPosition
            currentWindow = this.currentWindowIndex
            playWhenReady = this.playWhenReady
            removeListener(playbackStateListener)
            release()
        }
        player = null
    }

    @SuppressLint("InlinedApi")
    private fun hideSystemUi() {
        viewBinding.videoView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LOW_PROFILE
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
    }
}

private fun playbackStateListener() = object : Player.EventListener {
    override fun onPlaybackStateChanged(playbackState: Int) {
        val stateString: String = when (playbackState) {
            ExoPlayer.STATE_IDLE -> "ExoPlayer.STATE_IDLE      -"
            ExoPlayer.STATE_BUFFERING -> "ExoPlayer.STATE_BUFFERING -"
            ExoPlayer.STATE_READY -> "ExoPlayer.STATE_READY     -"
            ExoPlayer.STATE_ENDED -> "ExoPlayer.STATE_ENDED     -"
            else -> "UNKNOWN_STATE             -"
        }
        Log.d(TAG, "changed state to $stateString")
    }
}
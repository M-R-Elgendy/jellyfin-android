package org.jellyfin.mobile.player.ui

import android.animation.Animator
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import org.jellyfin.mobile.R
import timber.log.Timber

class QueueSheetHelper(
    private val sheetRoot: View,
    private val onClose: () -> Unit,
) {
    var isOpen: Boolean = false
        private set

    private var lastToggleTime: Long = 0L
    private var translationAnimator: ObjectAnimator? = null

    private val recyclerView: RecyclerView = sheetRoot.findViewById(R.id.queue_recycler_view)
    private val emptyText: TextView = sheetRoot.findViewById(R.id.queue_empty_text)
    private val closeButton: ImageButton = sheetRoot.findViewById(R.id.queue_close_button)

    init {
        closeButton.setOnClickListener {
            Timber.d("QueueSheet: close button clicked")
            close()
        }
        setupDragToDismiss()
        logSheetState("helper-init")
    }

    fun open(scrollToIndex: Int = -1) {
        Timber.d(
            "QueueSheet: open() entry scrollToIndex=%d isOpen=%b canToggle=%b msSinceToggle=%d",
            scrollToIndex,
            isOpen,
            canToggle(),
            System.currentTimeMillis() - lastToggleTime,
        )
        logSheetState("open-before-guards")
        if (isOpen || !canToggle()) {
            Timber.d("QueueSheet: open() aborted (already open or debounced)")
            return
        }
        isOpen = true
        lastToggleTime = System.currentTimeMillis()

        cancelTranslationAnimator()
        updateSheetHeight()
        sheetRoot.isVisible = true
        sheetRoot.translationY = sheetRoot.height.toFloat().coerceAtLeast(sheetRoot.resources.displayMetrics.heightPixels.toFloat())

        translationAnimator = ObjectAnimator.ofFloat(sheetRoot, View.TRANSLATION_Y, sheetRoot.translationY, 0f).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = DecelerateInterpolator()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    Timber.d("QueueSheet: open translation animator onAnimationEnd")
                    translationAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    Timber.d("QueueSheet: open translation animator onAnimationCancel")
                    translationAnimator = null
                }
            })
            start()
        }
        logSheetState("open-after-start")

        if (scrollToIndex >= 0) {
            recyclerView.post { recyclerView.scrollToPosition(scrollToIndex) }
        }
    }

    fun close() {
        Timber.d("QueueSheet: close() entry isOpen=%b sheetRoot.isVisible=%b", isOpen, sheetRoot.isVisible)
        logSheetState("close-entry")
        if (!sheetRoot.isVisible) {
            Timber.d("QueueSheet: close() no-op (sheet not visible); syncing isOpen=false")
            isOpen = false
            return
        }

        isOpen = false
        lastToggleTime = System.currentTimeMillis()

        cancelTranslationAnimator()

        val targetY = sheetRoot.height.toFloat()
        if (targetY <= 0f) {
            Timber.d("QueueSheet: close() immediate hide (targetY<=0)")
            sheetRoot.isVisible = false
            onClose()
            logSheetState("close-immediate-hide")
            return
        }

        val startY = sheetRoot.translationY
        Timber.d("QueueSheet: close() animating translationY %.1f -> %.1f", startY, targetY)
        translationAnimator = ObjectAnimator.ofFloat(sheetRoot, View.TRANSLATION_Y, startY, targetY).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = DecelerateInterpolator()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    Timber.d("QueueSheet: close translation onAnimationEnd -> GONE + onClose()")
                    translationAnimator = null
                    sheetRoot.isVisible = false
                    onClose()
                    logSheetState("close-anim-end")
                }

                override fun onAnimationCancel(animation: Animator) {
                    Timber.d("QueueSheet: close translation onAnimationCancel -> GONE + onClose()")
                    translationAnimator = null
                    sheetRoot.isVisible = false
                    onClose()
                    logSheetState("close-anim-cancel")
                }
            })
            start()
        }
    }

    fun toggle(scrollToIndex: Int = -1) {
        Timber.d("QueueSheet: toggle() isOpen=%b -> will %s", isOpen, if (isOpen) "close" else "open")
        logSheetState("toggle-entry")
        if (isOpen) close() else open(scrollToIndex)
        logSheetState("toggle-exit")
    }

    fun handleConfiguration(newConfig: Configuration) {
        Timber.d("QueueSheet: handleConfiguration isOpen=%b newOrientation=%d", isOpen, newConfig.orientation)
        if (isOpen) {
            updateSheetHeight()
            logSheetState("handleConfiguration-after-resize")
        }
    }

    fun updateQueueState(hasItems: Boolean) {
        Timber.d("QueueSheet: updateQueueState hasItems=%b", hasItems)
        emptyText.isVisible = !hasItems
        recyclerView.isVisible = hasItems
    }

    private fun updateSheetHeight() {
        val screenHeight = sheetRoot.resources.displayMetrics.heightPixels
        val sheetHeight = (screenHeight * SHEET_HEIGHT_RATIO).toInt()
        val parent = sheetRoot.parent as? ViewGroup ?: return

        val newParams: ViewGroup.LayoutParams = when (parent) {
            is CoordinatorLayout -> {
                val existing = sheetRoot.layoutParams as? CoordinatorLayout.LayoutParams
                (existing ?: CoordinatorLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    sheetHeight,
                )).apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = sheetHeight
                    gravity = Gravity.BOTTOM
                }
            }
            is FrameLayout -> {
                val existing = sheetRoot.layoutParams as? FrameLayout.LayoutParams
                (existing ?: FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    sheetHeight,
                )).apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = sheetHeight
                    gravity = Gravity.BOTTOM
                }
            }
            else -> {
                sheetRoot.layoutParams?.apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = sheetHeight
                } ?: ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    sheetHeight,
                )
            }
        }
        sheetRoot.layoutParams = newParams
        sheetRoot.translationY = if (isOpen) 0f else sheetHeight.toFloat()
        parent.requestLayout()
        sheetRoot.requestLayout()

        Timber.d(
            "QueueSheet: updateSheetHeight screenH=%d sheetH=%d isOpen=%b translationY=%.1f parent=%s",
            screenHeight,
            sheetHeight,
            isOpen,
            sheetRoot.translationY,
            parent.javaClass.simpleName,
        )
    }

    private fun canToggle(): Boolean {
        return System.currentTimeMillis() - lastToggleTime > DEBOUNCE_MS
    }

    private fun cancelTranslationAnimator() {
        if (translationAnimator != null) {
            Timber.d("QueueSheet: cancelTranslationAnimator (had running animator)")
        }
        translationAnimator?.removeAllListeners()
        translationAnimator?.cancel()
        translationAnimator = null
    }

    private fun logSheetState(reason: String) {
        val lp = sheetRoot.layoutParams
        Timber.d(
            "QueueSheet: [%s] isOpen=%b isVisible(ext)=%b visibility=%d alpha=%.2f ty=%.1f z=%.1f " +
                "size=%dx%d measured=%dx%d lpH=%s clickable=%b focusable=%b",
            reason,
            isOpen,
            sheetRoot.isVisible,
            sheetRoot.visibility,
            sheetRoot.alpha,
            sheetRoot.translationY,
            sheetRoot.z,
            sheetRoot.width,
            sheetRoot.height,
            sheetRoot.measuredWidth,
            sheetRoot.measuredHeight,
            if (lp != null) lp.height.toString() else "null",
            sheetRoot.isClickable,
            sheetRoot.isFocusable,
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragToDismiss() {
        val gestureDetector = GestureDetector(sheetRoot.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (velocityY > FLING_VELOCITY_THRESHOLD) {
                    Timber.d("QueueSheet: fling-down on drag handle -> close()")
                    close()
                    return true
                }
                return false
            }
        })

        sheetRoot.findViewById<View>(R.id.queue_drag_handle)?.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    companion object {
        private const val ANIMATION_DURATION_MS = 280L
        private const val DEBOUNCE_MS = 300L
        private const val SHEET_HEIGHT_RATIO = 0.8
        private const val FLING_VELOCITY_THRESHOLD = 500f
    }
}

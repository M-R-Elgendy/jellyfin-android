package org.jellyfin.mobile.player.ui

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.TextView
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

    private val recyclerView: RecyclerView = sheetRoot.findViewById(R.id.queue_recycler_view)
    private val emptyText: TextView = sheetRoot.findViewById(R.id.queue_empty_text)
    private val closeButton: ImageButton = sheetRoot.findViewById(R.id.queue_close_button)

    init {
        closeButton.setOnClickListener { close() }
        setupDragToDismiss()
    }

    fun open(scrollToIndex: Int = -1) {
        Timber.d("QueueSheet: open() called, isOpen=%b, canToggle=%b", isOpen, canToggle())
        if (isOpen || !canToggle()) return
        isOpen = true
        lastToggleTime = System.currentTimeMillis()

        updateSheetHeight()
        sheetRoot.isVisible = true
        sheetRoot.translationY = sheetRoot.height.toFloat().coerceAtLeast(sheetRoot.resources.displayMetrics.heightPixels.toFloat())

        ObjectAnimator.ofFloat(sheetRoot, View.TRANSLATION_Y, sheetRoot.translationY, 0f).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = DecelerateInterpolator()
            start()
        }

        if (scrollToIndex >= 0) {
            recyclerView.post { recyclerView.scrollToPosition(scrollToIndex) }
        }
    }

    fun close() {
        if (!isOpen || !canToggle()) return
        isOpen = false
        lastToggleTime = System.currentTimeMillis()

        val targetY = sheetRoot.height.toFloat()
        ObjectAnimator.ofFloat(sheetRoot, View.TRANSLATION_Y, 0f, targetY).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = DecelerateInterpolator()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    sheetRoot.isVisible = false
                    onClose()
                }
            })
            start()
        }
    }

    fun toggle(scrollToIndex: Int = -1) {
        Timber.d("QueueSheet: toggle() called, isOpen=%b", isOpen)
        if (isOpen) close() else open(scrollToIndex)
    }

    fun handleConfiguration(newConfig: Configuration) {
        if (isOpen) {
            updateSheetHeight()
        }
    }

    fun updateQueueState(hasItems: Boolean) {
        emptyText.isVisible = !hasItems
        recyclerView.isVisible = hasItems
    }

    private fun updateSheetHeight() {
        val screenHeight = sheetRoot.resources.displayMetrics.heightPixels
        val sheetHeight = (screenHeight * SHEET_HEIGHT_RATIO).toInt()
        sheetRoot.layoutParams = sheetRoot.layoutParams?.apply {
            height = sheetHeight
        }
        (sheetRoot.parent as? View)?.let { parent ->
            sheetRoot.translationY = if (isOpen) 0f else sheetHeight.toFloat()
        }
    }

    private fun canToggle(): Boolean {
        return System.currentTimeMillis() - lastToggleTime > DEBOUNCE_MS
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

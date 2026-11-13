package ai.arena.arkernel.diagnostics

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Shared base activity for every diagnostics screen.
 *
 * Android 15 (API 35) enforces edge-to-edge for apps that target it, which
 * this app does. Without handling the insets, the system status bar covers the
 * first control and the navigation bar (the on-screen Home / Back / Recents
 * row) covers the last button, so the bottom of the screen becomes unusable.
 *
 * This base class therefore opts into drawing behind the system bars and then
 * re-applies the exact system-bar insets as padding on the content view. The
 * original padding of each screen is captured once and kept, so the padding is
 * never accumulated when the insets are dispatched more than once (rotation,
 * keyboard, navigation-mode change).
 *
 * This is a pure layout concern: it captures nothing, stores nothing, uploads
 * nothing and changes no measurement, no contract and no quality grade.
 */
open class DiagnosticsActivity : android.app.Activity() {

    private var insetBaseTop = 0
    private var insetBaseBottom = 0
    private var insetBaseLeft = 0
    private var insetBaseRight = 0
    private var insetBaseCaptured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    }

    override fun setContentView(view: View) {
        super.setContentView(view)
        applySystemBars(view)
    }

    /** Adds the system-bar insets to the content view's existing padding. */
    protected fun applySystemBars(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Below API 30 the framework still fits the content itself, so
            // adding insets here would double the padding.
            return
        }
        if (!insetBaseCaptured) {
            insetBaseLeft = view.paddingLeft
            insetBaseTop = view.paddingTop
            insetBaseRight = view.paddingRight
            insetBaseBottom = view.paddingBottom
            insetBaseCaptured = true
        }
        view.setOnApplyWindowInsetsListener { target, insets ->
            val top = systemInsetTop(insets)
            val bottom = systemInsetBottom(insets)
            target.setPadding(
                insetBaseLeft,
                insetBaseTop + top,
                insetBaseRight,
                insetBaseBottom + bottom,
            )
            insets
        }
        if (view.isAttachedToWindow) {
            view.requestApplyInsets()
        } else {
            view.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(attached: View) {
                        attached.requestApplyInsets()
                        attached.removeOnAttachStateChangeListener(this)
                    }

                    override fun onViewDetachedFromWindow(detached: View) {
                    }
                })
        }
    }

    private fun systemInsetTop(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(
                WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout()
            ).top
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetTop
        }

    private fun systemInsetBottom(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(
                WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout()
            ).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }
}

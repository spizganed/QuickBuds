package com.spizganed.quickbuds.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ScrollView
import com.spizganed.quickbuds.R
import java.util.WeakHashMap

/**
 * Glass motion for every screen in every style but the dot style: the screen rises and fades in, its cards follow one after the other, and a tapped
 * row sinks a little under the finger. Other styles keep the platform's. Nothing runs when the system removes animations.
 */
object Entrance : Application.ActivityLifecycleCallbacks {
    private val staged = WeakHashMap<Activity, Boolean>()

    fun install(app: Application) = app.registerActivityLifecycleCallbacks(this)

    private fun on(a: Activity) = !ThemeRes.nothing(a) && android.provider.Settings.Global.getFloat(
        a.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    override fun onActivityCreated(a: Activity, b: Bundle?) {
        if (on(a)) a.window.setWindowAnimations(R.style.GlassWindowAnim)
    }

    override fun onActivityResumed(a: Activity) {
        if (!on(a)) return
        val content = a.findViewById<ViewGroup>(android.R.id.content) ?: return
        // A screen rebuilt by a theme change fades in from its own picture: no second entrance on top of it.
        val rebuilt = ThemeRes.justRecreated
        if (rebuilt) { ThemeRes.justRecreated = false; staged[a] = true }
        content.post {
            if (staged.put(a, true) == null) stagger(a, content)
            content.postDelayed({ feel(content) }, 450)
        }
    }

    /** The cards of the first scrolling column fade and rise in turn. */
    private fun stagger(a: Activity, content: ViewGroup) {
        val column = firstColumn(content) ?: return
        val rise = ThemeRes.dp(a, 28f).toFloat()
        // Quick: the whole entrance ends in about half a second. Each card rises past its place and settles (the wow), and the first cards lead.
        val kids = (0 until column.childCount).map { column.getChildAt(it) }.filter { it.visibility == View.VISIBLE }.take(10)
        kids.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = rise
            v.scaleX = 0.96f; v.scaleY = 0.96f
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(i * 28L).setDuration(300)
                .setInterpolator(OvershootInterpolator(1.4f)).start()
        }
    }

    private fun firstColumn(root: ViewGroup): ViewGroup? {
        fun find(v: View): ScrollView? {
            if (v is ScrollView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        val scroll = find(root) ?: return null
        return scroll.getChildAt(0) as? ViewGroup
    }

    /** Clickable rows and buttons sink on press and spring back. The listener returns false, so clicks still work. */
    private fun feel(v: View) {
        if (v.hasOnClickListeners() && v.getTag(R.id.glass_feel) == null && v.width > ThemeRes.dp(v.context, 40f)) {
            v.setTag(R.id.glass_feel, true)
            val big = v.width > ThemeRes.dp(v.context, 180f)
            val to = if (big) 0.975f else 0.93f
            v.setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> view.animate().scaleX(to).scaleY(to).setDuration(90).setInterpolator(DecelerateInterpolator()).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(2.8f)).start()
                }
                false
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) feel(v.getChildAt(i))
    }

    override fun onActivityStarted(a: Activity) {}
    override fun onActivityPaused(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}

package app.lawnchair

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import app.lawnchair.font.FontManager
import com.android.launcher3.BubbleTextView
import com.android.launcher3.util.SafeCloseable
import com.android.launcher3.views.DoubleShadowBubbleTextView

class LawnchairLayoutFactory(context: Context) :
    LayoutInflater.Factory2,
    SafeCloseable {

    private val constructorMap = mapOf<String, (Context, AttributeSet) -> View>(
        "Button" to ::Button,
        "TextView" to ::TextView,
        BubbleTextView::class.java.name to ::BubbleTextView,
        DoubleShadowBubbleTextView::class.java.name to ::DoubleShadowBubbleTextView,
    )

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet,
    ): View? {
        val view = constructorMap[name]?.let { it(context, attrs) }
        if (view is TextView) {
            // Issue #565: resolve the font manager per call; do NOT re-introduce a `by lazy`
            // here. MainThreadInitializedObject.get() parks a non-main caller on a main-thread
            // future, and a lazy monitor held across that wait deadlocks the main-thread
            // inflator of the same factory instance (ViewPool cloneInContext shares it) —
            // cold-start ANR. The MTIO mValue is the single memoization.
            // Oracle: docs/assessment/563-home-empty-overview-evidence/anr/cold-start-deadlock-anr-trace.txt
            runCatching { FontManager.INSTANCE.get(context).overrideFont(view, attrs) }
        }
        return view
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
        return onCreateView(null, name, context, attrs)
    }

    override fun close() {
        TODO("Not yet implemented")
    }
}

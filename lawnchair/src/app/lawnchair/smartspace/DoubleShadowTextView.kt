package app.lawnchair.smartspace

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.widget.TextView
import app.lawnchair.views.CustomTextView
import com.android.launcher3.views.ShadowInfo

open class DoubleShadowTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : CustomTextView(context, attrs) {

    // Rebase Phase 2 adapt (#532): ShadowInfo moved to a top-level data class with a
    // fromContext factory and no skipDoubleShadow helper; the helper is inlined below.
    private val shadowInfo = ShadowInfo.fromContext(context, attrs, 0)

    init {
        setShadowLayer(shadowInfo.ambientShadowBlur, 0f, 0f, shadowInfo.ambientShadowColor)
    }

    override fun onDraw(canvas: Canvas) {
        // If text is transparent or shadow alpha is 0, don't draw any shadow
        if (skipDoubleShadow(this)) {
            super.onDraw(canvas)
            return
        }

        // We enhance the shadow by drawing the shadow twice
        paint.setShadowLayer(shadowInfo.ambientShadowBlur, 0f, 0f, shadowInfo.ambientShadowColor)

        super.onDraw(canvas)
        canvas.save()
        canvas.clipRect(
            scrollX,
            scrollY + extendedPaddingTop,
            scrollX + width,
            scrollY + height,
        )

        paint.setShadowLayer(
            shadowInfo.keyShadowBlur,
            shadowInfo.keyShadowOffsetX,
            shadowInfo.keyShadowOffsetY,
            shadowInfo.keyShadowColor,
        )
        super.onDraw(canvas)
        canvas.restore()
    }

    private fun skipDoubleShadow(textView: TextView): Boolean {
        val textAlpha = Color.alpha(textView.currentTextColor)
        val keyShadowAlpha = Color.alpha(shadowInfo.keyShadowColor)
        val ambientShadowAlpha = Color.alpha(shadowInfo.ambientShadowColor)
        return when {
            textAlpha == 0 || (keyShadowAlpha == 0 && ambientShadowAlpha == 0) -> {
                paint.clearShadowLayer()
                true
            }

            else -> false
        }
    }
}

package app.lawnchair

import android.content.Context
import androidx.annotation.Keep
import androidx.arch.core.util.Function
import app.lawnchair.bugreport.LawnchairBugReporter
import app.lawnchair.homeedit.AppDestinationBridge
import app.lawnchair.theme.color.tokens.ColorTokens
import com.android.launcher3.Utilities
import com.android.launcher3.icons.ThemedIconDrawable
import com.android.quickstep.QuickstepProcessInitializer

@Keep
class LawnchairProcessInitializer(context: Context) : QuickstepProcessInitializer(context) {

    override fun init(context: Context) {
        LawnchairBugReporter.INSTANCE.get(context)
        // Issue #497: destination-policy resolver for the auto-add path
        // (ADR-0015 Decision 12 — the fork-side direct-edit-side module owns
        // the policy; without this registration the stock behavior remains).
        AppDestinationBridge.install(context)
        ThemedIconDrawable.COLORS_LOADER = Function {
            if (Utilities.isDarkTheme(it)) {
                intArrayOf(
                    ColorTokens.Accent2_800.resolveColor(it),
                    ColorTokens.Accent1_200.resolveColor(it),
                )
            } else {
                intArrayOf(
                    ColorTokens.Accent1_100.resolveColor(it),
                    ColorTokens.Accent1_700.resolveColor(it),
                )
            }
        }
        super.init(context)
    }
}

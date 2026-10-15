package com.moneybook.widget

import android.content.Context
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.R
import org.junit.Test
import org.junit.runner.RunWith

/** The widget picker draws each preview as RemoteViews, which only allow certain view classes ("Can't load widget"). */
@RunWith(AndroidJUnit4::class)
class WidgetPreviewTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun everyPreviewInflatesAsRemoteViews() {
        listOf(
            R.layout.widget_preview_summary,
            R.layout.widget_preview_quick_2x1, R.layout.widget_preview_quick_2x1_white,
            R.layout.widget_preview_quick_2x2, R.layout.widget_preview_quick_2x2_white,
            R.layout.widget_preview_quick_4x1, R.layout.widget_preview_quick_4x1_white,
        ).forEach { layout -> RemoteViews(context.packageName, layout).apply(context, FrameLayout(context)) }
    }
}

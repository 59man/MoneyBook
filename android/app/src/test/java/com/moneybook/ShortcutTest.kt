package com.moneybook

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** The launcher's "Add" shortcut (Realbyte's 1×1 pencil) opens a new entry through MainActivity's open extra. */
@RunWith(AndroidJUnit4::class)
class ShortcutTest {
    @Test fun theAddShortcutOpensANewEntry() {
        val parser = ApplicationProvider.getApplicationContext<Context>().resources.getXml(R.xml.shortcuts)
        val found = mutableMapOf<String, String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            for (i in 0 until parser.attributeCount) found["${parser.name}.${parser.getAttributeName(i)}"] = parser.getAttributeValue(i)
        }
        assertEquals("add", found["shortcut.shortcutId"])
        assertEquals("com.moneybook.MainActivity", found["intent.targetClass"])
        assertEquals(MainActivity.EXTRA_OPEN, found["extra.name"])
        assertEquals(MainActivity.OPEN_ADD, found["extra.value"])
    }
}

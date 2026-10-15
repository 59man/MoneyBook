package com.moneybook.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Android's own backup must never copy the device-only settings (the passcode) to the cloud or a new phone. */
@RunWith(AndroidJUnit4::class)
class DeviceBackupRulesTest {
    private val deviceFile = "datastore/device_settings.preferences_pb"

    /** Each section (by its tag) that excludes the device settings file from the "file" domain. */
    private fun excludingSections(xml: Int): List<String> {
        val parser = ApplicationProvider.getApplicationContext<android.content.Context>().resources.getXml(xml)
        val sections = mutableListOf<String>()
        var section = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "exclude" -> if (parser.getAttributeValue(null, "domain") == "file" && parser.getAttributeValue(null, "path") == deviceFile) {
                    sections += section
                }
                else -> section = parser.name
            }
        }
        return sections
    }

    @Test fun android12AndLaterExcludeItFromCloudBackupAndDeviceTransfer() {
        assertEquals(listOf("cloud-backup", "device-transfer"), excludingSections(R.xml.data_extraction_rules))
    }

    @Test fun olderAndroidExcludesItFromFullBackup() {
        assertEquals(listOf("full-backup-content"), excludingSections(R.xml.backup_rules))
    }
}

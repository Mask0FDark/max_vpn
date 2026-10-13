package ru.vgastream.maxvpn

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** A reinstall must not require re-selecting a chat already saved on VPS. */
@RunWith(AndroidJUnit4::class)
class ChatRestoreTest {
    @Test fun restoreOnlyAuthenticatedServerChat() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val activity = instrument.startActivitySync(
            Intent(context, MaxPhoneLoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MaxPhoneLoginActivity
        val prefs = context.getSharedPreferences("vpn-private-settings", 0)
        val original = prefs.getLong("max_chat_id", 0L)
        try {
            instrument.runOnMainSync {
                prefs.edit().putLong("max_chat_id", 0L).commit()
                activity.restoreSelectedChatFromStatus(
                    JSONObject().put("phase", "waiting_code").put("chat_id", 90123L))
                assertEquals(0L, prefs.getLong("max_chat_id", 0L))
                activity.restoreSelectedChatFromStatus(
                    JSONObject().put("phase", "connected").put("chat_id", 90123L))
                assertEquals(90123L, prefs.getLong("max_chat_id", 0L))
                activity.finish()
            }
        } finally {
            prefs.edit().putLong("max_chat_id", original).commit()
        }
        instrument.waitForIdleSync()
    }
}

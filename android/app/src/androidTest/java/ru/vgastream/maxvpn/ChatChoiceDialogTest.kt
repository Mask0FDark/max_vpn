package ru.vgastream.maxvpn

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Regression: dialog must show actual MAX chats, not a message-only panel. */
@RunWith(AndroidJUnit4::class)
class ChatChoiceDialogTest {
    @Test fun chatListIsVisibleAndSelectable() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext
        val activity = instrument.startActivitySync(
            Intent(app, MaxPhoneLoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MaxPhoneLoginActivity
        instrument.runOnMainSync {
            val dialog = activity.createChatChoiceDialog(arrayOf("Private A", "Private B")) {}
            dialog.show()
            val list = dialog.listView
            assertNotNull("chat dialog must contain a list", list)
            assertEquals(2, list.adapter.count)
            assertEquals("Private A", list.adapter.getItem(0))
            dialog.dismiss()
            activity.finish()
        }
        instrument.waitForIdleSync()
    }
}

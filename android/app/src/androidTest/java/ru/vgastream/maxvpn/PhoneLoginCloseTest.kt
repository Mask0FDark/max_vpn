package ru.vgastream.maxvpn

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Regression for Xiaomi NetworkOnMainThreadException while closing login UI. */
@RunWith(AndroidJUnit4::class)
class PhoneLoginCloseTest {
    @Test fun closingPhoneLoginDoesNotPerformSocketIOOnMainThread() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext
        val activity = instrument.startActivitySync(
            Intent(app, MaxPhoneLoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        instrument.runOnMainSync { activity.finish() }
        instrument.waitForIdleSync()
        Thread.sleep(650)
        assertTrue("login activity should be destroyed without network exception", activity.isDestroyed)
    }
}

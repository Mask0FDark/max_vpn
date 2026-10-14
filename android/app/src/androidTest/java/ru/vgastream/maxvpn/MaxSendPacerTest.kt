package ru.vgastream.maxvpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Offline account pacing tests: no real MAX account or vendor requests. */
@RunWith(AndroidJUnit4::class)
class MaxSendPacerTest {
    @Test fun consecutiveFirefoxFramesCannotFloodMax() {
        var now = 1000L
        val sleeps = mutableListOf<Long>()
        val gate = MaxSendPacer(
            intervalMillis = 1800,
            clockMillis = { now },
            sleepMillis = { ms -> sleeps.add(ms); now += ms }
        )
        assertTrue(gate.waitForSlot { true })
        assertTrue(gate.waitForSlot { true })
        assertEquals(listOf(1800L), sleeps)
    }

    @Test fun rateLimitAppliesCooldownWithoutAutomaticallyResending() {
        var now = 1000L
        val gate = MaxSendPacer(
            intervalMillis = 1800,
            clockMillis = { now },
            sleepMillis = { ms -> now += ms }
        )
        assertTrue(gate.waitForSlot { true })
        gate.rejected(rateLimited = true)
        assertFalse(gate.waitForSlot { true })
        now += 15000
        assertTrue(gate.waitForSlot { true })
        gate.rejected(rateLimited = true)
        assertFalse(gate.waitForSlot { true })
        now += 30000
        assertTrue(gate.waitForSlot { true })
        gate.accepted()
        gate.rejected(rateLimited = true)
        now += 15000
        assertTrue(gate.waitForSlot { true })
    }

    @Test fun noSendWhenDisconnectedEvenIfSlotOpens() {
        var now = 0L
        val gate = MaxSendPacer(
            clockMillis = { now },
            sleepMillis = { ms -> now += ms }
        )
        assertFalse(gate.waitForSlot { false })
        assertTrue(gate.waitForSlot { true })
        assertFalse(gate.waitForSlot { false })
    }
}

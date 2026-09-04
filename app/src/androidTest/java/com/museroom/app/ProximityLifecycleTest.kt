package com.museroom.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.museroom.app.proximity.ProximityManager
import com.museroom.app.proximity.ProximityStatus
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Starting and stopping Nearby, repeatedly, without taking the app with it.
 *
 * Two things in here can only fail on a device. The manager now registers a
 * broadcast receiver so it hears the Bluetooth radio being switched off, and a
 * receiver unregistered twice, or never, throws — the first as an exception on
 * the way out, the second as a leak the system complains about later. And the
 * scan is now restarted whenever the screen comes to the front, which means
 * stopScan runs against a scanner that may have nothing running on it.
 *
 * None of this needs a signed-in session or a second phone: what is being
 * checked is that the lifecycle survives being driven, not that anybody is
 * found.
 */
@RunWith(AndroidJUnit4::class)
class ProximityLifecycleTest {

    private val manager =
        ProximityManager.get(ApplicationProvider.getApplicationContext())

    @After
    fun tearDown() {
        runCatching { manager.setForeground(false) }
        runCatching { manager.stop() }
    }

    /**
     * Stopping something that was never started, twice.
     *
     * The receiver is unregistered here, and unregistering one that was never
     * registered is an IllegalArgumentException rather than a quiet no-op.
     */
    @Test
    fun stoppingWithoutStartingIsHarmless() {
        manager.stop()
        manager.stop()
        assertTrue(manager.state.value is ProximityStatus.Off)
    }

    /**
     * The screen coming and going while nothing is running.
     *
     * setForeground restarts the scan, and on this path there is no scan and
     * possibly no Bluetooth adapter at all.
     */
    @Test
    fun theScreenCanComeAndGoWithNothingRunning() {
        repeat(3) {
            manager.setForeground(true)
            manager.setForeground(false)
        }
    }

    /**
     * The whole cycle, three times over.
     *
     * Without permissions this stops at NeedsPermission and never reaches the
     * radio, which is itself worth asserting: the failure has to be a state,
     * not an exception. With them it runs for real, and the third stop is the
     * one that would find a receiver being unregistered twice.
     */
    @Test
    fun startingAndStoppingRepeatedlyDoesNotThrow() {
        repeat(3) {
            manager.start()
            manager.setForeground(true)
            manager.setForeground(false)
            manager.stop()
        }
        assertTrue(
            "stop() must leave it off, was ${manager.state.value}",
            manager.state.value is ProximityStatus.Off,
        )
        assertTrue("the list must be emptied on stop", manager.nearby.value.isEmpty())
    }
}

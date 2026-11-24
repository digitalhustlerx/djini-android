package com.wangzan101.djini.core.buddy

import com.wangzan101.djini.core.assembly.toolDispatcher
import com.wangzan101.djini.core.model.Part
import com.wangzan101.djini.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset

class HabitLedgerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun hapticPatternValidatesSafetyBounds() {
        assertTrue(HapticPattern.BUILT_IN.size >= 6)
        assertThrows(IllegalArgumentException::class.java) {
            HapticPattern("bad", "Bad", "too long", listOf(0L, 7_000L), listOf(0, 200))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HapticPattern("bad", "Bad", "bad amp", listOf(0L, 100L), listOf(0, 300))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HapticPattern("bad", "Bad", "mismatch", listOf(0L, 100L), listOf(0))
        }
    }

    @Test fun detectsDoomscrollWithCooldownAndPersistsSynthesis() {
        var now = 1_700_000_000_000L
        val dir = tmp.newFolder("buddy")
        val ledger = HabitLedger(dir, clock = { now }, zone = { ZoneOffset.UTC })
        ledger.updateConfig { it.copy(doomscrollScrollThreshold = 5, nudgeCooldownSec = 120) }

        ledger.recordPickup(now)
        ledger.onForegroundApp("com.instagram.android", "Instagram", now)
        now += 120_000L // 2 minutes
        ledger.onForegroundApp("com.android.settings", "Settings", now)

        var fired: BuddyIntervention? = null
        repeat(5) {
            now += 1_000L
            fired = ledger.onScroll("com.instagram.android", "Instagram", now)
        }
        assertTrue("Expected Doomscroll intervention", fired is BuddyIntervention.Doomscroll)
        val doom = fired as BuddyIntervention.Doomscroll
        assertEquals("Instagram", doom.appLabel)
        assertEquals(5, doom.scrollsInWindow)

        // Cooldown prevents spamming right away
        repeat(6) {
            now += 1_000L
            assertNull(ledger.onScroll("com.instagram.android", "Instagram", now))
        }

        val savedInsight = ledger.saveInsight("habit", "Late-night Instagram loops", "Trigger gentle_tap after 15 scrolls", 7, now)
        assertEquals("habit", savedInsight.category)
        ledger.flush()

        // Reload from disk and verify persistence + synthesis
        val reloaded = HabitLedger(dir, clock = { now }, zone = { ZoneOffset.UTC })
        val report = reloaded.synthesize(7, now)
        assertEquals(1, report.todayPickups)
        assertTrue(report.todayForegroundMinutes >= 2)
        assertEquals(1, report.todayDoomscrollEpisodes)
        assertEquals(1, report.recentInsights.size)
        assertEquals("Late-night Instagram loops", report.recentInsights.first().summary)
    }

    @Test fun focusSessionTriggersDriftInterventionAndAutoBounceFlag() {
        var now = 1_700_000_000_000L
        val ledger = HabitLedger(tmp.newFolder("focus"), clock = { now }, zone = { ZoneOffset.UTC })
        val session = ledger.startFocusSession("Ship Djini Pulse", 30, setOf("com.zhiliaoapp.musically"), autoBounce = true, nowMs = now)
        assertEquals("Ship Djini Pulse", session.goal)

        now += 60_000L
        val drift = ledger.onForegroundApp("com.zhiliaoapp.musically", "TikTok", now)
        assertTrue(drift is BuddyIntervention.FocusDrift)
        assertTrue((drift as BuddyIntervention.FocusDrift).autoBounce)
        assertEquals(29, drift.remainingMinutes)

        now += 10 * 60_000L
        ledger.stopFocusSession(completed = true, nowMs = now)
        assertNull(ledger.activeFocus(now))
        assertTrue(ledger.synthesize(1, now).todayFocusMinutes >= 11)
    }

    @Test fun buddyCapabilityToolsWireIntoToolDispatcher() = runBlocking {
        var playedId: String? = null
        val port = object : BuddyPort {
            override suspend fun habitStats(days: Int) = buildJsonObject { put("days", days) }
            override suspend fun playHaptic(patternId: String?, timingsMs: List<Long>?, amplitudes: List<Int>?): JsonObject {
                playedId = patternId
                return buildJsonObject { put("played", patternId ?: "custom") }
            }
            override suspend fun saveHaptic(pattern: HapticPattern) = buildJsonObject { put("saved", pattern.id) }
            override suspend fun focusControl(
                action: String, goal: String?, durationMinutes: Int?, distractionPackages: Set<String>?,
                autoBounce: Boolean?, doomscrollThreshold: Int?, vipKeywords: List<String>?, personalityTone: String?,
            ) = buildJsonObject { put("action", action) }
            override suspend fun saveInsight(category: String, summary: String, recommendation: String, sourceDays: Int) =
                buildJsonObject { put("saved", true) }
        }
        val dispatcher = toolDispatcher(FakeScreen(), buddy = port)
        val names = dispatcher.definitions().map { it.name }.toSet()
        assertTrue("buddy_habit_stats" in names)
        assertTrue("buddy_haptic_play" in names)
        assertTrue("buddy_haptic_save" in names)
        assertTrue("buddy_focus_control" in names)
        assertTrue("buddy_insight_save" in names)

        val stats = dispatcher.executeRead(Part.ToolCall("c1", "buddy_habit_stats", """{"days":3}"""))
        assertEquals(3, stats.content["days"]!!.jsonPrimitive.int)

        val played = port.playHaptic("heartbeat", null, null)
        assertEquals("heartbeat", playedId)
        assertEquals("heartbeat", played["played"]!!.jsonPrimitive.content)
    }

    @Test fun customHapticSaveDeleteAndNightlyBriefCompareAgainstBaseline() {
        val dayMs = 86_400_000L
        var now = 1_700_000_000_000L
        val ledger = HabitLedger(tmp.newFolder("v2"), clock = { now }, zone = { ZoneOffset.UTC })

        // Save and delete custom haptic, ensuring built-ins cannot be deleted
        val custom = HapticPattern("my_wave", "My Wave", "Custom cue", listOf(0L, 60L, 40L, 90L), listOf(0, 140, 0, 220))
        ledger.saveHapticPattern(custom)
        assertNotNull(ledger.config().hapticById("my_wave"))
        assertTrue(ledger.deleteHapticPattern("my_wave"))
        assertNull(ledger.config().hapticById("my_wave"))
        assertTrue(!ledger.deleteHapticPattern("heartbeat"))
        assertNotNull(ledger.config().hapticById("heartbeat"))

        // Seed yesterday (30m) and today (75m) -> +45m vs 7-day average
        val yesterdayIso = java.time.Instant.ofEpochMilli(now - dayMs).atZone(ZoneOffset.UTC).toLocalDate().toString()
        val todayIso = java.time.Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().toString()
        ledger.mergeSystemUsage(yesterdayIso, mapOf("com.instagram.android" to ("Instagram" to 30 * 60_000L)), now - dayMs)
        ledger.mergeSystemUsage(todayIso, mapOf("com.instagram.android" to ("Instagram" to 75 * 60_000L)), now)
        ledger.recordPickup(now)

        val brief = ledger.generateNightlyBrief(now)
        assertEquals(todayIso, brief.dateIso)
        assertEquals(75, brief.todayMinutes)
        assertEquals(30, brief.avgMinutes7d)
        assertEquals(45, brief.deltaMinutesVsAvg)
        assertEquals("Instagram", brief.topDistractionApp)
        assertEquals("nightly", brief.savedInsight.category)
        assertEquals(todayIso, ledger.config().lastNightlyBriefDate)
    }
}

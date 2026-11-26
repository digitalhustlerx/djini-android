package com.wangzan101.djini.buddy

import com.wangzan101.djini.core.buddy.BuddyPort
import com.wangzan101.djini.core.buddy.HapticPattern
import com.wangzan101.djini.core.buddy.toJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId

/**
 * Connects Core's [com.wangzan101.djini.core.buddy.BuddyCapability] agent tools to Android's
 * [BuddyRuntime], [HapticBehaviorEngine], and [AndroidUsageCollector].
 */
class AndroidBuddyPort(
    private val runtime: BuddyRuntime,
    private val conversationId: String? = null,
) : BuddyPort {
    override suspend fun habitStats(days: Int): JsonObject {
        val todayMap = runtime.usage.collectTodayUsage()
        if (todayMap.isNotEmpty()) {
            runtime.ledger.mergeSystemUsage(LocalDate.now(ZoneId.systemDefault()).toString(), todayMap)
            runtime.ledger.flush()
            runtime.refresh()
        }
        val synthesis = runtime.ledger.synthesize(days)
        val cfg = runtime.ledger.config()
        return buildJsonObject {
            put("buddyEnabled", cfg.enabled)
            put("hapticsEnabled", cfg.hapticsEnabled)
            put("hasUsageAccess", runtime.usage.hasUsageAccess())
            put("personalityTone", cfg.personalityTone)
            put("doomscrollThresholdPerMin", cfg.doomscrollScrollThreshold)
            put("synthesis", synthesis.toJson(cfg.allHaptics()))
        }
    }

    override suspend fun playHaptic(
        patternId: String?,
        timingsMs: List<Long>?,
        amplitudes: List<Int>?,
    ): JsonObject {
        val played = when {
            !patternId.isNullOrBlank() -> runtime.playPattern(patternId)
            !timingsMs.isNullOrEmpty() && !amplitudes.isNullOrEmpty() -> runtime.playCustomWaveform(timingsMs, amplitudes)
            else -> runtime.playPattern("gentle_tap")
        }
        return buildJsonObject {
            put("played", played)
            put("patternId", patternId ?: "custom")
            put("hapticsEnabled", runtime.ledger.config().hapticsEnabled)
            put("hardwareAvailable", runtime.haptics.hasHardware())
        }
    }

    override suspend fun saveHaptic(pattern: HapticPattern): JsonObject {
        val saved = runtime.saveCustomPattern(pattern)
        val previewPlayed = runtime.playPattern(saved.id)
        return buildJsonObject {
            put("saved", true)
            put("id", saved.id)
            put("name", saved.name)
            put("previewPlayed", previewPlayed)
        }
    }

    override suspend fun focusControl(
        action: String,
        goal: String?,
        durationMinutes: Int?,
        distractionPackages: Set<String>?,
        autoBounce: Boolean?,
        doomscrollThreshold: Int?,
        vipKeywords: List<String>?,
        personalityTone: String?,
    ): JsonObject {
        doomscrollThreshold?.let(runtime::setDoomscrollThreshold)
        vipKeywords?.let(runtime::updateVipKeywords)
        personalityTone?.let(runtime::setPersonalityTone)
        return when (action.trim().lowercase()) {
            "start" -> {
                val session = runtime.startFocus(
                    goal = goal ?: "Deep Focus",
                    durationMinutes = durationMinutes ?: 45,
                    distractionPackages = distractionPackages ?: runtime.ledger.config().watchPackages,
                    autoBounce = autoBounce ?: runtime.ledger.config().autoBounceOnFocusDrift,
                    conversationId = conversationId,
                )
                buildJsonObject {
                    put("status", "started")
                    put("id", session.id)
                    put("goal", session.goal)
                    put("endsAtMs", session.endsAtMs)
                    put("autoBounce", session.autoBounce)
                }
            }
            "stop" -> {
                val stopped = runtime.stopFocus(completed = true)
                buildJsonObject {
                    put("status", if (stopped != null) "stopped" else "none_active")
                    if (stopped != null) put("goal", stopped.goal)
                }
            }
            "configure" -> {
                autoBounce?.let(runtime::setAutoBounceDefault)
                val cfg = runtime.ledger.config()
                buildJsonObject {
                    put("status", "configured")
                    put("doomscrollThreshold", cfg.doomscrollScrollThreshold)
                    put("personalityTone", cfg.personalityTone)
                    put("autoBounce", cfg.autoBounceOnFocusDrift)
                }
            }
            else -> {
                val active = runtime.ledger.activeFocus()
                buildJsonObject {
                    put("active", active != null)
                    if (active != null) {
                        put("goal", active.goal)
                        put("remainingMinutes", active.remainingMinutes(System.currentTimeMillis()))
                        put("autoBounce", active.autoBounce)
                        put("nudgesSent", active.nudgesSent)
                    }
                }
            }
        }
    }

    override suspend fun saveInsight(
        category: String,
        summary: String,
        recommendation: String,
        sourceDays: Int,
    ): JsonObject {
        val saved = runtime.saveInsight(category, summary, recommendation, sourceDays)
        return buildJsonObject {
            put("saved", true)
            put("id", saved.id)
            put("category", saved.category)
        }
    }
}

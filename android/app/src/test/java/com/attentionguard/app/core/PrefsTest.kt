package com.attentionguard.app.core

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PrefsTest {
    private val prefs = Prefs(RuntimeEnvironment.getApplication())

    @Test fun opacityCoversEntireRangeAndClampsInvalidValues() {
        prefs.overlayOpacity = 0
        assertEquals(0, prefs.overlayOpacity)
        prefs.overlayOpacity = 50
        assertEquals(50, prefs.overlayOpacity)
        prefs.overlayOpacity = 100
        assertEquals(100, prefs.overlayOpacity)
        prefs.overlayOpacity = 120
        assertEquals(100, prefs.overlayOpacity)
    }

    @Test fun markingCurrentConversationAddsOneTermAndNarrowsAllScope() {
        assertTrue(prefs.whitelist.isEmpty())
        assertEquals(Prefs.RecognitionTermResult.ADDED_FIRST, prefs.addRecognitionTerm(" 课程群 "))
        assertEquals(setOf("课程群"), prefs.whitelist)
        assertFalse(prefs.isAllowed("其他群"))
        assertEquals(Prefs.RecognitionTermResult.EXISTS, prefs.addRecognitionTerm("课程群"))
        assertEquals(Prefs.RecognitionTermResult.ADDED, prefs.addRecognitionTerm("班级群"))
        assertEquals(setOf("课程群", "班级群"), prefs.whitelist)
        assertTrue(prefs.isAllowed("课 程 群（48）"))
        assertTrue(prefs.isAllowed("班级-群"))
    }

    @Test fun titleFormattingAndChangingMemberCountDoNotBreakAConfirmedTerm() {
        assertEquals(Prefs.RecognitionTermResult.ADDED_FIRST, prefs.addRecognitionTerm("学长家教-温州6群(485)四"))
        assertEquals(Prefs.RecognitionTermResult.EXISTS, prefs.addRecognitionTerm("学长家教－温州６群（４８５）四"))
        prefs.whitelist = setOf("课程群(48)")
        assertTrue(prefs.isAllowed("课程群（49）"))
        assertFalse(prefs.isAllowed("别的课程群（49）通知"))
    }

    @Test fun shortAndLatinKeywordsAvoidAccidentalPartialMatches() {
        prefs.whitelist = setOf("AI")
        assertTrue(prefs.isAllowed("AI 课程群"))
        assertFalse(prefs.isAllowed("PAID 课程群"))
        prefs.whitelist = setOf("23网工2")
        assertTrue(prefs.isAllowed("23 网工 2 班"))
        assertFalse(prefs.isAllowed("2023网工2班"))
        prefs.whitelist = setOf("群")
        assertTrue(prefs.isAllowed("群"))
        assertFalse(prefs.isAllowed("课程群"))
    }

    @Test fun invisibleTitleSpacingDoesNotBreakExistingTermsOrDuplicateThem() {
        prefs.addRecognitionTerm("课程群(48)")
        assertTrue(prefs.isAllowed("课\u200B程群（49）\uFEFF"))
        assertEquals(Prefs.RecognitionTermResult.EXISTS, prefs.addRecognitionTerm("\uFEFF课程\u200B群(50)"))
        assertEquals(1, prefs.whitelist.size)
    }

    @Test fun captureModeDefaultsToEventAndPersistsTheExplicitChoice() {
        assertEquals(CaptureMode.EVENT, prefs.captureMode)
        prefs.captureMode = CaptureMode.INTENT
        assertEquals(CaptureMode.INTENT, Prefs(RuntimeEnvironment.getApplication()).captureMode)
        prefs.captureMode = CaptureMode.EVENT
        assertEquals(CaptureMode.EVENT, prefs.captureMode)
    }

    @Test fun overlaySizeSupportsASecondCollapseAndLegacyBoolean() {
        assertEquals(OverlaySize.EXPANDED, prefs.overlaySize)
        prefs.overlayCollapsed = true
        assertEquals(OverlaySize.COMPACT, prefs.overlaySize)
        prefs.overlaySize = OverlaySize.BUBBLE
        assertTrue(prefs.overlayCollapsed)
        assertEquals(OverlaySize.BUBBLE, Prefs(RuntimeEnvironment.getApplication()).overlaySize)
        prefs.overlayCollapsed = false
        assertEquals(OverlaySize.EXPANDED, prefs.overlaySize)
    }

    @Test fun messageKeywordRulesRoundTripAndStaySeparateFromConversationTerms() {
        prefs.whitelist = setOf("23网工2")
        val rules = listOf(
            MessageKeywordRule("训练营", EventCategory.ACTIVITY, EventPriority.P1, "stable-a"),
            MessageKeywordRule(" API ", EventCategory.COURSE, EventPriority.P3, "stable-b", enabled = false)
        )
        prefs.messageKeywordRules = rules
        assertEquals(rules.map { it.copy(keyword = it.keyword.trim()) }, Prefs(RuntimeEnvironment.getApplication()).messageKeywordRules)
        assertEquals(setOf("23网工2"), prefs.whitelist)
    }

    @Test fun normalizedDuplicatesKeepTheFirstRuleAndAnExistingStableId() {
        prefs.messageKeywordRules = listOf(MessageKeywordRule("AI", id = "original"))
        prefs.messageKeywordRules = listOf(
            MessageKeywordRule(" ai ", priority = EventPriority.P1, id = "new-id"),
            MessageKeywordRule("ＡＩ", priority = EventPriority.P0, id = "duplicate")
        )
        val rule = prefs.messageKeywordRules.single()
        assertEquals("original", rule.id)
        assertEquals(EventPriority.P1, rule.priority)
        prefs.messageKeywordRules = listOf(rule.copy(keyword = "API"))
        assertEquals("original", prefs.messageKeywordRules.single().id)
    }

    @Test fun invalidMessageKeywordsDoNotOverwriteSavedRules() {
        val original = listOf(MessageKeywordRule("训练营", id = "saved"))
        prefs.messageKeywordRules = original
        for (rule in listOf(MessageKeywordRule("  "), MessageKeywordRule("x".repeat(81)), MessageKeywordRule("valid", id = ""))) {
            assertThrows(IllegalArgumentException::class.java) { prefs.messageKeywordRules = listOf(rule) }
            assertEquals(original, prefs.messageKeywordRules)
        }
        assertThrows(IllegalArgumentException::class.java) {
            prefs.messageKeywordRules = (1..101).map { MessageKeywordRule("词条$it", id = "id-$it") }
        }
        assertEquals(original, prefs.messageKeywordRules)
    }

    @Test fun missingIdsReadStablyAndMalformedStoredRulesDoNotCrash() {
        val stored = RuntimeEnvironment.getApplication().getSharedPreferences("attention_guard", android.content.Context.MODE_PRIVATE)
        stored.edit().putString("message_keyword_rules", "[{\"keyword\":\"训练营\"}]").commit()
        val first = prefs.messageKeywordRules.single()
        assertEquals(first.id, prefs.messageKeywordRules.single().id)
        assertEquals(EventPriority.P2, first.priority)
        stored.edit().putString("message_keyword_rules", "not-json").commit()
        assertTrue(prefs.messageKeywordRules.isEmpty())
    }
}

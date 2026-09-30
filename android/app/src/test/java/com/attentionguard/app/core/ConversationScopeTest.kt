package com.attentionguard.app.core

import org.junit.Assert.*
import org.junit.Test

class ConversationScopeTest {
    @Test fun formattingSeparatorsRemainWordBoundaries() {
        assertTrue(ConversationScope.matches("项目-TEAM-23", "TEAM"))
        assertTrue(ConversationScope.matches("AI_课程群(48)", "AI"))
        assertFalse(ConversationScope.matches("PAID课程群", "AI"))
        assertFalse(ConversationScope.matches("23网工20", "网工2"))
    }

    @Test fun emptyNormalizedTitlesNeverConfirmAConversation() {
        assertFalse(ConversationScope.equivalent("", ""))
        assertFalse(ConversationScope.equivalent("(48)", "(49)"))
        assertTrue(ConversationScope.equivalent("课程 群（48）", "课程-群(49)"))
    }
}

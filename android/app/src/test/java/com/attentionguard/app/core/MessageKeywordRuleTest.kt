package com.attentionguard.app.core

import org.junit.Assert.*
import org.junit.Test

class MessageKeywordRuleTest {
    @Test fun keywordsAreLiteralNormalizedAndBoundedLatinWords() {
        val ai = MessageKeywordRule("ＡＩ")
        assertTrue(ai.matches("本次 Ai 学习资料在这里"))
        assertTrue(ai.matches("AI学习群资料"))
        assertFalse(ai.matches("PAID 资料"))
        assertFalse(ai.matches("AIs 资料"))
        val punctuation = MessageKeywordRule("a.b[2]")
        assertTrue(punctuation.matches("说明 A.B[2] 已发布"))
        assertFalse(punctuation.matches("说明 axb2 已发布"))
        assertTrue(MessageKeywordRule("a,b[2]").matches("说明 A,B[2] 已发布"))
        assertFalse(ai.copy(enabled = false).matches("AI学习资料"))
    }

    @Test fun negativeFacilitiesFactsAreDifferentFromDenialOfAnEvent() {
        assertTrue(MessageKeywordRule("热水").matches("今晚没有热水"))
        assertTrue(MessageKeywordRule("没电").matches("宿舍没电了"))
        assertTrue(MessageKeywordRule("供水").matches("今晚停止供水"))
        assertFalse(MessageKeywordRule("停电").matches("不是停电，是开关没开"))
        assertFalse(MessageKeywordRule("停水").matches("今晚不会停水"))
        assertFalse(MessageKeywordRule("考试").matches("今天没有考试"))
        assertFalse(MessageKeywordRule("考试").matches("今天无考试安排"))
        assertFalse(MessageKeywordRule("考试").matches("今天不考试"))
        assertTrue(MessageKeywordRule("停电").matches("不是今天停电，是明天计划停电"))
    }

    @Test fun quotedOrReportedWordsNeedAnActualRequestOutsideTheQuote() {
        val rule = MessageKeywordRule("训练营")
        assertFalse(rule.matches("她说“训练营请报名”"))
        assertFalse(rule.matches("“请报名训练营”"))
        assertFalse(rule.matches("这句话“训练营报名”是什么意思？"))
        assertFalse(rule.matches("“训练营”只是闲聊话题，请大家明天提交课程作业"))
        assertTrue(rule.matches("请提交“训练营”的报名材料"))
    }
}

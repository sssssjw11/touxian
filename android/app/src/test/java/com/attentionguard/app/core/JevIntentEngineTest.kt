package com.attentionguard.app.core

import org.junit.Assert.*
import org.junit.Test

class JevIntentEngineTest {
    private fun snapshot(text: String, sender: String = "同学") = ChatSnapshot("课程群",
        listOf(Msg("other", text, sender)), "com.tencent.mm", 1_700_000_000_000L)

    @Test fun actionableMessageProducesTraceableNextStep() {
        val insight = requireNotNull(JevIntentEngine.analyze(snapshot("请大家明天提交作业")))
        assertEquals("可能在提出行动请求", insight.label)
        assertEquals("请大家明天提交作业", insight.evidence)
        assertEquals("同学", insight.sender)
        assertEquals("课程群", insight.group)
        assertEquals(IntentImportance.HIGH, insight.importance)
        assertTrue(insight.confidence in 85..94)
    }

    @Test fun emotionalAndClosingMessagesDoNotBecomeTasks() {
        assertEquals("可能在表达情绪", JevIntentEngine.analyze(snapshot("今天真难过"))?.label)
        assertEquals("话题可能已结束", JevIntentEngine.analyze(snapshot("不用了，已经解决了"))?.label)
        assertEquals("可能在确认关系或关注", JevIntentEngine.analyze(snapshot("你是不是不想理我了"))?.label)
        assertEquals("可能在寻求解释", JevIntentEngine.analyze(snapshot("为什么这样安排？"))?.label)
        assertEquals("可能在寻求解释", JevIntentEngine.analyze(snapshot("请问明天要提交作业吗？"))?.label)
        assertEquals("暂无明确请求", JevIntentEngine.analyze(snapshot("今天见到了老同学"))?.label)
    }

    @Test fun spokenQuestionsDoNotNeedAQuestionMarkOrBecomeActionRequests() {
        listOf("路演要讲多久", "视频需要讲多久", "截止时间是几号", "明天集合几点", "交材料的地点是哪里").forEach {
            assertEquals(it, "可能在寻求解释", JevIntentEngine.analyze(snapshot(it))?.label)
        }
        listOf("没多久就结束了", "已经没多少", "整个流程用不了多久").forEach {
            assertNotEquals(it, "可能在寻求解释", JevIntentEngine.analyze(snapshot(it))?.label)
        }
    }

    @Test fun unsupportedOrUnverifiedTextIsNeverAnalyzed() {
        assertNull(JevIntentEngine.analyze(snapshot("请提交作业").copy(sourcePackage = "com.other.app")))
        assertNull(JevIntentEngine.analyze(snapshot("请提交作业").copy(messages = listOf(Msg("me", "请提交作业")))))
        assertNull(JevIntentEngine.analyze(snapshot("请提交作业").copy(messages = listOf(Msg("other", "请提交作业", captureMethod = "ocr")))))
    }

    @Test fun customModeReadsLatestCounterpartTurnAndMarksManualSource() {
        val insight = requireNotNull(JevIntentEngine.analyzeCustom("我：明天怎么安排？\n对方：请在明天前提交报告\n我：收到"))
        assertEquals("可能在提出行动请求", insight.label)
        assertEquals("请在明天前提交报告", insight.evidence)
        assertEquals("手动输入 · 本地规则", insight.sourceLabel)
        assertTrue(insight.nextStep.contains("你已在其后回复"))
        assertNull(insight.eventId)
    }

    @Test fun customModeSupportsPlainTextAndRejectsSelfOnlyInput() {
        assertEquals("可能在寻求解释", JevIntentEngine.analyzeCustom("为什么改时间？")?.label)
        assertEquals("可能在表达情绪", JevIntentEngine.analyzeCustom("Me: I am ready\nThem: 今天真难过")?.label)
        assertNull(JevIntentEngine.analyzeCustom("我：我已经处理了"))
        assertNull(JevIntentEngine.analyzeCustom("  \n  "))
    }

    @Test fun untitledWeChatUsesVisibleContextWithoutNeedingAGroupName() {
        val current = ChatSnapshot(null, listOf(
            Msg("other", "请帮我提交材料", "同学"),
            Msg("me", "什么时候？"),
            Msg("other", "明天之前可以吗？", "同学")
        ), "com.tencent.mm")
        val insight = requireNotNull(JevIntentEngine.analyze(current))
        assertEquals("可能在跟进先前请求", insight.label)
        assertEquals(IntentImportance.HIGH, insight.importance)
        assertTrue(insight.confidence in 65..85)
        assertEquals("", insight.group)
        assertEquals("对方：请帮我提交材料；我：什么时候？", insight.contextEvidence)
        assertTrue(insight.contextSummary.orEmpty().contains("3 条可读文字"))
    }

    @Test fun customContextCanResolveShortFollowUpWithoutAConversationName() {
        val insight = requireNotNull(JevIntentEngine.analyzeCustom("对方：请帮我提交材料\n我：什么时候？\n对方：明天之前可以吗？"))
        assertEquals("可能在跟进先前请求", insight.label)
        assertEquals(IntentImportance.HIGH, insight.importance)
        assertEquals("", insight.group)
    }

    @Test fun earlierVisibleRequestBeyondFourTurnsStillInformsFollowUp() {
        val current = ChatSnapshot(null, listOf(
            Msg("other", "麻烦帮我提交材料"),
            Msg("me", "知道了"), Msg("other", "还有一份附件"), Msg("me", "收到"),
            Msg("other", "我再检查一下"), Msg("me", "好"),
            Msg("other", "明天之前可以吗？")
        ), "com.tencent.mm")
        val insight = requireNotNull(JevIntentEngine.analyze(current))
        assertEquals("可能在跟进先前请求", insight.label)
        assertEquals(IntentImportance.HIGH, insight.importance)
        assertEquals(7, insight.contextSummary?.substringBefore(" 条")?.toInt())
        assertTrue(insight.contextEvidence.orEmpty().contains("麻烦帮我提交材料"))
    }

    @Test fun laterClosingTurnStopsOldRequestFromDrivingTheContext() {
        val current = ChatSnapshot(null, listOf(
            Msg("other", "请帮我提交材料"), Msg("other", "不用了"),
            Msg("me", "好的"), Msg("other", "明天之前可以吗？")
        ), "com.tencent.mm")
        assertEquals("可能在寻求解释", JevIntentEngine.analyze(current)?.label)
    }

    @Test fun confidenceVariesWithSpecificityWithinTheSameIntent() {
        val weak = requireNotNull(JevIntentEngine.analyze(snapshot("请提交")))
        val specific = requireNotNull(JevIntentEngine.analyze(snapshot("请大家明天17:00前提交课程报告")))
        assertEquals(weak.label, specific.label)
        assertTrue(specific.confidence > weak.confidence)

        val unclear = requireNotNull(JevIntentEngine.analyze(snapshot("嗯")))
        val descriptive = requireNotNull(JevIntentEngine.analyze(snapshot("今天遇见了多年不见的同学，聊了很久")))
        assertEquals(unclear.label, descriptive.label)
        assertTrue(descriptive.confidence > unclear.confidence)
    }

    @Test fun groupMembershipNoticeIsNotARequest() {
        val insight = requireNotNull(JevIntentEngine.analyze(snapshot("\"群成员\"邀请\"新成员\"加入了群聊")))
        assertEquals("微信系统提示", insight.label)
        assertEquals(IntentImportance.LOW, insight.importance)
        assertFalse(insight.contextSummary.orEmpty().contains("行动线索"))
        assertEquals("情绪不明显", insight.affect?.label)
    }

    @Test fun actionConfidenceUsesConcreteTaskEvidence() {
        val weak = requireNotNull(JevIntentEngine.analyze(snapshot("请提交")))
        val task = requireNotNull(JevIntentEngine.analyze(snapshot("请大家提交报告")))
        val timed = requireNotNull(JevIntentEngine.analyze(snapshot("请大家明天17:00前提交报告")))
        assertTrue(weak.confidence < task.confidence)
        assertTrue(task.confidence < timed.confidence)
    }

    @Test fun affectCueDistinguishesCareFrictionAndRoutineWork() {
        assertEquals("表达关切", JevIntentEngine.analyze(snapshot("最近辛苦了，注意休息"))?.affect?.label)
        assertEquals("出现负向情绪", JevIntentEngine.analyze(snapshot("你总是不理我，我有点失望"))?.affect?.label)
        assertEquals("情绪不明显", JevIntentEngine.analyze(snapshot("麻烦大家明天提交作业"))?.affect?.label)
        assertEquals("出现负向情绪", JevIntentEngine.analyze(snapshot("我不喜欢这个安排"))?.affect?.label)
        assertTrue(requireNotNull(JevIntentEngine.analyze(snapshot("请大家明天提交作业"))).confidenceTrace!!.evidence > 70)
        assertTrue(requireNotNull(JevIntentEngine.analyze(snapshot("请提交"))).confidence <
            requireNotNull(JevIntentEngine.analyze(snapshot("请大家明天提交作业"))).confidence)
    }

    @Test fun fullScreenMeetingContextDoesNotCollapseIntoNoRequest() {
        val insight = requireNotNull(JevIntentEngine.analyze(ChatSnapshot(
            "示例班级群(49)",
            listOf(
                Msg("other", "@所有人 今晚七点要开个简短的线上班会，大家不要忘记", "测试班长"),
                Msg("other", "#腾讯会议：000-000-000", "测试班长"),
                Msg("other", "@所有人 等会七点要开班会，请各位同学不要迟到", "测试班长"),
                Msg("other", "@所有人 可以先进会议", "测试班长"),
                Msg("other", "没进的同学，抓紧时间，这个会议定的30分钟", "测试班长")
            ),
            "com.tencent.mm"
        )))
        assertEquals("可能在提出行动请求", insight.label)
        assertEquals(IntentImportance.HIGH, insight.importance)
        assertTrue(insight.confidence >= 70)
        assertTrue(insight.contextSummary.orEmpty().contains("行动线索"))
    }

    @Test fun affectCueRecognizesCommonPersonalChatWording() {
        assertEquals("出现负向情绪", JevIntentEngine.analyze(snapshot("我被欺负了，真的很难受"))?.affect?.label)
        assertEquals("表达关切", JevIntentEngine.analyze(snapshot("别怕，我支持你，我陪你"))?.affect?.label)
    }

    @Test fun affectCueRecognizesPlayfulTeasingAndStrongerContemptSeparately() {
        val teasing = requireNotNull(JevIntentEngine.analyze(snapshot("你个臭乐乐")))
        assertEquals("调侃/玩笑倾向", teasing.affect?.label)
        assertTrue(teasing.affect!!.confidence in 45..75)
        assertTrue(teasing.affect!!.basis.orEmpty().contains("方向不完全确定"))

        val contempt = requireNotNull(JevIntentEngine.analyze(snapshot("滚，别烦我")))
        assertEquals("攻击/蔑视倾向", contempt.affect?.label)
        assertTrue(contempt.affect!!.confidence > teasing.affect!!.confidence)
        assertTrue(contempt.affect!!.basis.orEmpty().contains("攻击或蔑视"))
    }

    @Test fun unnamedStickerIsShownAsASeparateLowConfidenceEmotionSignal() {
        val insight = requireNotNull(JevIntentEngine.analyze(ChatSnapshot(
            "测试联系人", listOf(Msg("other", "[表情:未命名贴纸]", type = MessageType.STICKER)),
            "com.tencent.mm"
        )))
        assertEquals("贴纸情绪线索", insight.affect?.label)
        assertTrue(insight.affect!!.confidence in 30..60)
        assertTrue(insight.affect!!.basis.orEmpty().contains("方向待确认"))
    }

    @Test fun affectCueCanUseTheWholeVisibleContextWithoutClaimingCurrentEmotion() {
        val current = ChatSnapshot(null, listOf(Msg("other", "今天有点难过"), Msg("me", "怎么了？"),
            Msg("other", "嗯")), "com.tencent.mm")
        val affect = requireNotNull(JevIntentEngine.analyze(current)?.affect)
        assertEquals("上文：出现负向情绪", affect.label)
        assertTrue(affect.evidence.contains("整屏上文"))
        assertTrue(affect.confidence < 60)
    }

    @Test fun affectCueUsesVisibleStickerSemanticsWithAConfidenceCaveat() {
        val current = ChatSnapshot(null, listOf(
            Msg("other", "[表情:呜哇]", type = MessageType.STICKER),
            Msg("other", "朋友正在吃饭"),
            Msg("me", "他会支持你的")
        ), "com.tencent.mm")
        val affect = requireNotNull(JevIntentEngine.analyze(current)?.affect)
        assertEquals("上文：出现负向情绪", affect.label)
        assertTrue(affect.evidence.contains("呜哇"))
        assertTrue(affect.confidence < 60)
    }

    @Test fun distantContextLowersFollowUpConfidenceWithoutIgnoringTheScreen() {
        val near = ChatSnapshot(null, listOf(
            Msg("other", "请帮我提交材料"), Msg("me", "什么时候？"), Msg("other", "明天之前可以吗？")
        ), "com.tencent.mm")
        val far = near.copy(messages = near.messages.take(2) + listOf(
            Msg("other", "另外还有一件事"), Msg("me", "什么事"), Msg("other", "先不说"),
            Msg("me", "好"), Msg("other", "明天之前可以吗？")
        ))
        val nearInsight = requireNotNull(JevIntentEngine.analyze(near))
        val farInsight = requireNotNull(JevIntentEngine.analyze(far))
        assertEquals("可能在跟进先前请求", farInsight.label)
        assertTrue(nearInsight.confidence > farInsight.confidence)
    }

    @Test fun nativeWeChatAndUnicodeEmojiAreLowConfidenceExpressionCues() {
        listOf("[发怒]", "😡", "[流泪]", "😭").forEach { text ->
            val affect = requireNotNull(JevIntentEngine.analyze(snapshot(text))?.affect)
            assertEquals(text, "负向表情线索", affect.label)
            assertTrue(text, affect.confidence in 32..58)
            assertTrue(affect.evidence.contains(text))
            assertTrue(affect.basis.orEmpty().contains("不等于真实感受"))
        }
        assertEquals("积极表情线索", JevIntentEngine.analyze(snapshot("[愉快]"))?.affect?.label)
        assertEquals("调侃表情线索", JevIntentEngine.analyze(snapshot("[偷笑]"))?.affect?.label)
    }

    @Test fun repeatedEmojiDoNotManufactureCertaintyButWordsCanCorroborate() {
        val single = requireNotNull(JevIntentEngine.analyze(snapshot("[发怒]"))?.affect)
        val repeated = requireNotNull(JevIntentEngine.analyze(snapshot("[发怒][发怒][发怒]"))?.affect)
        val words = requireNotNull(JevIntentEngine.analyze(snapshot("我很失望，真的很难受[发怒]"))?.affect)
        assertEquals(single.confidence, repeated.confidence)
        assertTrue(words.confidence > single.confidence)
        assertEquals("出现负向情绪", words.label)
    }

    @Test fun ownExpressionAndOtherGroupMembersDoNotBecomeCounterpartEmotion() {
        val own = snapshot("好的").copy(messages = listOf(
            Msg("me", "[发怒]"), Msg("other", "好的", "同学"), Msg("me", "😭")
        ))
        assertEquals("情绪不明显", JevIntentEngine.analyze(own)?.affect?.label)
        val group = own.copy(messages = listOf(Msg("other", "我很难过[流泪]", "甲"),
            Msg("other", "好的", "乙")))
        assertEquals("情绪不明显", JevIntentEngine.analyze(group)?.affect?.label)
    }

    @Test fun emojiPlaceholdersAreNotGuessedAndRequestedActingStaysUncertain() {
        listOf("[文件]", "[动画表情]", "[未知内容]", "[微笑]").forEach {
            assertEquals(it, "情绪不明显", JevIntentEngine.analyze(snapshot(it))?.affect?.label)
        }
        val current = snapshot("[发怒]").copy(messages = listOf(
            Msg("me", "发句生气的话"), Msg("other", "[发怒]", "同学")
        ))
        val affect = requireNotNull(JevIntentEngine.analyze(current)?.affect)
        assertEquals("负向表情线索", affect.label)
        assertTrue(affect.confidence <= 58)
    }

    @Test fun recentEmotionContextOutweighsDistantContextWithoutIgnoringEarlierTurns() {
        val near = snapshot("嗯").copy(messages = listOf(
            Msg("other", "[发怒]", "同学"), Msg("me", "怎么了"), Msg("other", "嗯", "同学")
        ))
        val far = near.copy(messages = near.messages.take(1) + List(6) { Msg("me", "补充说明") } + near.messages.drop(1))
        val nearAffect = requireNotNull(JevIntentEngine.analyze(near)?.affect)
        val farAffect = requireNotNull(JevIntentEngine.analyze(far)?.affect)
        assertEquals("上文：负向表情线索", nearAffect.label)
        assertEquals(nearAffect.label, farAffect.label)
        assertTrue(nearAffect.confidence > farAffect.confidence)
    }
}

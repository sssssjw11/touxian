package com.attentionguard.app.core

/** Shared event model for visible-message extraction, local storage, and the UI. */
enum class EventPriority(val label: String) {
    P0("P0"),
    P1("P1"),
    P2("P2"),
    P3("P3")
}

enum class EventStatus(val label: String) {
    ACTION_REQUIRED("需要行动"),
    MONITORING("持续观测"),
    CONFIRMED("信息已确认"),
    COMPLETED("已完成")
}

enum class EventCategory(val label: String) {
    ACADEMIC_ADMIN("教务 / 行政"),
    COURSE("课程"),
    EMPLOYMENT("就业"),
    COMPETITION("竞赛"),
    MEETING("班会 / 会议"),
    ACTIVITY("活动")
}

data class EventUpdate(
    val time: String,
    val title: String,
    val detail: String,
    val tone: UpdateTone = UpdateTone.NEUTRAL
)

enum class UpdateTone { POSITIVE, WARNING, NEUTRAL }

enum class CaptureOrigin(val label: String) {
    WECHAT_AUTO("微信 · 自动识别"),
    WECHAT_MANUAL("微信 · 手动识别"),
    UNKNOWN("来源待核对")
}

data class AttentionEvent(
    val id: String,
    val title: String,
    val summary: String,
    val sourceGroup: String,
    val sourcePerson: String,
    val priority: EventPriority,
    val status: EventStatus,
    val category: EventCategory,
    val attentionScore: Int,
    val dueLabel: String? = null,
    val actionLabel: String? = null,
    val consequence: String? = null,
    val updatedLabel: String,
    val updates: List<EventUpdate>,
    val evidence: List<String> = emptyList(),
    val reviewNotes: List<String> = emptyList(),
    val previousStatus: EventStatus? = null,
    val analysisSource: String = "本地规则",
    val captureOrigin: CaptureOrigin = CaptureOrigin.UNKNOWN,
    val sourceCapturedAt: Long? = null,
    val archived: Boolean = false
)

data class AttentionStats(
    val observed: Int,
    val actionRequired: Int,
    val dueSoon: Int,
    val completed: Int
)

/** Demo data for the first product slice. Keep it deterministic for screenshots and tests. */
object DemoAttentionData {
    val events: List<AttentionEvent> = listOf(
        AttentionEvent(
            id = "assessment",
            title = "综合测评材料提交",
            summary = "填写综测表，按“学号+姓名”命名后发给班长。",
            sourceGroup = "网络工程 2027 届班级群",
            sourcePerson = "辅导员 / 班长",
            priority = EventPriority.P0,
            status = EventStatus.ACTION_REQUIRED,
            category = EventCategory.ACADEMIC_ADMIN,
            attentionScore = 94,
            dueLabel = "明日 17:00 截止",
            actionLabel = "填写并发送材料",
            consequence = "逾期视为放弃",
            updatedLabel = "11:02 更新",
            updates = listOf(
                EventUpdate("09:31", "发现新事件", "辅导员发布综合测评通知"),
                EventUpdate("09:36", "补充信息", "模板位于群文件"),
                EventUpdate("09:42", "补充截止时间", "明日 17:00 前提交", UpdateTone.WARNING),
                EventUpdate("11:02", "规则确认", "无需打印纸质版", UpdateTone.POSITIVE)
            ),
            evidence = listOf(
                "请大家在周三下午五点前填写综测材料，逾期视为放弃。",
                "文件命名格式：学号+姓名，无需打印，直接发给我。"
            )
        ),
        AttentionEvent(
            id = "network-lab",
            title = "计算机网络实验课调课",
            summary = "明天下午实验课调整教室，请按新地点到课。",
            sourceGroup = "计算机网络课程群",
            sourcePerson = "张老师",
            priority = EventPriority.P1,
            status = EventStatus.CONFIRMED,
            category = EventCategory.COURSE,
            attentionScore = 82,
            dueLabel = "明日 14:00",
            actionLabel = "前往 3-105",
            consequence = "请不要再去原教室 6-302",
            updatedLabel = "10:18 确认",
            updates = listOf(
                EventUpdate("09:12", "发现新事件", "实验课时间不变，教室发生变化"),
                EventUpdate("10:18", "地点确认", "6-302 → 3-105", UpdateTone.WARNING)
            ),
            evidence = listOf("明天下午计网实验课调整至 3-105，请大家相互转告。")
        ),
        AttentionEvent(
            id = "scholarship",
            title = "国家励志奖学金申请",
            summary = "符合条件的同学准备申请表，班级群内等待材料说明。",
            sourceGroup = "计算机与人工智能学院通知群",
            sourcePerson = "学院教务",
            priority = EventPriority.P1,
            status = EventStatus.MONITORING,
            category = EventCategory.ACADEMIC_ADMIN,
            attentionScore = 76,
            dueLabel = "本周五前关注",
            actionLabel = "准备申请材料",
            updatedLabel = "昨天更新",
            updates = listOf(
                EventUpdate("昨日 16:20", "发现新事件", "学院发布申请通知"),
                EventUpdate("昨日 16:34", "等待确认", "申请表和具体时间尚未发布")
            ),
            evidence = listOf("国家励志奖学金申请工作即将开始，请符合条件的同学留意后续通知。")
        ),
        AttentionEvent(
            id = "career-fair",
            title = "秋季双选会报名",
            summary = "本周六线下双选会开放报名，适合提前收藏岗位。",
            sourceGroup = "2027 届就业信息群",
            sourcePerson = "就业中心",
            priority = EventPriority.P2,
            status = EventStatus.MONITORING,
            category = EventCategory.EMPLOYMENT,
            attentionScore = 61,
            dueLabel = "周六 09:00",
            actionLabel = "选择是否报名",
            updatedLabel = "周一更新",
            updates = listOf(
                EventUpdate("周一 13:08", "发现新事件", "秋季双选会开放报名"),
                EventUpdate("周一 13:10", "低风险提醒", "距离活动还有 4 天")
            ),
            evidence = listOf("秋季校园双选会将于本周六举办，报名入口见群公告。")
        )
    )

}

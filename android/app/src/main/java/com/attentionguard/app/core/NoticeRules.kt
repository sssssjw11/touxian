package com.attentionguard.app.core

/** Shared notice cues for the event and intent readers. */
internal object NoticeRules {
    val meeting = Regex("班会|开会|会议(?!室)|入会")
    val topic = Regex("通知|报名|提交|填写|填报|收集|申请|讲座|宣讲|招聘|调课|教室|作业|考试|重修|学分|会议|班会|综测|奖学金|材料|表格|医保")
    private val verb = Regex("提交|填写|填报|报名|参加|完成|回复|确认|上传|下载|发我|发给|交给|到场|到课|登记|预约|交作业|交材料|领取|申请|投递|转发|冲抵")
    private val directive = Regex("(?<!邀|申)请(?!问|假|教)|须|务必|需要|记得|尽快|抓紧|统一|截止|最晚|之前|前提交|报名时间|报名登记|开放|要用")
    private val meetingRequest = Regex("开.{0,10}(班会|会议)|开会|参加.{0,8}(班会|会议)|进(入)?会议|先进会议|入会|不要迟到|准时.{0,8}(班会|会议)")
    private val question = Regex("^(请问|是否|怎么|能否|什么时候)|[吗么][？?]?$")
    private val status = Regex("已发布|已开放|开放了|调整|改为|不体现|未显示|没导入|未导入|莫急|等待|名单|结果|宣讲时间|会议时间|开始时间|会议号|腾讯会议")
    private val cancellation = Regex("取消|作废|(?:无需|不用|不必)(?:再)?(?:提交|报名|参加|填写|开会|处理)|^(?:不用了|无需处理)")
    private val exception = Regex("已.{0,20}(申请|填写|提交|报名|完成).{0,20}(不用|无需|不必)|(?:不用|无需|不必).{0,5}(重复|再次|打印|回复|纸质)|取消.{0,5}(勾选|选中)")
    private val clauses = Regex("[。！!？?；;\\n，,]")
    private val mention = Regex("@[^\\s\\p{Z}]+[\\s\\p{Z}]*")
    private val acknowledgement = Regex("^(收到|好的|好滴|好|嗯|谢谢|已阅|了解|没问题)[。！!、，,\\s]*$")
    private val system = Regex("加入了群聊|邀请.{1,80}加入|撤回了一条消息|拍了拍|修改群名为")

    fun content(text: String) = text.replace(Regex("@(?:所有人|所有成员|全体成员|全体)"), "")
        .replace(mention, "").trim()

    fun isNoise(text: String) = content(text).let {
        it.isBlank() || it in setOf("[图片]", "[文件]", "[语音]", "[视频]") ||
            acknowledgement.matches(it) || system.containsMatchIn(it) ||
            Regex("^(请)?(继续)?(关注|留意|等待)后续(通知|安排)[。！!]*$").matches(it)
    }

    fun hasAction(text: String): Boolean {
        val value = content(text)
        if (question.containsMatchIn(value) || system.containsMatchIn(value)) return false
        return value.split(clauses).any { clause ->
            clause.isNotBlank() && !cancellation.containsMatchIn(clause) &&
                !(Regex("已(经)?(提交|完成|报名|回复)|完成搬迁").containsMatchIn(clause) &&
                    !directive.containsMatchIn(clause)) &&
                (meetingRequest.containsMatchIn(clause) ||
                    (verb.containsMatchIn(clause) && directive.containsMatchIn(clause)) ||
                    (meeting.containsMatchIn(clause) && Regex("不要忘记|抓紧时间|今晚|明天|今天").containsMatchIn(clause)))
        }
    }

    fun isCancelled(text: String): Boolean {
        val value = content(text)
        if (question.containsMatchIn(value)) return false
        return value.split(clauses).any {
            cancellation.containsMatchIn(it) && !exception.containsMatchIn(it)
        } && !hasAction(value)
    }

    fun isInformation(text: String): Boolean {
        val value = content(text)
        return !question.containsMatchIn(value) && topic.containsMatchIn(value) &&
            (status.containsMatchIn(value) || Regex("宣讲|招聘|双选会").containsMatchIn(value))
    }
}

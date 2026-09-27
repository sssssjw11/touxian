import com.attentionguard.app.core.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Runs against the installed APK with app_process; no UI, storage or network access. */
public final class DeviceRuleCheck {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);
    private static final long NOW = DAY.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    private static int checks;

    private static Msg message(String text) {
        return new Msg("other", text, "班委", null, MessageType.TEXT, List.of(), DAY.toString(), null, "nodes");
    }

    private static ChatSnapshot snapshot(String... text) {
        List<Msg> messages = new ArrayList<>();
        for (String value : text) messages.add(message(value));
        return new ChatSnapshot("验收群", messages, "com.tencent.mm", NOW);
    }

    private static List<AttentionEvent> events(ChatSnapshot snapshot) {
        return AttentionEngine.INSTANCE.buildEvents(snapshot, "", CaptureOrigin.WECHAT_AUTO);
    }

    private static void check(boolean result, String name) {
        if (!result) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }

    public static void main(String[] args) {
        ChatSnapshot meeting = snapshot(
            "@所有人\u2005今晚七点要开个简短的线上班会，大家不要忘记",
            "#腾讯会议：123-456-789",
            "@所有人 等会七点要开班会，请各位同学不要迟到",
            "@所有人 可以先进会议",
            "没进的同学，抓紧时间，这个会议定的30分钟"
        );
        List<AttentionEvent> found = events(meeting);
        check(found.size() == 1, "meeting_context_one_event");
        AttentionEvent event = found.get(0);
        check(event.getCategory() == EventCategory.MEETING && event.getPriority() == EventPriority.P0
            && "2026-09-26 19:00".equals(event.getDueLabel()) && event.getEvidence().size() == 5,
            "meeting_type_priority_time_evidence");
        IntentInsight insight = JevIntentEngine.INSTANCE.analyze(meeting);
        check(insight != null && insight.getImportance() == IntentImportance.HIGH
            && insight.getConfidence() >= 70, "meeting_intent");

        ChatSnapshot past = new ChatSnapshot(meeting.getTitle(), meeting.getMessages(), "com.tencent.mm", NOW + 86_400_000L);
        AttentionEvent expired = events(past).get(0);
        check(expired.getPriority() == EventPriority.P2 && expired.getStatus() == EventStatus.MONITORING,
            "expired_meeting_not_urgent");

        AttentionEvent info = events(snapshot("@所有人 学分数据尚未导入教务系统，大家莫急")).get(0);
        check(info.getCategory() == EventCategory.ACADEMIC_ADMIN && info.getStatus() == EventStatus.MONITORING,
            "useful_information_without_mandatory_action");

        AttentionEvent exemption = events(snapshot(
            "还未申请综测加分的同学请填写汇总表。截止时间：今天下午13:30。已自行申请的同学不用重复填写。"
        )).get(0);
        check(exemption.getStatus() == EventStatus.ACTION_REQUIRED
            && "2026-09-26 13:30".equals(exemption.getDueLabel()), "conditional_exemption_not_cancellation");

        check(events(snapshot("今晚七点开线上班会", "请在明天17:00前提交实验报告")).size() == 2,
            "independent_notices_preserved");
        check(events(snapshot("收到", "@所有人", "今天晚饭吃什么？")).isEmpty(), "noise_not_events");

        IntentInsight weak = JevIntentEngine.INSTANCE.analyze(snapshot("请提交"));
        IntentInsight specific = JevIntentEngine.INSTANCE.analyze(snapshot("请大家明天17:00前提交课程报告"));
        check(weak != null && specific != null && weak.getConfidence() < specific.getConfidence(),
            "confidence_changes_with_evidence");
        System.out.println("DEVICE_RULE_CHECK_OK " + checks + " checks; weak=" + weak.getConfidence()
            + " specific=" + specific.getConfidence() + " meeting=" + insight.getConfidence());
    }
}

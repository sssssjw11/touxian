import com.attentionguard.app.core.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Focused 1.20 regressions against the installed APK; never reads or writes user data. */
public final class DeviceAcceptance120 {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 27);
    private static final long NOW = DAY.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    private static int passed;
    private static int failed;

    private static ChatSnapshot snapshot(String title, String... texts) {
        List<Msg> messages = new ArrayList<>();
        for (String text : texts) {
            messages.add(new Msg("other", text, "测试发送者", null, MessageType.TEXT,
                List.of(), DAY.toString(), null, "nodes"));
        }
        return new ChatSnapshot(title, messages, "com.tencent.mm", NOW);
    }

    private static List<AttentionEvent> events(String title, String... texts) {
        return AttentionEngine.INSTANCE.buildEvents(snapshot(title, texts), "", CaptureOrigin.WECHAT_AUTO);
    }

    private static void check(boolean condition, String name, String actual) {
        if (condition) passed++; else failed++;
        System.out.println((condition ? "PASS " : "FAIL ") + name + " actual=" + actual);
    }

    public static void main(String[] args) {
        List<AttentionEvent> ranking = events("个人聊天",
            "是不是不满足啊", "那我是不是还有机会", "可惜", "发现下面那个排名相加比我高");
        check(ranking.isEmpty(), "ranking_discussion_not_event", "count=" + ranking.size());

        List<AttentionEvent> travel = events("个人聊天",
            "你上去一个", "可爱不", "不方便到回来啊", "要跑到上海去", "我这次回去就带个小行李箱");
        check(travel.isEmpty(), "casual_product_and_travel_not_event", "count=" + travel.size());

        List<AttentionEvent> video = events("校园黑客松",
            "视频提交时间28号下午5点截止，提交到指定邮箱");
        AttentionEvent item = video.isEmpty() ? null : video.get(0);
        check(video.size() == 1, "video_submission_detected", "count=" + video.size());
        check(item != null && item.getCategory() == EventCategory.COMPETITION,
            "video_category_uses_relevant_group", item == null ? "missing" : item.getCategory().name());
        check(item != null && "2026-09-28 17:00".equals(item.getDueLabel()),
            "video_deadline", item == null ? "missing" : item.getDueLabel());
        check(item != null && item.getCaptureOrigin() == CaptureOrigin.WECHAT_AUTO
                && "校园黑客松".equals(item.getSourceGroup()) && item.getSourceCapturedAt() == NOW,
            "source_metadata_retained", item == null ? "missing" : item.getCaptureOrigin().name());

        IntentInsight question = JevIntentEngine.INSTANCE.analyze(snapshot("测试群", "路演要讲多久"));
        check(question != null && "可能在寻求解释".equals(question.getLabel()),
            "spoken_question_without_question_mark",
            question == null ? "missing" : question.getLabel() + ";confidence=" + question.getConfidence());

        List<AttentionEvent> coursework = events("校园黑客松", "请大家明天17:00前提交课程作业");
        check(coursework.size() == 1 && coursework.get(0).getCategory() == EventCategory.COURSE,
            "explicit_course_content_beats_group_name",
            coursework.isEmpty() ? "missing" : coursework.get(0).getCategory().name());

        DeadlineParser.Result unknownDay = DeadlineParser.INSTANCE.parse(
            "视频提交时间28号下午5点截止", null, DAY);
        check(unknownDay.getAt() == null, "unknown_message_month_requires_review", unknownDay.getLabel());

        DeadlineParser.Result pastDay = DeadlineParser.INSTANCE.parse(
            "视频25号下午5点就已经截止了", DAY, DAY);
        check(pastDay.getAt() == null || !pastDay.getAt().toLocalDate().isAfter(DAY),
            "explicitly_past_deadline_not_rolled_to_future", pastDay.getLabel());

        long dayMillis = 86_400_000L;
        check(EventDateFilter.LAST_7_DAYS.matches(NOW - 2 * dayMillis, NOW)
                && !EventDateFilter.LAST_7_DAYS.matches(NOW - 8 * dayMillis, NOW),
            "last_week_excludes_eight_days_ago", "2 days included; 8 days excluded");
        check(EventPriorityFilter.P0.matches(EventPriority.P0)
                && !EventPriorityFilter.P0.matches(EventPriority.P1),
            "priority_filter", "P0 only");

        System.out.println("DEVICE_ACCEPTANCE_120 passed=" + passed + " failed=" + failed);
        if (failed > 0) throw new AssertionError(failed + " focused regressions failed");
    }
}

import com.attentionguard.app.core.*;
import java.util.List;

/** Runs pure rules from the installed APK, without reading or writing chat storage. */
public final class DeviceAcceptance122 {
    private static int passed;

    private static Msg msg(String side, String text, String sender) {
        return new Msg(side, text, sender, null, MessageType.TEXT,
            List.of(), null, null, "nodes");
    }

    private static AffectInsight affect(Msg... messages) {
        IntentInsight insight = JevIntentEngine.INSTANCE.analyze(
            new ChatSnapshot(null, List.of(messages), "com.tencent.mm", 0L));
        if (insight == null || insight.getAffect() == null) throw new AssertionError("missing affect");
        return insight.getAffect();
    }

    private static void check(boolean condition, String name, AffectInsight actual) {
        if (!condition) throw new AssertionError(name + ": " + actual);
        passed++;
        System.out.println("PASS " + name + " label=" + actual.getLabel() +
            ";confidence=" + actual.getConfidence());
    }

    public static void main(String[] args) {
        DeviceAcceptance120.main(args);
        AffectInsight angry = affect(msg("other", "[发怒]", "测试发送者"));
        check("负向表情线索".equals(angry.getLabel()) && angry.getConfidence() <= 58,
            "native_angry_emoji", angry);
        AffectInsight unicode = affect(msg("other", "😡", "测试发送者"));
        check("负向表情线索".equals(unicode.getLabel()), "unicode_angry_emoji", unicode);
        AffectInsight repeated = affect(msg("other", "[发怒][发怒][发怒]", "测试发送者"));
        check(repeated.getConfidence() == angry.getConfidence(), "repetition_does_not_inflate", repeated);
        AffectInsight words = affect(msg("other", "我很失望，真的很难受[发怒]", "测试发送者"));
        check(words.getConfidence() > angry.getConfidence(), "words_corroborate_expression", words);
        AffectInsight own = affect(msg("me", "[发怒]", null), msg("other", "好的", "测试发送者"));
        check("情绪不明显".equals(own.getLabel()), "own_expression_not_attributed", own);
        AffectInsight otherMember = affect(msg("other", "很难过[流泪]", "甲"), msg("other", "好的", "乙"));
        check("情绪不明显".equals(otherMember.getLabel()), "other_member_not_attributed", otherMember);
        AffectInsight unknown = affect(msg("other", "[动画表情]", "测试发送者"));
        check("情绪不明显".equals(unknown.getLabel()), "unknown_expression_not_guessed", unknown);
        System.out.println("DEVICE_ACCEPTANCE_122 passed=" + passed + " failed=0 (plus 1.20 regressions)");
    }
}

package com.fanzuixianchang.ai;

import com.fanzuixianchang.core.Problem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 周复盘的输出校验——整个功能的信任基础。
 *
 * 为什么需要它：prompt 里写「不许编数字」只是口头约定，模型偶尔就是会编。
 * 一旦编了，用户看到的就是假的营养结论，这比没有分析更糟。
 * 所以这里把「不许编」变成可执行检查：输出里的每个数字都必须在输入里出现过。
 *
 * 校验不过 → 整份拒收（INVALID_MODEL_OUTPUT）→ 客户端降级到规则文案。
 * 用户永远看不到编造的数字。
 */
public final class WeekReviewValidator {
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern BANNED = Pattern.compile("确诊|治愈|治疗|保证|一定能|100\\s*%|必须|应该|超标|罪过|太胖");
    private static final Set<String> KINDS = Set.of("breakfast", "lunch", "dinner", "unknown", "coverage", "portion", "energy");

    private static final int MAX_HEADLINE = 25;
    private static final int MAX_READING = 60;
    private static final int MAX_TITLE = 20;
    private static final int MAX_WHY = 50;
    private static final int MAX_HOW = 40;
    private static final int MAX_FOLLOW = 40;
    private static final int OBSERVATIONS = 3;

    private WeekReviewValidator() {}

    /** facts 的形状校验：挡住畸形输入，也避免把离谱数值喂进 prompt。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> validFacts(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) throw Problem.input("复盘数据格式有误。");
        var facts = (Map<String, Object>) map;
        var period = facts.get("period");
        if (!(period instanceof Map<?, ?> p) || p.get("start") == null || p.get("end") == null) {
            throw Problem.input("复盘缺少统计周期。");
        }
        var coverage = facts.get("coverage");
        if (!(coverage instanceof Map<?, ?> c)) throw Problem.input("复盘缺少覆盖统计。");
        checkInt(c.get("recordedDays"), 0, 7, "记录天数");
        checkInt(c.get("completeDays"), 0, 7, "记全天数");
        checkInt(c.get("unknownCount"), 0, 500, "存疑条数");
        checkInt(c.get("meals"), 0, 200, "餐数");

        var energy = facts.get("energy");
        if (energy instanceof Map<?, ?> e) {
            checkNumber(e.get("avg"), 0, 20000, "平均能量");
            checkNumber(e.get("stdDev"), 0, 20000, "能量波动");
            checkNumber(e.get("weekdayAvg"), 0, 20000, "工作日均值");
            checkNumber(e.get("weekendAvg"), 0, 20000, "周末均值");
            checkNumber(e.get("goal"), 0, 20000, "参考额度");
        }
        var quality = facts.get("quality");
        if (quality instanceof Map<?, ?> q) {
            checkNumber(q.get("estimatedRatio"), 0, 1, "估算占比");
        }
        var repeats = facts.get("topRepeats");
        if (repeats instanceof List<?> list && list.size() > 10) throw Problem.input("重复食物条目过多。");
        return facts;
    }

    private static void checkInt(Object value, int min, int max, String label) {
        if (value == null) return;
        if (!(value instanceof Number n) || n.intValue() < min || n.intValue() > max) {
            throw Problem.input("复盘数据有误：" + label + "超出范围。");
        }
    }

    private static void checkNumber(Object value, double min, double max, String label) {
        if (value == null) return;
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() < min || n.doubleValue() > max) {
            throw Problem.input("复盘数据有误：" + label + "超出范围。");
        }
    }

    /**
     * 校验并规整模型输出。
     * facts 用于建白名单：键路径集合 + 数字集合。
     */
    static Map<String, Object> apply(Map<String, Object> output, Map<String, Object> facts) {
        if (output == null) throw invalid();
        var keys = new HashSet<String>();
        collectKeys(facts, "", keys);
        var numbers = new HashSet<Long>();
        collectNumbers(facts, numbers);

        var headline = text(output.get("headline"), MAX_HEADLINE, "判词");
        var observations = observations(output.get("observations"), keys);
        var oneThing = oneThing(output.get("oneThing"));
        var followUpNote = output.get("followUpNote") == null ? "" : text(output.get("followUpNote"), MAX_FOLLOW, "上周回顾", true);

        // 数字白名单：所有正文里的数字都必须在 facts 里出现过。
        var written = new ArrayList<String>();
        written.add(headline);
        written.add(followUpNote);
        for (var row : observations) written.add((String) row.get("reading"));
        written.add((String) oneThing.get("title"));
        written.add((String) oneThing.get("why"));
        written.add((String) oneThing.get("how"));
        for (var sentence : written) checkNumbers(sentence, numbers);

        var banned = new StringBuilder();
        banned.append(headline).append(followUpNote);
        for (var row : observations) banned.append(row.get("reading"));
        banned.append(oneThing.get("title")).append(oneThing.get("why")).append(oneThing.get("how"));
        var matcher = BANNED.matcher(banned);
        if (matcher.find()) throw invalid("措辞越界：" + matcher.group());

        var result = new LinkedHashMap<String, Object>();
        result.put("headline", headline);
        result.put("observations", observations);
        result.put("oneThing", oneThing);
        result.put("followUpNote", followUpNote);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> observations(Object raw, Set<String> keys) {
        if (!(raw instanceof List<?> list) || list.size() != OBSERVATIONS) {
            throw invalid("线索需恰好 " + OBSERVATIONS + " 条");
        }
        var out = new ArrayList<Map<String, Object>>();
        for (var item : list) {
            if (!(item instanceof Map<?, ?> row)) throw invalid();
            var factKey = row.get("factKey");
            if (!(factKey instanceof String key) || !keys.contains(key)) {
                // 引用不存在的键 = 编造了一条事实
                throw invalid("引用了不存在的事实：" + factKey);
            }
            var reading = text(row.get("reading"), MAX_READING, "解读");
            var entry = new LinkedHashMap<String, Object>();
            entry.put("factKey", key);
            entry.put("reading", reading);
            out.add(entry);
        }
        return out;
    }

    private static Map<String, Object> oneThing(Object raw) {
        if (!(raw instanceof Map<?, ?> row)) throw invalid();
        var kind = row.get("kind");
        if (!(kind instanceof String k) || !KINDS.contains(k)) throw invalid("行动类型不合法");
        var out = new LinkedHashMap<String, Object>();
        out.put("kind", k);
        out.put("title", text(row.get("title"), MAX_TITLE, "行动标题"));
        out.put("why", text(row.get("why"), MAX_WHY, "行动理由"));
        out.put("how", text(row.get("how"), MAX_HOW, "行动做法"));
        return out;
    }

    private static String text(Object raw, int limit, String label) {
        return text(raw, limit, label, false);
    }

    private static String text(Object raw, int limit, String label, boolean allowEmpty) {
        if (!(raw instanceof String value)) throw invalid(label + "缺失");
        var trimmed = value.trim();
        if (trimmed.isEmpty()) {
            if (allowEmpty) return "";
            throw invalid(label + "为空");
        }
        if (trimmed.length() > limit) throw invalid(label + "超过 " + limit + " 字");
        if (trimmed.chars().anyMatch(c -> c == '<' || c == '>' || Character.isISOControl(c))) {
            throw invalid(label + "含非法字符");
        }
        return trimmed;
    }

    /**
     * 数字白名单。
     * 1–7 放行：它们是「一周七天」的天然计数，模型常用来描述天数，
     * 宽松处理避免大量误拒。8 以上必须是 facts 里真实存在的数。
     */
    private static void checkNumbers(String sentence, Set<Long> allowed) {
        Matcher matcher = NUMBER.matcher(sentence);
        while (matcher.find()) {
            long value = Math.round(Double.parseDouble(matcher.group()));
            if (value >= 1 && value <= 7) continue;
            if (!allowed.contains(value)) {
                throw invalid("出现数据中不存在的数字：" + matcher.group());
            }
        }
    }

    private static void collectKeys(Object node, String prefix, Set<String> out) {
        if (node instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                var path = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
                out.add(path);
                collectKeys(entry.getValue(), path, out);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) collectKeys(list.get(i), prefix + "[" + i + "]", out);
        }
    }

    private static void collectNumbers(Object node, Set<Long> out) {
        if (node instanceof Number n && Double.isFinite(n.doubleValue())) {
            out.add(Math.round(n.doubleValue()));
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (var value : map.values()) collectNumbers(value, out);
            return;
        }
        if (node instanceof List<?> list) {
            for (var value : list) collectNumbers(value, out);
        }
    }

    private static Problem invalid() {
        return Problem.output();
    }

    private static Problem invalid(String detail) {
        return new Problem(502, "INVALID_MODEL_OUTPUT", "这份复盘没通过核对（" + detail + "），已保留上一版。");
    }
}

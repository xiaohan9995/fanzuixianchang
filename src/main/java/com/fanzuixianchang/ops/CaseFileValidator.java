package com.fanzuixianchang.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanzuixianchang.core.Problem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 案卷内容校验。与小程序端 lib/case-schema.js 是同一套规则的两份实现。
 *
 * 为什么两边各写一遍而不是只留一边：
 * 服务端是「把关」，必须挡住脏包上线；客户端是「防御」，防的是服务端被绕过或
 * 缓存里混进历史脏数据。两边都拦，任何一边失效都不会把页面写坏。
 * 改规则时两边必须同改，测试各自断言同一批用例。
 *
 * 策略照 content/starters 的全量拒收：任一条不合格整包退回，不做部分采纳。
 * 内容宁可停在旧版，也不能让半包脏数据上线。
 */
@Component
public final class CaseFileValidator {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_PANEL_COUNT = 40;
    public static final int MAX_CASE_BYTES = 200 * 1024;

    private static final Set<String> SOURCES = Set.of("H01", "H02");
    private static final Set<String> PANEL_KINDS = Set.of("text", "image");
    private static final Pattern CASE_ID = Pattern.compile("^a-\\d{3}$");
    private static final Pattern CASE_CODE = Pattern.compile("^A-\\d{3}$");
    private static final Pattern PAGE_URL = Pattern.compile("^/pages/[A-Za-z0-9_-]+/index(\\?[^\\s<>]*)?$");
    /** 健康类文案红线：不诊断、不承诺、不把估算说成测量。 */
    private static final Pattern BANNED = Pattern.compile("确诊|治愈|治疗|保证|一定能|百分之百|100\\s*%|包治");
    /** 内容要能安全嵌进 WXML，不做二次转义，所以尖括号与控制字符一律不收。 */
    private static final Pattern UNSAFE = Pattern.compile("[\\u0000-\\u001f<>]");

    public record Problem2(String path, String message) {}

    private final ObjectMapper mapper;

    public CaseFileValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 校验整包，返回问题清单；空表示通过。 */
    public List<Problem2> check(JsonNode bundle) {
        var problems = new ArrayList<Problem2>();
        if (bundle == null || !bundle.isObject()) {
            problems.add(new Problem2("bundle", "内容需为对象"));
            return problems;
        }
        var version = bundle.path("schemaVersion");
        if (!version.isInt() || version.asInt() != SCHEMA_VERSION) {
            problems.add(new Problem2("schemaVersion",
                    "客户端支持 " + SCHEMA_VERSION + "，内容为 " + version + "，请升级一端后再发布"));
            return problems;
        }
        var cases = bundle.path("cases");
        if (!cases.isArray() || cases.isEmpty()) {
            problems.add(new Problem2("cases", "至少要有一案"));
            return problems;
        }

        var ids = new HashSet<String>();
        var codes = new HashSet<String>();
        int lastCode = 0;
        for (int i = 0; i < cases.size(); i++) {
            var item = cases.get(i);
            String at = "cases[" + i + "]";
            checkCase(item, at, problems);

            if (item.isObject()) {
                var id = item.path("id").asText("");
                if (!id.isEmpty() && !ids.add(id)) problems.add(new Problem2(at + ".id", "案 id 重复"));
                var code = item.path("code").asText("");
                if (!code.isEmpty() && !codes.add(code)) problems.add(new Problem2(at + ".code", "案号重复"));
                // 页面靠数组顺序取「下一案」，所以倒序必须拦在发布前。
                if (CASE_CODE.matcher(code).matches()) {
                    int n = Integer.parseInt(code.substring(2));
                    if (n <= lastCode) problems.add(new Problem2(at + ".code", "案号需按顺序递增"));
                    lastCode = n;
                }
            }
            scanBanned(item, at, problems);
        }
        return problems;
    }

    /** 校验并抛出：给 Store 用，脏包直接 400，绝不落盘。 */
    public void require(JsonNode bundle) {
        var problems = check(bundle);
        if (problems.isEmpty()) return;
        var first = problems.get(0);
        throw new Problem(400, "INVALID_INPUT",
                "内容未通过校验（" + first.path() + "：" + first.message() + "），共 " + problems.size() + " 处问题，原版本未改变。");
    }

    private void checkCase(JsonNode item, String at, List<Problem2> problems) {
        if (item == null || !item.isObject()) {
            problems.add(new Problem2(at, "不是对象"));
            return;
        }
        for (var key : List.of("id", "code", "title", "hook", "meta", "panels", "evidence",
                "redHerring", "verdict", "lesson", "quote", "sourceIds", "action", "next")) {
            if (item.path(key).isMissingNode() || item.path(key).isNull()) {
                problems.add(new Problem2(at + "." + key, "缺少字段"));
            }
        }
        if (!problems.isEmpty() && !item.path("id").isTextual()) return;

        if (!CASE_ID.matcher(item.path("id").asText("")).matches()) problems.add(new Problem2(at + ".id", "形如 a-001"));
        if (!CASE_CODE.matcher(item.path("code").asText("")).matches()) problems.add(new Problem2(at + ".code", "形如 A-001"));
        text(item.path("title"), 40, at + ".title", "案名 1—40 字", problems);
        text(item.path("hook"), 120, at + ".hook", "钩子 1—120 字", problems);
        text(item.path("quote"), 80, at + ".quote", "收尾句 1—80 字", problems);

        var panels = item.path("panels");
        if (!panels.isArray() || panels.isEmpty() || panels.size() > MAX_PANEL_COUNT) {
            problems.add(new Problem2(at + ".panels", "分格 1—" + MAX_PANEL_COUNT + " 个"));
        } else {
            for (int i = 0; i < panels.size(); i++) checkPanel(panels.get(i), at + ".panels[" + i + "]", problems);
        }

        var verdict = item.path("verdict");
        if (!verdict.isObject()) {
            problems.add(new Problem2(at + ".verdict", "结案需有标题与正文"));
        } else {
            text(verdict.path("title"), 20, at + ".verdict.title", "结案标题 1—20 字", problems);
            text(verdict.path("text"), 600, at + ".verdict.text", "结案正文 1—600 字", problems);
        }

        var lesson = item.path("lesson");
        if (!lesson.isObject()) {
            problems.add(new Problem2(at + ".lesson", "规矩需 2—3 条"));
        } else {
            text(lesson.path("title"), 20, at + ".lesson.title", "小标题 1—20 字", problems);
            var points = lesson.path("points");
            if (!points.isArray() || points.size() < 2 || points.size() > 3) {
                problems.add(new Problem2(at + ".lesson.points", "规矩需 2—3 条"));
            } else {
                for (int i = 0; i < points.size(); i++) {
                    text(points.get(i), 80, at + ".lesson.points[" + i + "]", "每条规矩 1—80 字", problems);
                }
            }
        }

        var red = item.path("redHerring");
        if (!red.isObject()) {
            problems.add(new Problem2(at + ".redHerring", "错路需有主张、验法与结论"));
        } else {
            text(red.path("claim"), 200, at + ".redHerring.claim", "主张 1—200 字", problems);
            text(red.path("check"), 300, at + ".redHerring.check", "验法 1—300 字", problems);
            text(red.path("result"), 400, at + ".redHerring.result", "结论 1—400 字", problems);
        }

        var sources = item.path("sourceIds");
        if (!sources.isArray() || sources.isEmpty()) {
            problems.add(new Problem2(at + ".sourceIds", "至少引用一条依据"));
        } else {
            for (var id : sources) {
                if (!SOURCES.contains(id.asText(""))) {
                    problems.add(new Problem2(at + ".sourceIds", "依据 " + id.asText("") + " 不存在"));
                }
            }
        }

        var action = item.path("action");
        if (!action.isObject()) {
            problems.add(new Problem2(at + ".action", "需要一个页面跳转与按钮文案"));
        } else {
            text(action.path("label"), 30, at + ".action.label", "按钮文案 1—30 字", problems);
            if (!PAGE_URL.matcher(action.path("url").asText("")).matches()) {
                problems.add(new Problem2(at + ".action.url", "只能指向站内页面"));
            }
        }

        var next = item.path("next");
        if (!next.isObject() || !CASE_CODE.matcher(next.path("code").asText("")).matches()) {
            problems.add(new Problem2(at + ".next", "下一案预告需有案号与案名"));
        } else {
            text(next.path("title"), 40, at + ".next.title", "下一案名 1—40 字", problems);
        }

        // 体积：单案超过 200KB 多半是误传大文件，会拖慢首屏。
        try {
            if (mapper.writeValueAsBytes(item).length > MAX_CASE_BYTES) {
                problems.add(new Problem2(at, "单案内容超过 200KB"));
            }
        } catch (Exception e) {
            problems.add(new Problem2(at, "无法序列化"));
        }
    }

    private void checkPanel(JsonNode panel, String at, List<Problem2> problems) {
        if (panel == null || !panel.isObject()) {
            problems.add(new Problem2(at, "不是对象"));
            return;
        }
        var kind = panel.path("kind").asText("");
        if (!PANEL_KINDS.contains(kind)) {
            problems.add(new Problem2(at + ".kind", "只能是 text 或 image"));
            return;
        }
        if ("image".equals(kind)) {
            text(panel.path("src"), 500, at + ".src", "图片地址不能为空", problems);
            text(panel.path("alt"), 200, at + ".alt", "图片必须有无障碍描述", problems);
            return;
        }
        text(panel.path("no"), 8, at + ".no", "序号不能为空", problems);
        text(panel.path("title"), 60, at + ".title", "小节标题 1—60 字", problems);
        text(panel.path("body"), 800, at + ".body", "正文 1—800 字", problems);
    }

    private static void text(JsonNode node, int limit, String path, String message, List<Problem2> problems) {
        if (!node.isTextual()) {
            problems.add(new Problem2(path, message));
            return;
        }
        var value = node.asText().trim();
        if (value.isEmpty() || value.length() > limit || UNSAFE.matcher(value).find()) {
            problems.add(new Problem2(path, message));
        }
    }

    /** 文案红线：递归扫所有字符串，命中禁用表述即整包退回。 */
    private static void scanBanned(JsonNode node, String path, List<Problem2> problems) {
        if (node == null) return;
        if (node.isTextual()) {
            var matcher = BANNED.matcher(node.asText());
            if (matcher.find()) {
                problems.add(new Problem2(path, "命中禁用表述：" + matcher.group()));
            }
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) scanBanned(node.get(i), path + "[" + i + "]", problems);
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> scanBanned(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(), problems));
        }
    }
}

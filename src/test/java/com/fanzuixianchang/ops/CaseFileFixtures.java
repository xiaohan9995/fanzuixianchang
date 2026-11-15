package com.fanzuixianchang.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * 案卷内容的测试夹具。
 *
 * 存在的理由：{@link CaseFileValidator} 的规则一改，所有需要「一份能过校验的内容包」
 * 的测试都得跟着改。夹具只有一处，改动也只在一处。
 *
 * 之前这份构造散在 CaseFileStoreTest 里，另外两个测试跨类去调它——
 * 测试之间靠另一个测试类的静态方法互相依赖，改一个测试的签名会连累别的测试，
 * 而且看不出依赖关系。抽出来之后依赖是显式的。
 */
final class CaseFileFixtures {

    /** 夹具共用一个 mapper：内容包只是建对象树，不需要各自的配置。 */
    static final ObjectMapper MAPPER = new ObjectMapper();

    private CaseFileFixtures() {}

    /**
     * 一份最小的合法内容包，刚好够过校验。
     *
     * 刻意保持最小：字段齐全、值合法，但没有多余内容。
     * 想测某个字段出问题，在返回值上就地改，不要往这里加开关。
     */
    static JsonNode bundle(String title, String verdict) {
        var root = MAPPER.createObjectNode();
        root.put("schemaVersion", CaseFileValidator.SCHEMA_VERSION);
        ArrayNode cases = root.putArray("cases");
        var item = cases.addObject();
        item.put("id", "a-001").put("code", "A-001").put("title", title).put("hook", "她说只吃了半碗。");
        item.putObject("meta").put("place", "茶水间").put("time", "某个加班周").put("length", "约 3 分钟");
        ArrayNode cast = item.putArray("cast");
        cast.addObject().put("role", "报案人").put("name", "林小姐").put("line", "我只吃了半碗。");
        ArrayNode panels = item.putArray("panels");
        panels.addObject().put("kind", "text").put("no", "壹").put("title", "开场").put("body", "连续三天都记半碗饭。");
        ArrayNode evidence = item.putArray("evidence");
        evidence.addObject().put("label", "空盒").put("value", "210 g").put("note", "深口圆盒");
        evidence.addObject().put("label", "盛饭后").put("value", "360 g").put("note", "压实");
        evidence.addObject().put("label", "实际").put("value", "150 g").put("note", "差值");
        item.put("evidenceNote", "同一只盒子。");
        var red = item.putObject("redHerring").put("title", "先排除一条错路");
        red.put("claim", "是不是算贵了？").put("check", "代进同一密度再比。").put("result", "密度没变，差的是克数。");
        item.putObject("verdict").put("title", "结案").put("text", verdict);
        var lesson = item.putObject("lesson").put("title", "规矩");
        lesson.putArray("points").add("体积不等于质量。").add("能称就称一次。");
        item.put("quote", "秤不会替你省粮食。");
        item.putArray("sourceIds").add("H02");
        item.putObject("action").put("label", "称一称这一顿").put("url", "/pages/meal-log/index");
        item.putObject("next").put("code", "A-002").put("title", "零糖的证明").put("teaser", "配料表在笑。");
        return root;
    }

    /** 只关心标题的场合用这个：结案语固定。 */
    static JsonNode bundle(String title) {
        return bundle(title, "结案。");
    }
}

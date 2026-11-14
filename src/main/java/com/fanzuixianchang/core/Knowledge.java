package com.fanzuixianchang.core;

import java.util.*;

/** Small versioned reference set; not a nutrient database nor a medical-review claim. */
public final class Knowledge {
    public static final Map<String,Map<String,String>> SOURCES=Map.of(
        "H01",Map.of("id","H01","title","国家卫健委《居民体重管理核心知识（2024年版）释义》","url","https://www.nhc.gov.cn/cms-search/downFiles/5e20de34c0fe45bb9fcac0636b21ad98.pdf"),
        "H02",Map.of("id","H02","title","中国营养学会《中国居民膳食指南（2022）》公开八准则","url","https://dg.cnsoc.org/article/04/J4-AsD_DR3OLQMnHG0-jZA.html"));
    private Knowledge() {}
    public static String context(String mode) {
        return """
            以下为公开一般健康教育资料的有限整理，适用于一般健康成人，尚未完成专业审核；不构成诊疗或国家背书。
            H01：国家卫健委2024年版核心知识及释义，强调长期、合理的健康管理，维持膳食平衡并区分人群需求。不能据此把所有人都当成减重对象。
            H02：中国营养学会2022年版公开八准则，关注食物类别搭配、规律进餐、避免过度节食、阅读食品标签。不能凭单餐诊断缺乏或许诺减重。
            可引用ID只有H01、H02。未经核验的数值、疾病处方、药物与极端节食方案均不得生成。
            标签调查以服务器确定计算为准，指南不提供这顿饭的食物成分数据。没有营养数据库，不得凭照片编造克数、能量、营养素或误差范围。
            """;
    }
}

package com.fanzuixianchang.api;

import com.fanzuixianchang.ai.ModelGateway;
import com.fanzuixianchang.core.Problem;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/**
 * 周复盘：客户端把这一周的确定性统计发上来，模型负责解读，服务端负责校验。
 *
 * 为什么由客户端传 facts 而不是后端拉数据：
 * 营养计算已经是客户端的确定能力（lib/health.js）。后端重写一遍必然产生分歧，
 * 而分歧意味着同一个用户在两处看到不同的数——这正是 PromptPolicy 要避免的事。
 *
 * 代价是 facts 来自客户端，可以被伪造。这不构成风险：数据是用户自己的，
 * 伪造只能骗自己；真正要防的是「模型编数字」，那由 WeekReviewValidator 拦。
 */
@RestController
@RequestMapping("/api/cases")
public final class WeekReviewController {
    public record ReviewRequest(Map<String, Object> facts, Boolean consent) {}

    private final ModelGateway model;
    private final boolean enabled;

    public WeekReviewController(ModelGateway model) {
        this(model, true);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WeekReviewController(ModelGateway model, @Value("${app.ai.week-review-enabled:true}") boolean enabled) {
        this.model = model;
        this.enabled = enabled;
    }

    @PostMapping("/week-review")
    public Map<String, Object> review(@RequestBody ReviewRequest request) {
        if (!enabled) throw new Problem(503, "REVIEW_DISABLED", "周复盘暂不可用，先看这周的统计。");
        // 没有明确同意就不外发。客户端会先拿到授权再调这里，这一层是兜底。
        if (request == null || !Boolean.TRUE.equals(request.consent())) {
            throw new Problem(403, "CONSENT_REQUIRED", "需要你同意把本周统计发给模型，才能生成复盘。");
        }
        var facts = com.fanzuixianchang.ai.WeekReviewValidator.validFacts(request.facts());
        var analysis = model.weekReview(facts);

        var result = new LinkedHashMap<String, Object>();
        result.put("analysis", analysis);
        result.put("modelVersion", "week-review-v1");
        result.put("generatedAt", java.time.Instant.now().toString());
        return result;
    }

}

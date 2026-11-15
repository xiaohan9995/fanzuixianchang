package com.fanzuixianchang.ops;

import com.fanzuixianchang.ai.ModelGateway;
import com.fanzuixianchang.core.FoodCatalog;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营后台的只读端点：服务是否配好、接口跑得怎么样。
 *
 * 「首页 AI 首问建议」的编辑端点已随功能下架一并移除；
 * 案卷内容的读写与发布在 {@link CaseFileController}，
 * 反馈与提案在 {@link OpsWorkController}。
 */
@RestController
public final class OpsController {

    private final ModelGateway model;
    private final OpsMetrics metrics;

    @Value("${app.ops.storage:file}")
    private String storage = "file";

    public OpsController(ModelGateway model) {
        this(model, new OpsMetrics());
    }

    @Autowired
    public OpsController(ModelGateway model, OpsMetrics metrics) {
        this.model = model;
        this.metrics = metrics;
    }

    @GetMapping("/api/ops/metrics")
    public Map<String, Object> metrics() {
        return metrics.snapshot();
    }

    @GetMapping("/api/ops/overview")
    public Map<String, Object> overview() {
        return Map.of(
                "status", "ok",
                "modelConfigured", model.configured(),
                "catalogVersion", FoodCatalog.VERSION,
                "metricsAvailable", true,
                "storage", storageName(),
                "nutritionEditingAvailable", false);
    }

    private String storageName() {
        if ("cloudbase-pg".equals(storage)) return "cloudbase-postgresql";
        if ("mysql".equals(storage)) return "wechat-cloudrun-mysql";
        return "single-instance-file";
    }
}

package com.fanzuixianchang.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanzuixianchang.core.Problem;
import com.fanzuixianchang.storage.ObjectStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/**
 * 案卷内容的读写端点。
 *
 * 两组路径，别混：
 *   /api/content/cases/**  公开只读，给小程序拉内容；不鉴权，但要限流（走 ApiGuard）
 *   /api/ops/cases/**      管理员读写，走 OpsAuth（Bearer token）
 *
 * 公开端点只吐「已发布」的内容，草稿永远不出后端——这是审核的意义所在。
 */
@RestController
public final class CaseFileController {
    /** 图片上传的载荷：与现有 /api/storage/files 一致，走 JSON+base64，不引入 multipart。 */
    public record ImageUpload(String caseId, String name, String mimeType, String base64) {}
    public record ImageStored(String key) {}

    private static final Set<String> ALLOWED_MIME = Set.of("image/jpeg", "image/png", "image/webp");
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final String IMAGE_PREFIX = "content/cases/";
    /** 签名链接有效期。公有读部署下用不到；私有桶时靠它兜底。 */
    private static final Duration SIGN_TTL = Duration.ofDays(7);

    private final CaseFileStore store;
    private final ObjectStore objects;
    private final ObjectMapper mapper;

    @Value("${app.content.image-base-url:}")
    private String imageBaseUrl = "";

    public CaseFileController(CaseFileStore store, ObjectStore objects, ObjectMapper mapper) {
        this.store = store;
        this.objects = objects;
        this.mapper = mapper;
    }

    // ── 小程序侧：只读已发布 ───────────────────────────────────────

    @GetMapping("/api/content/cases")
    public JsonNode publishedBundle() {
        var published = store.published();
        if (published == null) {
            // 还没发布过：给一个空包，客户端据此退回内置种子，不报错。
            return mapper.createObjectNode().put("schemaVersion", CaseFileValidator.SCHEMA_VERSION).putArray("cases");
        }
        // 一次全量返回：案子少、单案 ≤200KB，比让客户端先拿目录再逐个取详情更省往返，
        // 也让「离线时读到的是完整内容」这件事成立。
        return CaseFileStore.forClient(published, this::imageUrl);
    }

    @GetMapping("/api/content/cases/{id}")
    public JsonNode publishedCase(@PathVariable String id) {
        var bundle = publishedBundle();
        for (var item : bundle.path("cases")) {
            if (item.path("id").asText("").equals(id) || item.path("code").asText("").equalsIgnoreCase(id)) {
                return item;
            }
        }
        throw new Problem(404, "NOT_FOUND", "这一案不在线上了，可能已经下架。");
    }

    // ── 后台侧：完整状态与写入 ─────────────────────────────────────

    @GetMapping("/api/ops/cases")
    public CaseFileStore.State content() {
        return store.get();
    }

    @PutMapping("/api/ops/cases")
    public CaseFileStore.State save(@RequestBody CaseFileStore.Change change) {
        return store.save(change);
    }

    @PostMapping("/api/ops/cases/publish")
    public CaseFileStore.State publish(@RequestBody CaseFileStore.Revision revision) {
        return store.publish(revision.version());
    }

    @PostMapping("/api/ops/cases/rollback")
    public CaseFileStore.State rollback(@RequestBody CaseFileStore.Revision revision) {
        return store.rollback(revision);
    }

    /** 上传一张漫画格图片，返回可直接写进 JSON 的 key。 */
    @PostMapping("/api/ops/cases/images")
    public ImageStored upload(@RequestBody ImageUpload input) {
        if (input == null || input.caseId() == null || !input.caseId().matches("^a-\\d{3}$")) {
            throw Problem.input("案号格式应为 a-001。");
        }
        if (input.mimeType() == null || !ALLOWED_MIME.contains(input.mimeType())) {
            throw Problem.input("图片只收 JPEG、PNG 或 WebP。");
        }
        if (input.base64() == null || input.base64().isEmpty()
                || input.base64().length() > ((MAX_IMAGE_BYTES + 2) / 3) * 4) {
            throw Problem.input("图片需为 2MiB 以内的有效图片。");
        }
        if (!objects.enabled()) throw new Problem(503, "STORAGE_UNAVAILABLE", "对象存储未配置，暂时无法上传图片。");

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(input.base64());
        } catch (IllegalArgumentException e) {
            throw Problem.input("图片编码无效，请重新选择图片。");
        }
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) throw Problem.input("图片需为 2MiB 以内。");

        var name = safeName(input.name(), input.mimeType());
        var key = IMAGE_PREFIX + input.caseId() + "/" + name;
        try {
            objects.put(key, bytes, input.mimeType());
        } catch (RuntimeException e) {
            throw new Problem(503, "STORAGE_UNAVAILABLE", "图片上传失败，请稍后重试。");
        }
        return new ImageStored(key);
    }

    /** 文件名只留安全字符，避免覆盖别人或跳出目录。 */
    private static String safeName(String raw, String mime) {
        String ext = switch (mime) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
        var base = raw == null ? "" : raw.replaceAll("\\.[A-Za-z0-9]+$", "").replaceAll("[^A-Za-z0-9_-]", "");
        if (base.isEmpty()) base = Long.toString(System.currentTimeMillis(), 36);
        return base.length() > 48 ? base.substring(0, 48) : base;
    }

    /**
     * 图片地址解析：配置了 CDN 前缀就用它（推荐，公有读 + 长缓存），
     * 否则退回 COS 签名链接。签名有有效期，所以缓存里的 URL 会过期——
     * 这也是推荐公有读的原因。
     */
    private String imageUrl(String key) {
        if (key == null || key.isEmpty()) return "";
        // 本地素材（以 / 开头）原样返回：种子内容用它们，不该被改写。
        if (key.startsWith("/")) return key;
        if (!imageBaseUrl.isBlank()) {
            return imageBaseUrl.endsWith("/") ? imageBaseUrl + key : imageBaseUrl + "/" + key;
        }
        try {
            return objects.signedGet(key, Instant.now().plus(SIGN_TTL));
        } catch (RuntimeException e) {
            return key;
        }
    }
}

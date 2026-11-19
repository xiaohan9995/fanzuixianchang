package com.fanzuixianchang.speech;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只做一层很薄的 HTTP 入口。身份、限流和请求体上限由 ApiGuard 统一处理。
 *
 * <p>录音以原始二进制提交，Content-Type 为 audio/wav 或 audio/ogg；
 * 时长为客户端实测值，服务端仍会解析 WAV 头并做硬校验。
 */
@RestController
@RequestMapping("/api/speech")
public final class SpeechController {
    private final SpeechTranscriber transcriber;

    public SpeechController(SpeechTranscriber transcriber) {
        this.transcriber = transcriber;
    }

    @PostMapping(value="/recognize",consumes={MediaType.APPLICATION_OCTET_STREAM_VALUE,"audio/wav","audio/x-wav","audio/wave","audio/ogg","audio/opus"})
    public Map<String,Object> recognize(
            @RequestBody byte[] audio,
            @RequestHeader(value="Content-Type",required=false) String contentType,
            @RequestHeader(value="X-Audio-Duration-Ms",required=false) Integer durationMs) {
        return transcriber.recognize(new SpeechAudio(audio,contentType,durationMs));
    }
}

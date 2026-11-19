package com.fanzuixianchang.api;

import com.fanzuixianchang.auth.AuthService;
import com.fanzuixianchang.speech.SpeechAudio;
import com.fanzuixianchang.speech.SpeechController;
import com.fanzuixianchang.speech.SpeechTranscriber;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SpeechApiTest {
    private static final String TOKEN="speech-development-token-123";
    private static final byte[] AUDIO="RIFF-not-a-real-wav-but-auth-test".getBytes();

    private MockMvc mvc(SpeechTranscriber transcriber) {
        var env=new MockEnvironment();env.setActiveProfiles("local");
        var mapper=new ObjectMapper();
        var auth=new AuthService("","","",TOKEN,"127.0.0.1",env,mapper);
        return MockMvcBuilders.standaloneSetup(new SpeechController(transcriber))
            .setControllerAdvice(new ApiErrors())
            .addFilters(new ApiGuard(auth,mapper))
            .build();
    }

    @Test void speechRequiresCloudIdentity() throws Exception {
        var mvc=mvc(request->{throw new AssertionError("must not call upstream");});
        mvc.perform(post("/api/speech/recognize").contentType("audio/wav").content(AUDIO))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test void transcriptReachesClientWithoutEchoingAudio() throws Exception {
        var seen=new SpeechAudio[1];
        var mvc=mvc(request->{seen[0]=request;return Map.of("text","一碗番茄鸡蛋面","durationMs",1200);});
        mvc.perform(post("/api/speech/recognize")
                .header("Authorization","Bearer "+TOKEN)
                .header("X-Audio-Duration-Ms","1200")
                .contentType("audio/wav")
                .content(AUDIO))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.text").value("一碗番茄鸡蛋面"))
            .andExpect(jsonPath("$.durationMs").value(1200));
        assert seen[0]!=null;
        org.junit.jupiter.api.Assertions.assertArrayEquals(AUDIO,seen[0].bytes());
        org.junit.jupiter.api.Assertions.assertEquals("audio/wav",seen[0].mimeType());
        org.junit.jupiter.api.Assertions.assertEquals(1200,seen[0].durationMs());
    }

    @Test void unsupportedAudioContentTypeIsRejectedBeforeTheTranscriber() throws Exception {
        var mvc=mvc(request->{throw new AssertionError("must not call upstream");});
        mvc.perform(post("/api/speech/recognize")
                .header("Authorization","Bearer "+TOKEN)
                .contentType("application/json")
                .content("{\"audioBase64\":\"x\"}"))
            .andExpect(status().isUnsupportedMediaType());
    }
}

package com.fanzuixianchang.speech;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SpeechRecognitionServiceTest {
    @Test void forwardsMultipartToDeepSeekAndReturnsTranscript() throws Exception {
        var seenAuth=new AtomicReference<String>();
        var seenType=new AtomicReference<String>();
        var seenBody=new AtomicReference<byte[]>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/tools/asr",exchange->{
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            seenBody.set(exchange.getRequestBody().readAllBytes());
            byte[] response="{\"request_id\":\"req-1\",\"text\":\"一碗番茄鸡蛋面\",\"duration_ms\":1200}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try{
            var service=new SpeechRecognitionService("test-key","http://127.0.0.1:"+server.getAddress().getPort(),30,true,true,new ObjectMapper());
            var result=service.recognize(new SpeechAudio(wav(16000,1),"audio/wav",1200));
            assertEquals("一碗番茄鸡蛋面",result.get("text"));
            assertEquals(1200,result.get("durationMs"));
            assertEquals("Bearer test-key",seenAuth.get());
            assertTrue(seenType.get().startsWith("multipart/form-data; boundary="));
            String multipart=new String(seenBody.get(),StandardCharsets.ISO_8859_1);
            assertTrue(multipart.contains("name=\"file\"; filename=\"speech.wav\""));
            assertTrue(multipart.contains("RIFF"));
        }finally{server.stop(0);}
    }

    @Test void upstreamBusyAndEmptyTranscriptBecomeSafeClientErrors() throws Exception {
        var mode=new AtomicReference<String>("busy");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/tools/asr",exchange->{
            byte[] response=mode.get().equals("busy")
                ?"{\"error\":{\"message\":\"too many\"}}".getBytes(StandardCharsets.UTF_8)
                :"{\"request_id\":\"r\",\"text\":\"  \",\"duration_ms\":500}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(mode.get().equals("busy")?429:200,response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try{
            var service=new SpeechRecognitionService("test-key","http://127.0.0.1:"+server.getAddress().getPort(),30,true,true,new ObjectMapper());
            var busy=assertThrows(com.fanzuixianchang.core.Problem.class,()->service.recognize(new SpeechAudio(wav(16000,1),"audio/wav",1000)));
            assertEquals(429,busy.status());
            assertEquals("SPEECH_BUSY",busy.code());
            mode.set("empty");
            var empty=assertThrows(com.fanzuixianchang.core.Problem.class,()->service.recognize(new SpeechAudio(wav(16000,1),"audio/wav",1000)));
            assertEquals(422,empty.status());
            assertEquals("SPEECH_EMPTY",empty.code());
        }finally{server.stop(0);}
    }

    @Test void validatesAudioLocallyAndDoesNotCallUpstream() {
        var service=new SpeechRecognitionService("","https://api.deepseek.com",30,true,false,new ObjectMapper());
        var missing=assertThrows(com.fanzuixianchang.core.Problem.class,()->service.recognize(new SpeechAudio(new byte[0],null,1000)));
        assertEquals("INVALID_INPUT",missing.code());

        var wrongMime=assertThrows(com.fanzuixianchang.core.Problem.class,()->service.recognize(new SpeechAudio(wav(16000,1),"audio/mpeg",1000)));
        assertEquals("SPEECH_INVALID_AUDIO",wrongMime.code());

        var tooLong=assertThrows(com.fanzuixianchang.core.Problem.class,()->service.recognize(new SpeechAudio(wav(8000,121),"audio/wav",121000)));
        assertEquals("SPEECH_TOO_LONG",tooLong.code());

        var notAudio=assertThrows(com.fanzuixianchang.core.Problem.class,()->service.recognize(new SpeechAudio("not audio".getBytes(StandardCharsets.UTF_8),null,1000)));
        assertEquals("SPEECH_INVALID_AUDIO",notAudio.code());
    }

    @Test void unconfiguredServiceIsHonestButStillValidatesNothingSecret() {
        var service=new SpeechRecognitionService("","https://api.deepseek.com",30,true,false,new ObjectMapper());
        var error=assertThrows(com.fanzuixianchang.core.Problem.class,()->service.recognize(new SpeechAudio(wav(16000,1),"audio/wav",1000)));
        assertEquals(503,error.status());
        assertEquals("SPEECH_NOT_CONFIGURED",error.code());
    }

    /** Minimal 16-bit PCM mono WAV used by the validator and mock upstream. */
    static byte[] wav(int sampleRate,int seconds){
        int dataSize=sampleRate*2*seconds;
        var out=new ByteArrayOutputStream(dataSize+44);
        ascii(out,"RIFF");le32(out,36+dataSize);ascii(out,"WAVE");
        ascii(out,"fmt ");le32(out,16);le16(out,1);le16(out,1);le32(out,sampleRate);le32(out,sampleRate*2);le16(out,2);le16(out,16);
        ascii(out,"data");le32(out,dataSize);out.writeBytes(new byte[dataSize]);
        return out.toByteArray();
    }
    private static void ascii(ByteArrayOutputStream out,String value){out.writeBytes(value.getBytes(StandardCharsets.US_ASCII));}
    private static void le16(ByteArrayOutputStream out,int value){out.write(value&0xFF);out.write((value>>>8)&0xFF);}
    private static void le32(ByteArrayOutputStream out,int value){
        out.write(value&0xFF);out.write((value>>>8)&0xFF);out.write((value>>>16)&0xFF);out.write((value>>>24)&0xFF);
    }
}

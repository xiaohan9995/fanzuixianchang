package com.fanzuixianchang.speech;

import com.fanzuixianchang.core.Problem;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * DeepSeek 语音转写（ASR）服务端适配。
 *
 * <p>DeepSeek 的 ASR 与聊天模型是两套接口：
 * {@code POST https://api.deepseek.com/v1/tools/asr}，multipart 的 {@code file} 字段，
 * 返回 {@code {request_id,text,duration_ms}}。密钥只存在服务端。
 *
 * <p>这里不做“先存音频再异步转写”：录音只在本次请求的内存里存在，转发后即丢弃。
 * 日志只记录字节数、时长、上游状态和耗时，不记录音频、转写文本或上游错误正文。
 */
@Service
public final class SpeechRecognitionService implements SpeechTranscriber {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SpeechRecognitionService.class);
    private static final int MAX_AUDIO_BYTES=4*1024*1024;
    private static final int MAX_AUDIO_MS=120_000;
    private static final int MAX_TEXT_CHARS=4_000;
    private static final Set<Integer> WAV_RATES=Set.of(8000,16000,22050,24000,32000,44100,48000);
    private static final Set<String> WAV_MIMES=Set.of("audio/wav","audio/x-wav","audio/wave","audio/vnd.wave");
    private static final Set<String> OGG_MIMES=Set.of("audio/ogg","audio/opus");

    private final String apiKey;
    private final URI endpoint;
    private final Duration timeout;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final boolean configured;

    @Autowired
    public SpeechRecognitionService(
            @Value("${app.ai.api-key:}") String apiKey,
            @Value("${app.ai.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${app.ai.asr-timeout-seconds:30}") int timeoutSeconds,
            @Value("${app.ai.asr-enabled:true}") boolean enabled,
            ObjectMapper mapper) {
        this(apiKey,baseUrl,timeoutSeconds,enabled,false,mapper);
    }

    /** Package-private HTTP variant exists only so tests can use a loopback mock server. */
    SpeechRecognitionService(String apiKey,String baseUrl,int timeoutSeconds,boolean enabled,boolean allowInsecure,ObjectMapper mapper) {
        if(timeoutSeconds<5||timeoutSeconds>55)throw new IllegalArgumentException("AI_ASR_TIMEOUT_SECONDS must be between 5 and 55");
        this.apiKey=apiKey==null?"":apiKey.trim();
        this.configured=enabled&&!this.apiKey.isBlank();
        this.timeout=Duration.ofSeconds(timeoutSeconds);
        this.mapper=mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.endpoint=this.configured?asrEndpoint(baseUrl,allowInsecure):null;
        this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    @Override
    public Map<String,Object> recognize(SpeechAudio request) {
        ValidAudio audio=validate(request);
        if(!configured)throw new Problem(503,"SPEECH_NOT_CONFIGURED","语音转写尚未配置，可以先用键盘语音或文字输入。");
        long started=System.nanoTime();
        log.info("speech start bytes={} mime={} durationMs={}",audio.bytes().length,audio.mimeType(),audio.durationMs());
        try{
            byte[] body=multipart(audio);
            var upstream=HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization","Bearer "+apiKey)
                .header("Content-Type","multipart/form-data; boundary="+BOUNDARY)
                .header("Accept","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
            var response=http.send(upstream,HttpResponse.BodyHandlers.ofByteArray());
            int status=response.statusCode();
            long elapsed=(System.nanoTime()-started)/1_000_000;
            if(status<200||status>=300){
                log.warn("speech upstream rejected status={} elapsedMs={}",status,elapsed);
                throw upstreamProblem(status);
            }
            byte[] responseBody=response.body();
            if(responseBody==null||responseBody.length>64*1024)throw new Problem(502,"SPEECH_UNAVAILABLE","语音转写返回异常，请稍后重试，或直接打字。");
            JsonNode root=mapper.readTree(responseBody);
            String text=root==null?null:root.path("text").textValue();
            text=text==null?"":text.trim();
            if(text.isEmpty())throw new Problem(422,"SPEECH_EMPTY","没听清这句话，请再说一次或直接打字。");
            if(text.length()>MAX_TEXT_CHARS)throw new Problem(422,"SPEECH_INVALID_AUDIO","这段语音转写内容过长，请分两句说。");
            long upstreamMs=root.path("duration_ms").asLong(-1);
            int duration=upstreamMs>=1&&upstreamMs<=MAX_AUDIO_MS?(int)upstreamMs:audio.durationMs();
            log.info("speech done status=200 elapsedMs={} textLen={} durationMs={}",elapsed,text.length(),duration);
            return Map.of("text",text,"durationMs",duration);
        }catch(Problem p){throw p;}
        catch(HttpTimeoutException e){throw new Problem(504,"SPEECH_TIMEOUT","语音转写超时，请缩短一句话再试，或直接打字。");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new Problem(502,"SPEECH_UNAVAILABLE","语音转写暂时不可用，请稍后重试，或直接打字。");}
        catch(IOException|RuntimeException e){
            log.warn("speech transport failed type={} elapsedMs={}",e.getClass().getSimpleName(),(System.nanoTime()-started)/1_000_000);
            throw new Problem(502,"SPEECH_UNAVAILABLE","语音转写暂时不可用，请稍后重试，或直接打字。");
        }
    }

    /** 校验请求，并把真实的 WAV 时长作为服务端约束，而不是只相信客户端报数。 */
    static ValidAudio validate(SpeechAudio request) {
        if(request==null||request.bytes()==null||request.bytes().length==0)
            throw new Problem(400,"INVALID_INPUT","请重新录一段语音。");
        byte[] bytes=request.bytes();
        if(bytes.length>MAX_AUDIO_BYTES)throw tooLong();
        String detected=detectAudio(bytes);
        String declared=normalizeMime(request.mimeType());
        if(detected==null)throw invalidAudio();
        String mime=declared==null?detected:declared;
        if(!mime.equals(detected))throw invalidAudio();
        Integer declaredMs=request.durationMs();
        if(declaredMs!=null&&(declaredMs<0||declaredMs>MAX_AUDIO_MS))throw tooLong();
        int duration;
        if(detected.equals("audio/wav")){
            WavInfo info=parseWav(bytes);
            if(info==null)throw invalidAudio();
            duration=(int)Math.round(info.dataBytes()*1000.0/info.byteRate());
        }else{
            duration=declaredMs==null?-1:declaredMs;
        }
        if(duration<=0)throw new Problem(400,"INVALID_INPUT","这段录音太短了，请按住多说一会儿。");
        if(duration>MAX_AUDIO_MS)throw tooLong();
        return new ValidAudio(bytes,detected,duration);
    }

    record ValidAudio(byte[] bytes,String mimeType,int durationMs) {}
    private record WavInfo(int format,int channels,int sampleRate,int byteRate,int bitsPerSample,long dataBytes) {}

    private static Problem tooLong(){return new Problem(413,"SPEECH_TOO_LONG","这段录音太长了，请控制在一分钟以内；也可以直接打字。");}
    private static Problem invalidAudio(){return new Problem(422,"SPEECH_INVALID_AUDIO","这段录音没能识别，请重说，或直接打字。");}
    private static Problem upstreamProblem(int status){
        if(status==401||status==403)return new Problem(503,"SPEECH_NOT_CONFIGURED","语音转写尚未配置，可以先用键盘语音或文字输入。");
        if(status==408||status==504)return new Problem(504,"SPEECH_TIMEOUT","语音转写超时，请缩短一句话再试，或直接打字。");
        if(status==413)return tooLong();
        if(status==429)return new Problem(429,"SPEECH_BUSY","语音转写有点忙，请稍等再试，或直接打字。");
        if(status==400||status==415||status==422)return invalidAudio();
        if(status==503)return new Problem(502,"SPEECH_UNAVAILABLE","语音转写暂时不可用，请稍后重试，或直接打字。");
        return new Problem(502,"SPEECH_UNAVAILABLE","语音转写暂时不可用，请稍后重试，或直接打字。");
    }

    private static URI asrEndpoint(String baseUrl,boolean allowInsecure){
        if(baseUrl==null||baseUrl.isBlank())throw new IllegalArgumentException("AI base URL is required");
        URI base;
        try{base=URI.create(baseUrl.trim());}
        catch(IllegalArgumentException e){throw new IllegalArgumentException("AI base URL must be a valid HTTPS API origin");}
        String scheme=base.getScheme()==null?"":base.getScheme().toLowerCase();
        boolean okScheme=allowInsecure?("http".equals(scheme)||"https".equals(scheme)):"https".equals(scheme);
        if(!okScheme||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null)
            throw new IllegalArgumentException("AI base URL must be an HTTPS API origin");
        String normalized=baseUrl.trim().replaceAll("/+$","");
        return URI.create(normalized+"/v1/tools/asr");
    }

    private static String normalizeMime(String value){
        if(value==null)return null;
        String mime=value.trim().toLowerCase();
        int semicolon=mime.indexOf(';');
        if(semicolon>=0)mime=mime.substring(0,semicolon).trim();
        if(WAV_MIMES.contains(mime))return "audio/wav";
        if(OGG_MIMES.contains(mime))return "audio/ogg";
        // 云托管/网关可能把自定义音频类型归一成 octet-stream；真正的格式仍由文件头判断。
        if(mime.equals("application/octet-stream")||mime.equals("binary/octet-stream")||mime.equals("application/binary"))return null;
        return mime.isEmpty()?null:mime;
    }

    private static String detectAudio(byte[] bytes){
        if(bytes.length>=12&&bytes[0]=='R'&&bytes[1]=='I'&&bytes[2]=='F'&&bytes[3]=='F'
                &&bytes[8]=='W'&&bytes[9]=='A'&&bytes[10]=='V'&&bytes[11]=='E')return "audio/wav";
        if(bytes.length>=4&&bytes[0]=='O'&&bytes[1]=='g'&&bytes[2]=='g'&&bytes[3]=='S')return "audio/ogg";
        return null;
    }

    /**
     * 逐块读取 RIFF，不假设 fmt/data 的顺序。微信录音是 16-bit PCM WAV；
     * 如果文件头无法证明这一点，就不把任意二进制转发给上游。
     */
    private static WavInfo parseWav(byte[] bytes){
        if(bytes.length<44)return null;
        Integer format=null,channels=null,sampleRate=null,byteRate=null,bits=null;
        Long dataBytes=null;
        int position=12;
        while(position+8<=bytes.length){
            String id=ascii4(bytes,position);
            long size=u32(bytes,position+4);
            int start=position+8;
            long available=bytes.length-start;
            if(size>available){
                if(id.equals("data"))dataBytes=available;
                break;
            }
            if(id.equals("fmt ")){
                if(size<16)return null;
                format=u16(bytes,start);
                channels=u16(bytes,start+2);
                sampleRate=(int)Math.min(Integer.MAX_VALUE,u32(bytes,start+4));
                byteRate=(int)Math.min(Integer.MAX_VALUE,u32(bytes,start+8));
                bits=u16(bytes,start+14);
            }else if(id.equals("data")){
                dataBytes=size==0&&available>0?available:size;
            }
            long next=start+size+(size&1);
            if(next>bytes.length)break;
            position=(int)next;
        }
        if(format==null||channels==null||sampleRate==null||byteRate==null||bits==null||dataBytes==null)return null;
        if(format!=1&&format!=0xFFFE)return null;
        if(channels<1||channels>2)return null;
        if(!WAV_RATES.contains(sampleRate))return null;
        if(bits!=16||byteRate<=0||dataBytes<=0)return null;
        return new WavInfo(format,channels,sampleRate,byteRate,bits,dataBytes);
    }

    private static int u16(byte[] bytes,int offset){
        return (bytes[offset]&0xFF)|((bytes[offset+1]&0xFF)<<8);
    }
    private static long u32(byte[] bytes,int offset){
        return (bytes[offset]&0xFFL)|((bytes[offset+1]&0xFFL)<<8)|((bytes[offset+2]&0xFFL)<<16)|((bytes[offset+3]&0xFFL)<<24);
    }
    private static String ascii4(byte[] bytes,int offset){
        return new String(bytes,offset,4,StandardCharsets.US_ASCII);
    }

    private static final String BOUNDARY="----fanzuixianchang-asr-"+UUID.randomUUID();
    private static byte[] multipart(ValidAudio audio)throws IOException{
        String filename=audio.mimeType().equals("audio/ogg")?"speech.ogg":"speech.wav";
        var out=new ByteArrayOutputStream(audio.bytes().length+512);
        ascii(out,"--"+BOUNDARY+"\r\n");
        ascii(out,"Content-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\n");
        ascii(out,"Content-Type: "+audio.mimeType()+"\r\n\r\n");
        out.write(audio.bytes());
        ascii(out,"\r\n--"+BOUNDARY+"--\r\n");
        return out.toByteArray();
    }
    private static void ascii(ByteArrayOutputStream out,String value)throws IOException{
        out.write(value.getBytes(StandardCharsets.US_ASCII));
    }
}

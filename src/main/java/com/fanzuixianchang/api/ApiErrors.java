package com.fanzuixianchang.api;

import com.fanzuixianchang.core.Problem;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public final class ApiErrors {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(Problem.class) public ResponseEntity<Map<String,Object>> problem(Problem e){return ResponseEntity.status(e.status()).body(payload(e.code(),e.getMessage()));}
    @ExceptionHandler(HttpMessageNotReadableException.class) public ResponseEntity<Map<String,Object>> malformed(){return ResponseEntity.badRequest().body(payload("INVALID_INPUT","请求格式无效，请检查输入后重试。"));}
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String,Object>> methodNotAllowed(){log.warn("API rejected: METHOD_NOT_ALLOWED; check deployed client/server versions");return ResponseEntity.status(405).body(payload("METHOD_NOT_ALLOWED","请求方法与服务版本不匹配，请更新小程序和后端；当前登录接口为 GET /api/session。"));}
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String,Object>> unsupportedMediaType(){log.warn("API rejected: UNSUPPORTED_MEDIA_TYPE");return ResponseEntity.status(415).body(payload("UNSUPPORTED_MEDIA_TYPE","请求内容类型不受支持；语音请使用 audio/wav 或 audio/ogg。"));}
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String,Object>> notFound(){log.warn("API rejected: NOT_FOUND; check deployed route/version");return ResponseEntity.status(404).body(payload("NOT_FOUND","接口不存在，请确认服务部署版本和请求路径。"));}
    @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,Object>> unexpected(Exception e){
        String incident=java.util.UUID.randomUUID().toString();
        // Exception messages may contain credentials or user data: log only types and code locations.
        Throwable cause=e;for(int depth=0;cause!=null&&depth<6;depth++,cause=cause.getCause()){
            log.error("API incident={} depth={} type={}",incident,depth,cause.getClass().getName());
            for(var frame:cause.getStackTrace())if(frame.getClassName().startsWith("com.fanzuixianchang."))log.error("API incident={} at={}",incident,frame);
        }
        return ResponseEntity.internalServerError().body(Map.of("error",Map.of("code","INTERNAL_ERROR","message","服务处理失败，请稍后重试；已有记录不受影响。","incidentId",incident)));
    }
    static Map<String,Object> payload(String code,String message){return Map.of("error",Map.of("code",code,"message",message));}
}

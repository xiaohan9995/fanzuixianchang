package com.fanzuixianchang.api;

import com.fanzuixianchang.core.Problem;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public final class ApiErrors {
    @ExceptionHandler(Problem.class) public ResponseEntity<Map<String,Object>> problem(Problem e){return ResponseEntity.status(e.status()).body(payload(e.code(),e.getMessage()));}
    @ExceptionHandler(HttpMessageNotReadableException.class) public ResponseEntity<Map<String,Object>> malformed(){return ResponseEntity.badRequest().body(payload("INVALID_INPUT","请求格式无效，请检查输入后重试。"));}
    @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,Object>> unexpected(){return ResponseEntity.internalServerError().body(payload("INTERNAL_ERROR","本次调查暂未完成，请重试；已有记录不受影响。"));}
    static Map<String,Object> payload(String code,String message){return Map.of("error",Map.of("code",code,"message",message));}
}

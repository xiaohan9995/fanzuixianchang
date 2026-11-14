package com.fanzuixianchang.storage;
import com.fanzuixianchang.core.Problem;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/storage/files")
public final class FileStorageController {
 private final FileStorageService service;
 public FileStorageController(FileStorageService service){this.service=service;}
 private String subject(HttpServletRequest request){
  Object value=request.getAttribute("fanzuixianchang.userIdentity");
  if(!(value instanceof String subject)||subject.isBlank())throw new Problem(401,"UNAUTHORIZED","微信身份校验失败，请确认当前小程序已关联云托管环境后重试。");
  return subject;
 }
 @PostMapping public FileStorageService.Stored upload(@RequestBody FileStorageService.Upload input,HttpServletRequest request){return service.upload(subject(request),input);}
 @GetMapping("/{id}") public FileStorageService.Access access(@PathVariable String id,HttpServletRequest request){return service.access(subject(request),id);}
 @DeleteMapping("/{id}") public Map<String,Boolean> delete(@PathVariable String id,HttpServletRequest request){service.delete(subject(request),id);return Map.of("deleted",true);}
}

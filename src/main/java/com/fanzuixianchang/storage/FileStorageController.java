package com.fanzuixianchang.storage;
import com.fanzuixianchang.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/storage/files")
public final class FileStorageController {
 private final FileStorageService service;private final AuthService auth;
 public FileStorageController(FileStorageService service,AuthService auth){this.service=service;this.auth=auth;}
 private String subject(HttpServletRequest request){return auth.authenticate(request.getHeader("Authorization"),request.getRemoteAddr());}
 @PostMapping public FileStorageService.Stored upload(@RequestBody FileStorageService.Upload input,HttpServletRequest request){return service.upload(subject(request),input);}
 @GetMapping("/{id}") public FileStorageService.Access access(@PathVariable String id,HttpServletRequest request){return service.access(subject(request),id);}
 @DeleteMapping("/{id}") public Map<String,Boolean> delete(@PathVariable String id,HttpServletRequest request){service.delete(subject(request),id);return Map.of("deleted",true);}
}

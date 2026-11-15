package com.fanzuixianchang.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/account")
public final class AccountController {
    private static final String POLICY_VERSION="meal-cloud-v1";
    private final UserDataStore store;private final String appId;private final ObjectMapper mapper;
    public AccountController(UserDataStore store,@org.springframework.beans.factory.annotation.Value("${app.wechat.app-id:}")String appId,ObjectMapper mapper){this.store=store;this.appId=appId;this.mapper=mapper;}
    @GetMapping public JsonNode account(@RequestAttribute(ApiGuardIdentity.ATTRIBUTE)String identity){return json(store.account(appId,identity));}
    @GetMapping("/profile") public Map<String,Object> profile(@RequestAttribute(ApiGuardIdentity.ATTRIBUTE)String identity){return store.profile(appId,identity);}
    @PostMapping("/profile") public Map<String,Object> profile(@RequestAttribute(ApiGuardIdentity.ATTRIBUTE)String identity,@RequestBody ProfileUpdate request){return store.updateProfile(appId,identity,request.nickname(),request.avatarFileId(),request.avatarMimeType());}
    @GetMapping("/sync/consent") public Map<String,Object> consent(@RequestAttribute(ApiGuardIdentity.ATTRIBUTE)String identity){return Map.of("granted",store.consented(appId,identity),"policyVersion",POLICY_VERSION);}
    @PostMapping("/sync/consent") public Map<String,Object> consent(@RequestAttribute(ApiGuardIdentity.ATTRIBUTE)String identity,@RequestBody Consent request){store.consent(appId,identity,request.granted(),request.policyVersion()==null?POLICY_VERSION:request.policyVersion());return Map.of("granted",request.granted(),"policyVersion",POLICY_VERSION);}
    @GetMapping("/meals") public Map<String,Object> meals(@RequestAttribute(ApiGuardIdentity.ATTRIBUTE)String identity){return Map.of("items",store.meals(appId,identity));}
    @GetMapping("/health") public Map<String,Object> health(@RequestAttribute(ApiGuardIdentity.ATTRIBUTE)String identity){return store.health(appId,identity);}
    @PutMapping("/health") public Map<String,Object> health(@RequestAttribute(ApiGuardIdentity.ATTRIBUTE)String identity,@RequestBody HealthSync request){return store.saveHealth(appId,identity,request.version(),request.profile(),request.goals());}
    @PutMapping("/meals") public Map<String,Object> sync(@RequestAttribute(ApiGuardIdentity.ATTRIBUTE)String identity,@RequestBody SyncRequest request){return store.saveMeals(appId,identity,request.records());}
    @DeleteMapping("/meals/{id}") public Map<String,Object> deleteMeal(@RequestAttribute(ApiGuardIdentity.ATTRIBUTE)String identity,@PathVariable String id){store.deleteMeal(appId,identity,id);return Map.of("deleted",true);}
    @DeleteMapping("/meals") public Map<String,Object> deleteMeals(@RequestAttribute(ApiGuardIdentity.ATTRIBUTE)String identity){store.deleteAllMeals(appId,identity);return Map.of("deleted",true);}
    @GetMapping("/export") public ResponseEntity<String> export(@RequestAttribute(ApiGuardIdentity.ATTRIBUTE)String identity){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"fanzuixianchang-data.json\"").body(store.export(appId,identity));}
    @DeleteMapping public Map<String,Object> deleteAccount(@RequestAttribute(ApiGuardIdentity.ATTRIBUTE)String identity){store.deleteAccount(appId,identity);return Map.of("deleted",true);}
    @GetMapping("/photos/consent") public Map<String,Object> photoConsent(@RequestAttribute(ApiGuardIdentity.ATTRIBUTE)String identity){return store.photoConsent(appId,identity);}
    @PostMapping("/photos/consent") public Map<String,Object> photoConsent(@RequestAttribute(ApiGuardIdentity.ATTRIBUTE)String identity,@RequestBody Consent request){store.photoConsent(appId,identity,request.granted());return store.photoConsent(appId,identity);}
    @PostMapping("/photos") public Map<String,Object> uploadPhoto(@RequestAttribute(ApiGuardIdentity.ATTRIBUTE)String identity,@RequestBody PhotoUpload input){return store.uploadPhoto(appId,identity,input.recordId(),input.cloudFileId(),input.mimeType());}
    @GetMapping("/photos/{fileId}") public Map<String,Object> photo(@RequestAttribute(ApiGuardIdentity.ATTRIBUTE)String identity,@PathVariable("fileId")String fileId){return store.photo(appId,identity,fileId);}
    public record PhotoUpload(String recordId,String cloudFileId,String mimeType){}
    public record ProfileUpdate(String nickname,String avatarFileId,String avatarMimeType){}
    private JsonNode json(String value){try{return mapper.readTree(value);}catch(Exception e){throw new IllegalStateException("Invalid account response",e);}}
    public record Consent(boolean granted,String policyVersion){}
    public record SyncRequest(List<Map<String,Object>> records){}
    public record HealthSync(Long version,JsonNode profile,JsonNode goals){}
}

/** Request identity is set only by ApiGuard after validating a server-signed session. */
final class ApiGuardIdentity { static final String ATTRIBUTE="fanzuixianchang.userIdentity"; private ApiGuardIdentity(){} }

package com.fanzuixianchang.ops;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanzuixianchang.core.Problem;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
/** Server-only PostgREST adapter with database compare-and-swap. No management credentials. */
public final class CloudBasePgPersistence implements OpsPersistence {
 private final ObjectMapper mapper;private final String base,key;private final HttpClient client;
 public CloudBasePgPersistence(ObjectMapper mapper,String envId,String apiKey){this(mapper,"https://"+envId+".api.tcloudbasegateway.com/v1/rdb/rest/fanzui_ops_state",apiKey,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());if(!envId.matches("[a-z0-9-]+")||apiKey.isBlank())throw new IllegalArgumentException("CloudBase PG server configuration required");}
 CloudBasePgPersistence(ObjectMapper mapper,String base,String key,HttpClient client){this.mapper=mapper;this.base=base;this.key=key;this.client=client;}
 private String namespace(String value){if(!value.matches("content|workflows"))throw new IllegalArgumentException("Unknown namespace");return value;}
 public Snapshot load(String key){try{var rows=mapper.readTree(send("GET","?namespace=eq."+namespace(key)+"&select=revision,payload",null));if(!rows.isArray()||rows.size()>1)throw unavailable();if(rows.isEmpty())return null;var r=rows.get(0);if(!r.path("revision").canConvertToLong()||!r.path("payload").isObject())throw unavailable();return new Snapshot(r.get("revision").asLong(),mapper.writeValueAsString(r.get("payload")));}catch(Problem e){throw e;}catch(Exception e){throw unavailable();}}
 public void save(String key,long expected,String json){try{namespace(key);var payload=mapper.readTree(json);if(!payload.isObject())throw unavailable();String data=mapper.writeValueAsString(Map.of("namespace",key,"revision",expected+1,"payload",payload,"updated_at",java.time.Instant.now().toString()));var result=mapper.readTree(send(expected<0?"POST":"PATCH",expected<0?"":"?namespace=eq."+key+"&revision=eq."+expected,data));if(!result.isArray()||result.size()!=1)throw conflict();}catch(Problem e){throw e;}catch(Exception e){throw unavailable();}}
 private String send(String method,String query,String json){try{var builder=HttpRequest.newBuilder(URI.create(base+query)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+key).header("Content-Type","application/json").header("Prefer","return=representation");var response=client.send(builder.method(method,json==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json)).build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()==409)throw conflict();if(response.statusCode()<200||response.statusCode()>=300)throw unavailable();return response.body();}catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}catch(java.io.IOException e){throw unavailable();}}
 private static Problem conflict(){return new Problem(409,"VERSION_CONFLICT","数据已更新，请刷新后重试。");}
 private static Problem unavailable(){return new Problem(503,"STORE_UNAVAILABLE","云端持久存储暂不可用，请稍后重试。");}
}

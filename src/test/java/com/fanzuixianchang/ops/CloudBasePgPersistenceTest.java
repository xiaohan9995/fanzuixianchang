package com.fanzuixianchang.ops;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanzuixianchang.core.Problem;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CloudBasePgPersistenceTest {
 record Request(String method,String query,String body,String authorization,String prefer){}
 private static final class Server implements AutoCloseable {
  final HttpServer server;final AtomicReference<Request> request=new AtomicReference<>();int status=200;String body="[]";
  Server()throws IOException{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/state",e->{request.set(new Request(e.getRequestMethod(),e.getRequestURI().getRawQuery(),new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8),e.getRequestHeaders().getFirst("Authorization"),e.getRequestHeaders().getFirst("Prefer")));byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();}
  String url(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/state";}
  CloudBasePgPersistence adapter(){return new CloudBasePgPersistence(new ObjectMapper(),url(),"server-only-key",HttpClient.newHttpClient());}
  public void close(){server.stop(0);}
 }
 @Test void loadUsesBoundedNamespaceAndPreservesChinesePayload()throws Exception{try(var s=new Server()){s.body="[{\"revision\":3,\"payload\":{\"文字\":\"反馈没有丢失\"}}]";var loaded=s.adapter().load("workflows");assertEquals(3,loaded.revision());assertEquals("反馈没有丢失",new ObjectMapper().readTree(loaded.json()).get("文字").asText());assertEquals("GET",s.request.get().method());assertEquals("namespace=eq.workflows&select=revision,payload",s.request.get().query());assertEquals("Bearer server-only-key",s.request.get().authorization());s.body="[]";assertNull(s.adapter().load("content"));var prior=s.request.get();assertEquals(503,assertThrows(Problem.class,()->s.adapter().save("content&select=*",0,"{}")).status());assertSame(prior,s.request.get());}}
 @Test void createAndConditionalPatchSendUtf8AndExpectedRevision()throws Exception{try(var s=new Server()){s.body="[{\"revision\":0}]";var adapter=s.adapter();adapter.save("content",-1,"{\"标题\":\"饭探\"}");var create=s.request.get();assertEquals("POST",create.method());assertNull(create.query());assertEquals("return=representation",create.prefer());var json=new ObjectMapper().readTree(create.body());assertEquals(0,json.get("revision").asLong());assertEquals("饭探",json.get("payload").get("标题").asText());adapter.save("content",7,"{\"version\":8}");var update=s.request.get();assertEquals("PATCH",update.method());assertEquals("namespace=eq.content&revision=eq.7",update.query());assertEquals(8,new ObjectMapper().readTree(update.body()).get("revision").asLong());}}
 @Test void conflictsAndUnauthorizedResponsesCannotMasqueradeAsSuccess()throws Exception{try(var s=new Server()){var adapter=s.adapter();assertEquals(409,assertThrows(Problem.class,()->adapter.save("content",3,"{}")).status());s.status=409;s.body="{\"message\":\"private database detail\"}";assertEquals(409,assertThrows(Problem.class,()->adapter.save("content",-1,"{}")).status());s.status=401;var error=assertThrows(Problem.class,()->adapter.load("workflows"));assertEquals(503,error.status());assertFalse(error.getMessage().contains("private"));s.status=200;s.body="{\"unexpected\":true}";assertEquals(503,assertThrows(Problem.class,()->adapter.load("content")).status());}}
 @Test void timeoutNeverFallsBackToEmptyOrLocalData(){var adapter=new CloudBasePgPersistence(new ObjectMapper(),"https://example.invalid/state","server-only-key",new TimeoutClient());assertEquals(503,assertThrows(Problem.class,()->adapter.load("content")).status());assertEquals(503,assertThrows(Problem.class,()->adapter.save("content",0,"{}")).status());}
 private static final class TimeoutClient extends HttpClient {
  private final HttpClient delegate=HttpClient.newHttpClient();
  public Optional<java.net.CookieHandler> cookieHandler(){return delegate.cookieHandler();}public Optional<Duration> connectTimeout(){return delegate.connectTimeout();}public Redirect followRedirects(){return Redirect.NEVER;}public Optional<ProxySelector> proxy(){return delegate.proxy();}public SSLContext sslContext(){return delegate.sslContext();}public SSLParameters sslParameters(){return delegate.sslParameters();}public Optional<Authenticator> authenticator(){return delegate.authenticator();}public Version version(){return delegate.version();}public Optional<Executor> executor(){return delegate.executor();}
  public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler)throws IOException{throw new HttpTimeoutException("simulated timeout");}
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler){return CompletableFuture.failedFuture(new HttpTimeoutException("simulated timeout"));}
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler,HttpResponse.PushPromiseHandler<T> push){return sendAsync(request,handler);}
 }
}

package com.fanzuixianchang.auth;

import com.fanzuixianchang.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

@Service
public final class AuthService {
    private final String appId,appSecret,devToken;
    private final boolean local;
    private final SessionTokens tokens;
    private final ObjectMapper mapper;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    public AuthService(@Value("${app.wechat.app-id:}")String appId,@Value("${app.wechat.app-secret:}")String appSecret,
        @Value("${app.auth.signing-secret:}")String signingSecret,@Value("${app.auth.dev-token:}")String devToken,
        @Value("${server.address:127.0.0.1}")String address,Environment env,ObjectMapper mapper){
        this.appId=appId;this.appSecret=appSecret;this.devToken=devToken;this.mapper=mapper;
        this.local=env.acceptsProfiles(Profiles.of("local"));
        if(local&&!devToken.isBlank()&&!(address.equals("127.0.0.1")||address.equals("::1")||address.equals("localhost")))
            throw new IllegalArgumentException("Local dev token requires loopback bind address");
        this.tokens=signingSecret.isBlank()?null:new SessionTokens(signingSecret,Clock.systemUTC());
    }
    public String authenticate(String authorization,String remote){
        if(authorization==null||!authorization.startsWith("Bearer "))throw unauthorized();
        String token=authorization.substring(7);
        if(SessionTokens.allowDevToken(local,remote,devToken,token))return "local-developer";
        if(tokens==null)throw unauthorized();
        return tokens.verify(token).orElseThrow(AuthService::unauthorized);
    }
    public SessionTokens.Issued exchange(String code){
        if(appId.isBlank()||appSecret.isBlank()||tokens==null)throw new Problem(503,"NOT_CONFIGURED","微信登录尚未配置；请先使用示例或配置本地调试连接。");
        if(code==null||code.isBlank()||code.length()>256||code.chars().anyMatch(Character::isISOControl))throw Problem.input("微信登录凭证无效，请重试。");
        try{
            String query="appid="+encode(appId)+"&secret="+encode(appSecret)+"&js_code="+encode(code)+"&grant_type=authorization_code";
            var request=HttpRequest.newBuilder(URI.create("https://api.weixin.qq.com/sns/jscode2session?"+query)).timeout(Duration.ofSeconds(12)).GET().build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var body=response.body()){
                byte[] bytes=body.readNBytes(65537);
                if(response.statusCode()!=200||bytes.length>65536)throw unauthorized();
                var json=mapper.readTree(bytes);
                if(json.has("errcode")&&json.path("errcode").asInt()!=0)throw unauthorized();
                String openid=json.path("openid").asText("");
                if(openid.isBlank()||openid.length()>200)throw unauthorized();
                return tokens.issue(openid);
            }
        }catch(Problem p){throw p;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new Problem(502,"LOGIN_UNAVAILABLE","微信登录暂时不可用，请重试。");}
        catch(Exception e){throw new Problem(502,"LOGIN_UNAVAILABLE","微信登录暂时不可用，请重试。");}
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static Problem unauthorized(){return new Problem(401,"UNAUTHORIZED","登录已失效或尚未登录，请重新连接服务。");}
}

package com.fanzuixianchang.api;

import com.fanzuixianchang.auth.AuthService;
import com.fanzuixianchang.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class ApiGuard extends OncePerRequestFilter {
    private static final org.slf4j.Logger access=org.slf4j.LoggerFactory.getLogger(ApiGuard.class);
    private final AuthService auth;private final ObjectMapper mapper;private final com.fanzuixianchang.ops.OpsAuth ops;private final com.fanzuixianchang.ops.OpsMetrics metrics;
    private final WindowLimiter analysis=new WindowLimiter(15,Duration.ofMinutes(1),Clock.systemUTC());
    private final WindowLimiter tools=new WindowLimiter(120,Duration.ofMinutes(1),Clock.systemUTC());
    // 语音转写按音频秒数计费，额度要比目录查询紧；仍然允许连续重说几次。
    private final WindowLimiter speechLimit=new WindowLimiter(20,Duration.ofMinutes(1),Clock.systemUTC());
    // 账号读写单独计数。翻一遍「我的」要读账号、资料、授权、饭案、健康档案好几份数据，
    // 挤进同一个桶时既会把核算接口饿死，也会让一次翻页就把自己的额度耗掉。
    private final WindowLimiter account=new WindowLimiter(180,Duration.ofMinutes(1),Clock.systemUTC());
    private final WindowLimiter opsLimit=new WindowLimiter(120,Duration.ofMinutes(1),Clock.systemUTC());
    private final WindowLimiter login=new WindowLimiter(10,Duration.ofMinutes(1),Clock.systemUTC());
    public ApiGuard(AuthService auth,ObjectMapper mapper){this(auth,mapper,new com.fanzuixianchang.ops.OpsAuth(""));}
    public ApiGuard(AuthService auth,ObjectMapper mapper,com.fanzuixianchang.ops.OpsAuth ops){this(auth,mapper,ops,new com.fanzuixianchang.ops.OpsMetrics());}
    @org.springframework.beans.factory.annotation.Autowired
    public ApiGuard(AuthService auth,ObjectMapper mapper,com.fanzuixianchang.ops.OpsAuth ops,com.fanzuixianchang.ops.OpsMetrics metrics){this.auth=auth;this.mapper=mapper;this.ops=ops;this.metrics=metrics;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        String path=request.getServletPath();
        if(path.isEmpty())path=request.getRequestURI().substring(request.getContextPath().length());
        long started=System.nanoTime();boolean escaped=false;
        try{
            response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
            boolean admin=path.startsWith("/api/ops/");
            boolean content=(path.equals("/api/content/cases")||path.startsWith("/api/content/cases/")||path.equals("/api/content/announcement"))&&request.getMethod().equals("GET");
            boolean staticOps=request.getMethod().equals("GET")&&(path.startsWith("/ops/assets/")||java.util.Set.of("/","/index.html","/ops/","/ops/index.html","/ops/ops.js","/ops/ops.css").contains(path));
            boolean session=path.equals("/api/session");
            boolean accountPath=path.startsWith("/api/account");
            boolean speechPath=path.equals("/api/speech/recognize");
            boolean deterministic=path.equals("/api/foods")||path.equals("/api/nutrition/calculate")||path.equals("/api/goals/estimate")||path.equals("/api/activity/estimate")||accountPath;
            // CloudRun identity is injected for callContainer requests, including the explicit account bootstrap.
            // 已下架的功能：告诉旧客户端「没了」，别让它以为是登录失效。
            // 401 会把用户引到「重新登录」，而真正该做的是升级。
            if(path.equals("/api/content/starters"))throw new Problem(410,"GONE","首页 AI 首问建议已下架，请升级小程序后再试。");
            boolean analyze=!path.equals("/api/health")&&!session&&!content&&!staticOps&&!admin;
            if(admin){if(!opsLimit.allow(request.getRemoteAddr()))throw new Problem(429,"RATE_LIMITED","管理请求过于频繁。");ops.authenticate(request.getHeader("Authorization"));request.setAttribute("fanzuixianchang.adminActor",ops.actorName());}
            if(analyze||session){
                String subject=auth.authenticate(request.getHeader("Authorization"),request.getRemoteAddr(),request.getHeader("X-WX-OPENID"),request.getHeader("X-WX-APPID"),request.getHeader("X-WX-ENV"));
                request.setAttribute("fanzuixianchang.userIdentity",subject);
                if(session){if(!login.allow(subject))throw new Problem(429,"RATE_LIMITED","登录尝试过于频繁，请稍后重试。");}
                else{var limiter=accountPath?account:speechPath?speechLimit:deterministic?tools:analysis;if(!limiter.allow(subject))throw rateLimited(accountPath,speechPath);}
            }
            if((analyze||session||admin)&&(request.getMethod().equals("POST")||request.getMethod().equals("PUT"))){
                // 案卷内容包比一般的运营请求大得多（单案 ≤200KB，一次可发多案），
                // 图片上传走 base64 会膨胀约 1/3，都要单独放宽；其余管理请求仍是 64KB。
                int limit=path.equals("/api/account/photos")?3*1024*1024
                        :path.equals("/api/speech/recognize")?6*1024*1024
                        :path.equals("/api/ops/cases/images")?4*1024*1024
                        :path.startsWith("/api/ops/cases")?2*1024*1024
                        :path.equals("/api/cases/week-review")?32*1024
                        :session?4096
                        :path.equals("/api/account/meals")?2*1024*1024
                        :admin||deterministic?65536
                        :RequestRules.MAX_BODY_BYTES;
                if(request.getContentLengthLong()>limit)throw tooLarge();
                byte[] body=request.getInputStream().readNBytes(limit+1);
                if(body.length>limit)throw tooLarge();
                chain.doFilter(new BodyRequest(request,body),response);
            }else chain.doFilter(request,response);
        }catch(Problem e){response.setStatus(e.status());response.setContentType("application/json;charset=UTF-8");mapper.writeValue(response.getOutputStream(),ApiErrors.payload(e.code(),e.getMessage()));}
        catch(ServletException|IOException|RuntimeException e){escaped=true;throw e;}
        finally{
            int status=escaped?500:response.getStatus();long elapsed=System.nanoTime()-started;
            metrics.record(path,status,elapsed);
            String identity=(String)request.getAttribute("fanzuixianchang.userIdentity");
            String line=request.getMethod()+" "+path+" "+status+" "+(elapsed/1_000_000)+"ms identity="+(identity==null?"-":identity);
            if(path.equals("/api/health"))access.debug("{}",line);else access.info("{}",line);
        }
    }
    private static Problem tooLarge(){return new Problem(413,"REQUEST_TOO_LARGE","请求过大，请压缩图片或减少文字后重试。");}
    // 账号接口没有「线索」可保留，照搬分析文案会让人以为记录丢了。
    private static Problem rateLimited(boolean accountPath,boolean speechPath){
        if(speechPath)return new Problem(429,"SPEECH_BUSY","语音识别有点密集，请稍等再试，已输入的文字会保留。");
        return accountPath
            ? new Problem(429,"RATE_LIMITED","账号信息请求有点密集，稍后会自动重试；本机记录仍在。")
            : new Problem(429,"RATE_LIMITED","请求有点密集，稍后再来，已输入线索会保留。");
    }
    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BodyRequest(HttpServletRequest request,byte[] body){super(request);this.body=body;}
        @Override public ServletInputStream getInputStream(){
            var input=new ByteArrayInputStream(body);
            return new ServletInputStream(){
                @Override public int read(){return input.read();}
                @Override public int read(byte[] b,int off,int len){return input.read(b,off,len);}
                @Override public boolean isFinished(){return input.available()==0;}
                @Override public boolean isReady(){return true;}
                @Override public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Only blocking MVC requests supported");}
            };
        }
        @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
    }
}

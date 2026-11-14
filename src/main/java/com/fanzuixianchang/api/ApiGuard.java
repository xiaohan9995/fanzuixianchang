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
    private final AuthService auth;private final ObjectMapper mapper;private final com.fanzuixianchang.ops.OpsAuth ops;private final com.fanzuixianchang.ops.OpsMetrics metrics;
    private final WindowLimiter analysis=new WindowLimiter(15,Duration.ofMinutes(1),Clock.systemUTC());
    private final WindowLimiter tools=new WindowLimiter(120,Duration.ofMinutes(1),Clock.systemUTC());
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
            boolean content=path.equals("/api/content/starters")&&request.getMethod().equals("GET");
            boolean staticOps=request.getMethod().equals("GET")&&java.util.Set.of("/","/index.html","/ops/","/ops/index.html","/ops/ops.js","/ops/ops.css").contains(path);
            boolean session=path.equals("/api/session");
            boolean accountPath=path.startsWith("/api/account");
            boolean deterministic=path.equals("/api/foods")||path.equals("/api/nutrition/calculate")||path.equals("/api/goals/estimate")||accountPath;
            // Protect every route except the two exact public endpoints. Matrix/encoded variants cannot bypass auth.
            boolean analyze=!path.equals("/api/health")&&!session&&!content&&!staticOps&&!admin;
            if(admin){if(!opsLimit.allow(request.getRemoteAddr()))throw new Problem(429,"RATE_LIMITED","管理请求过于频繁。");ops.authenticate(request.getHeader("Authorization"));}
            if(analyze){
                String subject=auth.authenticate(request.getHeader("Authorization"),request.getRemoteAddr());
                request.setAttribute("fanzuixianchang.userIdentity",subject);
                if(!(deterministic?tools:analysis).allow(subject))throw new Problem(429,"RATE_LIMITED","请求有点密集，稍后再来，已输入线索会保留。");
            }
            if(session&&!login.allow(request.getRemoteAddr()))throw new Problem(429,"RATE_LIMITED","登录尝试过于频繁，请稍后重试。");
            if((analyze||session||admin)&&(request.getMethod().equals("POST")||request.getMethod().equals("PUT"))){
                int limit=session?4096:path.equals("/api/account/meals")?2*1024*1024:admin||deterministic?65536:RequestRules.MAX_BODY_BYTES;
                if(request.getContentLengthLong()>limit)throw tooLarge();
                byte[] body=request.getInputStream().readNBytes(limit+1);
                if(body.length>limit)throw tooLarge();
                chain.doFilter(new BodyRequest(request,body),response);
            }else chain.doFilter(request,response);
        }catch(Problem e){response.setStatus(e.status());response.setContentType("application/json;charset=UTF-8");mapper.writeValue(response.getOutputStream(),ApiErrors.payload(e.code(),e.getMessage()));}
        catch(ServletException|IOException|RuntimeException e){escaped=true;throw e;}
        finally{metrics.record(path,escaped?500:response.getStatus(),System.nanoTime()-started);}
    }
    private static Problem tooLarge(){return new Problem(413,"REQUEST_TOO_LARGE","请求过大，请压缩图片或减少文字后重试。");}
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

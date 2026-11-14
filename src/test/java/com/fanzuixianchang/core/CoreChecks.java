package com.fanzuixianchang.core;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free behavior checks; also invoked by the JUnit adapter. */
public final class CoreChecks {
    private static final AtomicInteger checks = new AtomicInteger();
    public static void main(String[] args) {
        check(LabelCalculator.calculate(200,500,"kJ").total() == 1000, "label uses amount / 100");
        rejects(() -> LabelCalculator.calculate(Double.NaN,100,"kJ"), "invalid finite number");
        rejects(() -> LabelCalculator.calculate(20,-1,"g"), "negative amount");
        rejects(() -> LabelCalculator.calculate(20,100,"ounces"), "unsupported unit");
        var request = request("after","solo","这顿饭",Map.of(),Map.of());
        check(RequestRules.validate(request).preferences().isEmpty(), "memory disabled by default");
        var consentOff = request("after","solo","这顿饭",Map.of(),Map.of("memoryEnabled",false,"dislikes",List.of("香菜")));
        check(RequestRules.validate(consentOff).preferences().isEmpty(), "opted-out prefs excluded");
        var consentOn = request("before","solo","选饭",Map.of(),Map.of("memoryEnabled",true,"dislikes",List.of("香菜")));
        check(RequestRules.validate(consentOn).preferences().get("dislikes").equals(List.of("香菜")), "consented prefs available");
        rejects(() -> RequestRules.validate(request("wrong","solo","饭",Map.of(),Map.of())), "invalid mode");
        rejects(() -> RequestRules.validate(request("before","group","菜单",Map.of("people",0),Map.of())), "group people invalid");
        var badImage = new Contracts.AnalyzeRequest("case-1",1,"after","solo","",Map.of(),List.of(new Contracts.ImageInput("image/png",Base64.getEncoder().encodeToString("not an image".getBytes()))),Map.of(),null);
        rejects(() -> RequestRules.validate(badImage), "image magic mismatch");
        var input = RequestRules.validate(request);
        var good = report();
        var cleaned = ReportRules.apply(good,input);
        check(cleaned.get("scope").equals("personal"),"scope server controlled");
        var forged = report(); forged.put("sources",List.of(Map.of("id","evil","url","https://evil.invalid")));
        rejects(() -> ReportRules.apply(forged,input),"forged sources rejected");
        var duplicate = report(); duplicate.put("choices",List.of(Map.of("id","a","name","甲","reason","理由","tradeoff",""),Map.of("id","a","name","乙","reason","理由","tradeoff","")));
        rejects(() -> ReportRules.apply(duplicate,input),"duplicate choice IDs rejected");
        var fakeNumber = report();fakeNumber.put("summary","这一顿是756千卡，建议每天只吃900千卡。");
        rejects(() -> ReportRules.apply(fakeNumber,input),"unsupported nutrient values rejected");
        var label = RequestRules.validate(request("label","solo","标签",Map.of("per100",200,"amount",500,"unit","kJ"),Map.of()));
        var modelLie=report();modelLie.put("calculation",Map.of("total",1));
        var labelReport=ReportRules.apply(modelLie,label);
        check(((Map<?,?>)labelReport.get("calculation")).get("total").equals(1000.0), "calculation overrides model");
        var grouped=RequestRules.validate(request("after","group","整桌菜",Map.of("people",3),Map.of()));
        check(ReportRules.apply(report(),grouped).get("scope").equals("table"),"table never personal by model");
        var clock=Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"),ZoneOffset.UTC);
        var tokens=new SessionTokens("a-secret-with-at-least-thirty-two-characters",clock);
        var token=tokens.issue("openid-example");
        check(tokens.verify(token.token()).isPresent(),"signed session verifies");
        check(tokens.verify(token.token()+"x").isEmpty(),"tampered token fails");
        var expired=new SessionTokens("a-secret-with-at-least-thirty-two-characters",Clock.offset(clock,Duration.ofHours(3)));
        check(expired.verify(token.token()).isEmpty(),"expired token fails");
        check(!SessionTokens.allowDevToken(false,"127.0.0.1","local-development-token-123456","local-development-token-123456"),"dev token disabled production");
        check(!SessionTokens.allowDevToken(true,"192.168.1.2","local-development-token-123456","local-development-token-123456"),"dev token loopback only");
        check(SessionTokens.allowDevToken(true,"127.0.0.1","local-development-token-123456","local-development-token-123456"),"explicit local token works");
        var limits=new WindowLimiter(2,Duration.ofMinutes(1),clock);
        check(limits.allow("user-a")&&limits.allow("user-a")&&!limits.allow("user-a"),"session burst bounded");
        check(limits.allow("user-b"),"separate sessions isolated");
        System.out.println("PASS " + checks.get() + " backend core behavior checks");
    }
    private static Contracts.AnalyzeRequest request(String mode,String scene,String text,Map<String,Object> conditions,Map<String,Object> prefs) {
        return new Contracts.AnalyzeRequest("case-1",1,mode,scene,text,conditions,List.of(),prefs,null);
    }
    private static Map<String,Object> report() {
        var r=new LinkedHashMap<String,Object>();
        r.put("title","现场分析");r.put("summary","先核对实际食用情况。");r.put("quip","结论可以改，证据不能编。");
        r.put("choices",List.of());r.put("foods",List.of());
        r.put("evidence",List.of(Map.of("kind","guideline","text","重视长期、合理的饮食安排。","sourceId","H01")));
        r.put("unknowns",List.of("实际份量尚未确认"));r.put("suggestion","先补充实际吃了多少。");r.put("sources",List.of(Map.of("id","H01")));r.put("questions",List.of("这顿吃了多少？"));
        return r;
    }
    private static void check(boolean ok,String label) { if(!ok)throw new AssertionError(label);checks.incrementAndGet(); }
    private static void rejects(Runnable action,String label) {try{action.run();throw new AssertionError("accepted: "+label);}catch(Problem expected){checks.incrementAndGet();}}
}

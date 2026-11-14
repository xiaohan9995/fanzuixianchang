package com.fanzuixianchang.api;

import com.fanzuixianchang.ai.ModelGateway;
import com.fanzuixianchang.auth.AuthService;
import com.fanzuixianchang.core.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public final class ApiController {
    private final ModelGateway model;private final AuthService auth;
    public ApiController(ModelGateway model,AuthService auth){this.model=model;this.auth=auth;}
    @GetMapping("/health") public Map<String,Object> health(){return Map.of("status","ok","model","deepseek-flash","configured",model.configured());}
    @PostMapping("/session") public SessionTokens.Issued session(@RequestBody Contracts.SessionRequest request){return auth.exchange(request.code());}
    @PostMapping("/cases/analyze") public Contracts.AnalysisResponse analyze(@RequestBody Contracts.AnalyzeRequest request){
        var input=RequestRules.validate(request);
        if(!model.configured())throw new Problem(503,"NOT_CONFIGURED","服务端尚未配置 DeepSeek，请先体验示例或联系开发者完成配置。");
        return model.analyze(input);
    }
}

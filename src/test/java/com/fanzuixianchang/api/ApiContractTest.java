package com.fanzuixianchang.api;

import com.fanzuixianchang.ai.ModelGateway;
import com.fanzuixianchang.auth.AuthService;
import org.springframework.mock.env.MockEnvironment;
import com.fanzuixianchang.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiContractTest {
    @Test void unconfiguredModelIsHonest() throws Exception {
        ModelGateway unavailable=new ModelGateway(){public boolean configured(){return false;}public Contracts.AnalysisResponse analyze(Contracts.ValidCase c){throw new AssertionError("must not call");}};
        var mvc=MockMvcBuilders.standaloneSetup(new ApiController(unavailable,null)).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false)).andExpect(jsonPath("$.model").value("deepseek-flash"));
        mvc.perform(post("/api/cases/analyze").contentType("application/json").content("{\"caseId\":\"c1\",\"revision\":1,\"mode\":\"after\",\"scene\":\"solo\",\"text\":\"午饭\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("NOT_CONFIGURED"));
    }
    @Test void malformedInputDoesNotInvokeProvider() throws Exception {
        ModelGateway never=new ModelGateway(){public boolean configured(){return true;}public Contracts.AnalysisResponse analyze(Contracts.ValidCase c){throw new AssertionError("invalid request reached model");}};
        var mvc=MockMvcBuilders.standaloneSetup(new ApiController(never,null)).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(post("/api/cases/analyze").contentType("application/json").content("{\"caseId\":\"c1\",\"revision\":1,\"mode\":\"invalid\",\"scene\":\"solo\",\"text\":\"午饭\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }
    @Test void analysisRequiresAuthAndSessionIsExplicitlyUnconfigured() throws Exception {
        var mapper=new ObjectMapper();var env=new MockEnvironment();env.setActiveProfiles("local");
        var auth=new AuthService("","","","local-development-token-123456","127.0.0.1",env,mapper);
        ModelGateway never=new ModelGateway(){public boolean configured(){return false;}public Contracts.AnalysisResponse analyze(Contracts.ValidCase c){throw new AssertionError();}};
        var mvc=MockMvcBuilders.standaloneSetup(new ApiController(never,auth)).setControllerAdvice(new ApiErrors()).addFilters(new ApiGuard(auth,mapper)).build();
        mvc.perform(post("/api/cases/analyze").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/cases/analyze;ignored").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/session").contentType("application/json").content("{\"code\":\"temporary-code\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("NOT_CONFIGURED"));
        mvc.perform(post("/api/cases/analyze").header("Authorization","Bearer local-development-token-123456")
            .contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
    }

}

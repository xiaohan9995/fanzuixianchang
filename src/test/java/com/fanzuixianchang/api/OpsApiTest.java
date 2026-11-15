package com.fanzuixianchang.api;
import com.fanzuixianchang.ai.ModelGateway;
import com.fanzuixianchang.auth.AuthService;
import com.fanzuixianchang.core.Contracts;
import com.fanzuixianchang.ops.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class OpsApiTest {
 @TempDir Path dir;
 @Test void adminIsIndependentAndPublicContentIsReadOnly()throws Exception{
 var mapper=new ObjectMapper();var env=new MockEnvironment();env.setActiveProfiles("local");var auth=new AuthService("","","","local-development-token-123456","127.0.0.1",env,mapper);
 var model=new ModelGateway(){public boolean configured(){return false;}public Contracts.AnalysisResponse analyze(Contracts.ValidCase i){throw new AssertionError();}};
 var metrics=new OpsMetrics();
 var mvc=MockMvcBuilders.standaloneSetup(new OpsController(new OpsStore(mapper,dir.toString()),model,metrics)).setControllerAdvice(new ApiErrors()).addFilters(new ApiGuard(auth,mapper,new OpsAuth("operations-test-token-at-least-32-characters"),metrics)).build();
 mvc.perform(get("/api/content/starters")).andExpect(status().isOk()).andExpect(jsonPath("$.entries.length()").value(4));
 mvc.perform(post("/api/content/starters").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
 mvc.perform(get("/api/ops/content").header("Authorization","Bearer local-development-token-123456")).andExpect(status().isUnauthorized());
 mvc.perform(get("/api/ops/overview").header("Authorization","Bearer operations-test-token-at-least-32-characters")).andExpect(status().isOk()).andExpect(jsonPath("$.modelConfigured").value(false)).andExpect(jsonPath("$.metricsAvailable").value(true));
 mvc.perform(get("/api/foods").header("Authorization","Bearer operations-test-token-at-least-32-characters")).andExpect(status().isUnauthorized());
 mvc.perform(get("/api/ops/metrics").header("Authorization","Bearer operations-test-token-at-least-32-characters")).andExpect(status().isOk()).andExpect(jsonPath("$.routes[?(@.path == '/api/foods')].requests").value(org.hamcrest.Matchers.contains(1))).andExpect(jsonPath("$.routes[?(@.path == '/api/foods')].statusCounts.401").value(org.hamcrest.Matchers.contains(1)));
 mvc.perform(put("/api/ops/content").header("Authorization","Bearer operations-test-token-at-least-32-characters").contentType("application/json").content("{\"version\":0,\"entries\":[]}")).andExpect(status().isBadRequest());
 }
 @Test void adminCanBeTemporarilyOpenedWithoutBreakingTheAuthenticatedPath()throws Exception{
 var mapper=new ObjectMapper();var env=new MockEnvironment();env.setActiveProfiles("local");var auth=new AuthService("","","","local-development-token-123456","127.0.0.1",env,mapper);
 var model=new ModelGateway(){public boolean configured(){return false;}public Contracts.AnalysisResponse analyze(Contracts.ValidCase i){throw new AssertionError();}};
 var metrics=new OpsMetrics();
 var mvc=MockMvcBuilders.standaloneSetup(new OpsController(new OpsStore(mapper,dir.resolve("open").toString()),model,metrics)).setControllerAdvice(new ApiErrors()).addFilters(new ApiGuard(auth,mapper,OpsAuth.open(),metrics)).build();
 mvc.perform(get("/api/ops/overview")).andExpect(status().isOk()).andExpect(jsonPath("$.metricsAvailable").value(true));
 }
}

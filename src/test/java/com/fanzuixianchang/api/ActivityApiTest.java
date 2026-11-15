package com.fanzuixianchang.api;
import com.fanzuixianchang.ai.ModelGateway;
import com.fanzuixianchang.auth.AuthService;
import com.fanzuixianchang.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ActivityApiTest {
 private static final String TOKEN="activity-development-token-123";
 private static final String INPUT="{\"text\":\"快走 30 分钟\",\"minutes\":30,\"weightKg\":62}";
 private MockMvc mvc(boolean enabled){var env=new MockEnvironment();env.setActiveProfiles("local");var mapper=new ObjectMapper();var auth=new AuthService("","","",TOKEN,"127.0.0.1",env,mapper);var model=new ModelGateway(){public boolean configured(){return true;}public Contracts.AnalysisResponse analyze(Contracts.ValidCase c){throw new AssertionError();}public Map<String,Object> estimateActivity(ActivityRules.Valid input){return ActivityRules.apply(Map.of("items",List.of(Map.of("name","快走","minutes",30,"intensity","中","metMin",3.5,"metMax",4.5))),input);}};return MockMvcBuilders.standaloneSetup(new ActivityController(model,enabled)).setControllerAdvice(new ApiErrors()).addFilters(new ApiGuard(auth,mapper)).build();}
 @Test void authenticationAndFeatureFlagAreEnforced()throws Exception{mvc(true).perform(post("/api/activity/estimate").contentType("application/json").content(INPUT)).andExpect(status().isUnauthorized());mvc(false).perform(post("/api/activity/estimate").header("Authorization","Bearer "+TOKEN).contentType("application/json").content(INPUT)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("ESTIMATE_DISABLED"));}
 @Test void estimatedWorkoutAndItsBasisReachClient()throws Exception{mvc(true).perform(post("/api/activity/estimate").header("Authorization","Bearer "+TOKEN).contentType("application/json").content(INPUT)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value("activity-met-v1")).andExpect(jsonPath("$.items[0].name").value("快走")).andExpect(jsonPath("$.items[0].minutes").value(30)).andExpect(jsonPath("$.totalKcal").value(130)).andExpect(jsonPath("$.weightSource").value("user")).andExpect(jsonPath("$.assumptions").isArray());}
 @Test void rejectsEmptyDescriptionAndImplausibleDuration()throws Exception{mvc(true).perform(post("/api/activity/estimate").header("Authorization","Bearer "+TOKEN).contentType("application/json").content(INPUT.replace("快走 30 分钟",""))).andExpect(status().isBadRequest());mvc(true).perform(post("/api/activity/estimate").header("Authorization","Bearer "+TOKEN).contentType("application/json").content(INPUT.replace("30,\"weightKg\"","0,\"weightKg\""))).andExpect(status().isBadRequest());}
}

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
class MealEstimateApiTest {
 private static final String TOKEN="estimate-development-token-123";
 private static final String INPUT="{\"caseId\":\"c1\",\"revision\":1,\"text\":\"一份午饭\",\"images\":[],\"scene\":\"solo\"}";
 private MockMvc mvc(boolean enabled){var env=new MockEnvironment();env.setActiveProfiles("local");var mapper=new ObjectMapper();var auth=new AuthService("","","",TOKEN,"127.0.0.1",env,mapper);var model=new ModelGateway(){public boolean configured(){return true;}public Contracts.AnalysisResponse analyze(Contracts.ValidCase c){throw new AssertionError();}public Map<String,Object> estimateMeal(Contracts.ValidCase c){return MealEstimateRules.apply(Map.of("items",List.of(Map.of("name","番茄炒蛋","unit","g","amountMin",100,"amountMax",150,"energyKcal",Map.of("min",130,"max",240),"portionSource","image-estimate","assumptions",List.of("按家常做法估算")))),FoodCatalog.load(),"test-model");}};return MockMvcBuilders.standaloneSetup(new MealEstimateController(model,enabled)).setControllerAdvice(new ApiErrors()).addFilters(new ApiGuard(auth,mapper)).build();}
 @Test void authenticationAndFeatureFlagAreEnforced()throws Exception{mvc(true).perform(post("/api/cases/estimate-meal").contentType("application/json").content(INPUT)).andExpect(status().isUnauthorized());mvc(false).perform(post("/api/cases/estimate-meal").header("Authorization","Bearer "+TOKEN).contentType("application/json").content(INPUT)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("ESTIMATE_DISABLED"));}
 @Test void estimatedItemAndProvenanceReachClient()throws Exception{mvc(true).perform(post("/api/cases/estimate-meal").header("Authorization","Bearer "+TOKEN).contentType("application/json").content(INPUT)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value("meal-estimate-v1")).andExpect(jsonPath("$.items[0].type").value("estimate")).andExpect(jsonPath("$.items[0].estimate.source.kind").value("ai")).andExpect(jsonPath("$.representativeKcal").value(185));}
 @Test void rejectsEmptyInputAndTableScope()throws Exception{mvc(true).perform(post("/api/cases/estimate-meal").header("Authorization","Bearer "+TOKEN).contentType("application/json").content(INPUT.replace("一份午饭",""))).andExpect(status().isBadRequest());mvc(true).perform(post("/api/cases/estimate-meal").header("Authorization","Bearer "+TOKEN).contentType("application/json").content(INPUT.replace("solo","group"))).andExpect(status().isBadRequest());}
}

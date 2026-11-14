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
class FoodIdentificationApiTest {
 private static final String TOKEN="identification-development-token-123";
 private static final String INPUT="{\"caseId\":\"c1\",\"revision\":1,\"text\":\"一份米饭\",\"images\":[],\"scene\":\"solo\"}";
 MockMvc mvc(boolean configured){var env=new MockEnvironment();env.setActiveProfiles("local");var mapper=new ObjectMapper();var auth=new AuthService("","","",TOKEN,"127.0.0.1",env,mapper);var model=new ModelGateway(){public boolean configured(){return configured;}public Contracts.AnalysisResponse analyze(Contracts.ValidCase i){throw new AssertionError();}public Map<String,Object> identifyFoods(Contracts.ValidCase input){return IdentificationRules.apply(Map.of("items",List.of(Map.of("name","米饭","preparation","熟","searchTerms",List.of("米饭")))),input,FoodCatalog.load());}};return MockMvcBuilders.standaloneSetup(new FoodIdentificationController(model)).setControllerAdvice(new ApiErrors()).addFilters(new ApiGuard(auth,mapper)).build();}
 @Test void authRequiredAndUnavailableModelIsNotFakeSuccess()throws Exception{mvc(true).perform(post("/api/cases/identify-foods").contentType("application/json").content(INPUT)).andExpect(status().isUnauthorized());mvc(false).perform(post("/api/cases/identify-foods").header("Authorization","Bearer "+TOKEN).contentType("application/json").content(INPUT)).andExpect(status().isServiceUnavailable());}
 @Test void validatedFoodCandidatesAndUnknownPortionReachClient()throws Exception{mvc(true).perform(post("/api/cases/identify-foods").header("Authorization","Bearer "+TOKEN).contentType("application/json").content(INPUT)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value("food-identification-v1")).andExpect(jsonPath("$.items[0].candidates[0].source.url").exists()).andExpect(jsonPath("$.items[0].portion").isEmpty());}
 @Test void rejectsTableAndBrokenImages()throws Exception{mvc(true).perform(post("/api/cases/identify-foods").header("Authorization","Bearer "+TOKEN).contentType("application/json").content(INPUT.replace("solo","group"))).andExpect(status().isBadRequest());mvc(true).perform(post("/api/cases/identify-foods").header("Authorization","Bearer "+TOKEN).contentType("application/json").content(INPUT.replace("\"images\":[]","\"images\":[{\"mimeType\":\"image/png\",\"base64\":\"dGV4dA==\"}]"))).andExpect(status().isBadRequest());}
}

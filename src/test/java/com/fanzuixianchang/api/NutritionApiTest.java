package com.fanzuixianchang.api;
import com.fanzuixianchang.auth.AuthService;
import com.fanzuixianchang.core.NutritionChecks;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NutritionApiTest {
 MockMvc mvc(){var env=new MockEnvironment();env.setActiveProfiles("local");var mapper=new ObjectMapper();var auth=new AuthService("","","","nutrition-development-token-123","127.0.0.1",env,mapper);return MockMvcBuilders.standaloneSetup(new NutritionController()).setControllerAdvice(new ApiErrors()).addFilters(new ApiGuard(auth,mapper)).build();}
 @Test void pureChecks(){NutritionChecks.main(new String[0]);}
 @Test void lookupRequiresAuthAndWorksWithoutModelKey()throws Exception{
  var mvc=mvc();mvc.perform(get("/api/foods").param("q","米饭")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/foods").param("q","米饭").header("Authorization","Bearer nutrition-development-token-123")).andExpect(status().isOk()).andExpect(jsonPath("$.foods[0].basisUnit").value("g")).andExpect(jsonPath("$.foods[0].source.version").value("2018-04"));
 }
 @Test void validNutritionUsesSourceAndUnknownStatus()throws Exception{
  mvc().perform(post("/api/nutrition/calculate").header("Authorization","Bearer nutrition-development-token-123").contentType("application/json").content("{\"items\":[{\"id\":\"a\",\"name\":\"米饭\",\"type\":\"food\",\"foodId\":\"168878\",\"amountMin\":100,\"amountMax\":200,\"unit\":\"g\"},{\"id\":\"b\",\"name\":\"酱料\",\"type\":\"unknown\"}]}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.energyKcal.min").value(130)).andExpect(jsonPath("$.energyKcal.max").value(260)).andExpect(jsonPath("$.complete").value(false)).andExpect(jsonPath("$.unknownCount").value(1));
 }
 @Test void malformedGoalRejectsRatherThanGuessing()throws Exception{
  mvc().perform(post("/api/goals/estimate").header("Authorization","Bearer nutrition-development-token-123").contentType("application/json").content("{\"age\":\"30\"}")) .andExpect(status().isBadRequest());
 }
 @Test void goalWorksWithoutModelKey()throws Exception{
  mvc().perform(post("/api/goals/estimate").header("Authorization","Bearer nutrition-development-token-123").contentType("application/json").content("{\"age\":30,\"sex\":\"male\",\"heightCm\":170,\"weightKg\":70,\"activityFactor\":1.6,\"screening\":{\"pregnantOrBreastfeeding\":false,\"medicalNutrition\":false,\"eatingDisorder\":false}}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.eligible").value(true)).andExpect(jsonPath("$.reeKcal").value(1617.5)).andExpect(jsonPath("$.maintenanceKcal").value(2600));
 }
}

package com.fanzuixianchang.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanzuixianchang.auth.AuthService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 账号读写曾和分析类接口共用一个每分钟 120 次的桶：翻一遍「我的」要读账号、资料、授权、饭案、
 * 健康档案好几份数据，一次来回就把自己的额度耗光，界面上还会弹出「已输入线索会保留」——
 * 而账号接口根本没有线索。现在账号单独计数、单独文案。
 */
class ApiRateLimitTest {
    @RestController static class Stub {
        @GetMapping("/api/foods") @ResponseBody Map<String,Object> foods(){return Map.of("ok",true);}
        @GetMapping("/api/account") @ResponseBody Map<String,Object> account(){return Map.of("ok",true);}
        @GetMapping("/api/account/profile") @ResponseBody Map<String,Object> profile(){return Map.of("ok",true);}
    }

    private org.springframework.test.web.servlet.MockMvc mvc(){
        var mapper=new ObjectMapper();var env=new MockEnvironment();env.setActiveProfiles("local");
        var auth=new AuthService("","","","local-development-token-123456","127.0.0.1",env,mapper);
        return MockMvcBuilders.standaloneSetup(new Stub()).setControllerAdvice(new ApiErrors())
            .addFilters(new ApiGuard(auth,mapper)).build();
    }
    private static final String AUTH="Bearer local-development-token-123456";

    @Test void accountTrafficCannotStarveTheToolEndpoints() throws Exception {
        var mvc=mvc();
        for(int i=0;i<120;i++)mvc.perform(get("/api/foods").header("Authorization",AUTH)).andExpect(status().isOk());
        // 核算类超出自己的额度后按原来的文案拒绝，不该被账号流量影响方向。
        mvc.perform(get("/api/foods").header("Authorization",AUTH))
            .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        // 账号是另一个桶，核算被限流时它仍然可用。
        mvc.perform(get("/api/account").header("Authorization",AUTH)).andExpect(status().isOk());
        mvc.perform(get("/api/account/profile").header("Authorization",AUTH)).andExpect(status().isOk());
    }

    @Test void accountLimitSpeaksAboutAccountDataNotClues() throws Exception {
        var mvc=mvc();
        for(int i=0;i<180;i++)mvc.perform(get("/api/account").header("Authorization",AUTH)).andExpect(status().isOk());
        mvc.perform(get("/api/account").header("Authorization",AUTH))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("账号信息")))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("线索"))));
        // 账号桶满了也不该连累核算接口。
        mvc.perform(get("/api/foods").header("Authorization",AUTH)).andExpect(status().isOk());
    }
}

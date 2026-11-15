package com.fanzuixianchang.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UserDataConfiguration {
    @Bean public UserDataStore userDataStore(ObjectMapper mapper,
        @Value("${app.ops.mysql-url:}")String url,
        @Value("${app.ops.mysql-user:}")String user,
        @Value("${app.ops.mysql-password:}")String password){
        return new UserDataStore(mapper,url,user,password);
    }
    @Bean public AdminUserStore adminUserStore(ObjectMapper mapper,
        @Value("${app.ops.mysql-url:}")String url,
        @Value("${app.ops.mysql-user:}")String user,
        @Value("${app.ops.mysql-password:}")String password){
        return new AdminUserStore(mapper,url,user,password);
    }
}

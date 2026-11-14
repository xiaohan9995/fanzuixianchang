package com.fanzuixianchang.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

final class DeepSeekModelFactory {
    private DeepSeekModelFactory() {}
    static ChatModel create(String apiKey,String baseUrl,String model,int timeoutSeconds){
        if(apiKey==null||apiKey.isBlank())return null;
        var uri=URI.create(baseUrl);
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)
            throw new IllegalArgumentException("DEEPSEEK_BASE_URL must be an HTTPS API origin");
        if(!"deepseek-flash".equals(model))throw new IllegalArgumentException("This MVP is verified for DeepSeek V4.1 Flash model ID deepseek-flash");
        if(timeoutSeconds<5||timeoutSeconds>55)throw new IllegalArgumentException("AI_TIMEOUT_SECONDS must be between 5 and 55");
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var factory=new JdkClientHttpRequestFactory(http);factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        var api=OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).completionsPath("/chat/completions")
            .restClientBuilder(RestClient.builder().requestFactory(factory)).build();
        var options=OpenAiChatOptions.builder().model(model).temperature(0.4).maxTokens(4096)
            .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
            .extraBody(Map.of("thinking",Map.of("type","disabled"))).build();
        return OpenAiChatModel.builder().openAiApi(api).defaultOptions(options)
            .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
    }
}

package com.fanzuixianchang.ai;

import com.fanzuixianchang.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpringAiGatewayTest {
    private static final String GOOD="""
        {"report":{"title":"午饭调查","summary":"先核对实际食用情况。","quip":"结论可以改，证据不能编。","choices":[],"foods":[],"evidence":[{"kind":"guideline","text":"关注长期合理安排。","sourceId":"H01"}],"unknowns":["实际份量"],"suggestion":"补充实际食用情况。","sources":[{"id":"H01","url":"https://forged.invalid"}],"questions":["吃了多少？"],"scope":"personal"},"answer":""}
        """;
    @Test void mediaAndCurrentFactsReachRealSpringMessages(){
        ChatModel model=mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(GOOD)))));
        var gateway=new SpringAiGateway(model,new ObjectMapper());
        byte[] image={(byte)137,80,78,71,13,10,26,10,0,0,0,0};
        var request=new Contracts.AnalyzeRequest("case-1",1,"after","solo","我的午饭",Map.of(),List.of(new Contracts.ImageInput("image/png",Base64.getEncoder().encodeToString(image))),Map.of("memoryEnabled",false,"dislikes",List.of("旧香菜偏好")),null);
        var response=gateway.analyze(RequestRules.validate(request));
        var prompt=ArgumentCaptor.forClass(Prompt.class);verify(model).call(prompt.capture());
        var user=(UserMessage)prompt.getValue().getInstructions().get(1);
        assertEquals(1,user.getMedia().size());assertArrayEquals(image,user.getMedia().get(0).getDataAsByteArray());
        assertTrue(user.getText().contains("我的午饭"));assertFalse(user.getText().contains("旧香菜偏好"));
        var sources=(List<?>)response.report().get("sources");
        assertEquals(Knowledge.SOURCES.get("H01"),sources.get(0));
    }
    @Test void blankOrMalformedResponseNeverBecomesSuccess(){
        ChatModel model=mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("   ")))));
        var gateway=new SpringAiGateway(model,new ObjectMapper());
        var problem=assertThrows(Problem.class,()->gateway.analyze(simple()));
        assertEquals("INVALID_MODEL_OUTPUT",problem.code());
    }
    @Test void upstreamDetailsAreNotReturned(){
        ChatModel model=mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("provider body and secret must not reach client"));
        var gateway=new SpringAiGateway(model,new ObjectMapper());
        var problem=assertThrows(Problem.class,()->gateway.analyze(simple()));
        assertEquals("MODEL_UNAVAILABLE",problem.code());assertFalse(problem.getMessage().contains("secret"));
    }
    private Contracts.ValidCase simple(){return RequestRules.validate(new Contracts.AnalyzeRequest("c1",1,"after","solo","午饭",Map.of(),List.of(),Map.of(),null));}
}

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
class MealEstimateGatewayTest {
 private static final String VALID="{\"items\":[{\"name\":\"番茄炒蛋\",\"unit\":\"g\",\"amountMin\":180,\"amountMax\":240,\"energyKcal\":{\"min\":220,\"max\":340},\"portionSource\":\"image-estimate\",\"assumptions\":[\"用油量未知，按家常做法估算\"]}],\"question\":null}";
 private ChatResponse reply(String text){return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));}
 private Contracts.ValidCase input(boolean image){byte[] bytes={(byte)137,80,78,71,13,10,26,10,0,0,0,0};return IdentificationRules.validate(new Contracts.IdentifyRequest("c1",2,"午饭",image?List.of(new Contracts.ImageInput("image/png",Base64.getEncoder().encodeToString(bytes))):List.of(),"solo"));}
 @Test void sendsActualMediaAndReturnsEstimatedMealWithoutDatabaseMatch(){var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(reply(VALID));var result=new SpringAiGateway(model,new ObjectMapper()).estimateMeal(input(true));assertEquals("meal-estimate-v1",result.get("version"));assertEquals(280.0,result.get("representativeKcal"));var captor=ArgumentCaptor.forClass(Prompt.class);verify(model).call(captor.capture());assertEquals(1,((UserMessage)captor.getValue().getInstructions().get(1)).getMedia().size());assertFalse(captor.getValue().getContents().contains("heightCm"));}
 @Test void malformedOutputGetsOnlyOneRepair(){var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(reply("not json"),reply(VALID));assertEquals(280.0,new SpringAiGateway(model,new ObjectMapper()).estimateMeal(input(false)).get("representativeKcal"));verify(model,times(2)).call(any(Prompt.class));}
 @Test void twoInvalidResultsStopWithoutFakeSuccess(){var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(reply("{}"));var error=assertThrows(Problem.class,()->new SpringAiGateway(model,new ObjectMapper()).estimateMeal(input(false)));assertEquals("INVALID_MODEL_OUTPUT",error.code());verify(model,times(2)).call(any(Prompt.class));}
 @Test void providerFailureIsSafeAndNotRetried(){var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("secret-api-key upstream body"));var error=assertThrows(Problem.class,()->new SpringAiGateway(model,new ObjectMapper()).estimateMeal(input(true)));assertEquals("MODEL_UNAVAILABLE",error.code());assertFalse(error.getMessage().contains("secret"));verify(model).call(any(Prompt.class));}
}

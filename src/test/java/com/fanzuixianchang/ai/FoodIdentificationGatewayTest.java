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
class FoodIdentificationGatewayTest {
 @Test void identificationUsesRealMediaAndServerCatalogCandidates(){
  ChatModel model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"items\":[{\"name\":\"米饭\",\"preparation\":\"熟\",\"searchTerms\":[\"米饭\"],\"portion\":null,\"unknowns\":[\"份量未知\"]}]}")))));
  var gateway=new SpringAiGateway(model,new ObjectMapper());byte[] image={(byte)137,80,78,71,13,10,26,10,0,0,0,0};
  var input=IdentificationRules.validate(new Contracts.IdentifyRequest("c1",1,"午饭",List.of(new Contracts.ImageInput("image/png",Base64.getEncoder().encodeToString(image))),"solo"));
  var result=gateway.identifyFoods(input);assertEquals("food-identification-v1",result.get("version"));var row=(Map<?,?>)((List<?>)result.get("items")).get(0);assertNull(row.get("portion"));assertFalse(((List<?>)row.get("candidates")).isEmpty());
  var prompt=ArgumentCaptor.forClass(Prompt.class);verify(model).call(prompt.capture());var user=(UserMessage)prompt.getValue().getInstructions().get(1);assertEquals(1,user.getMedia().size());assertTrue(user.getText().contains("午饭"));assertFalse(user.getText().contains("profile"));
 }
 @Test void maliciousNutrientAndCatalogIdOutputCannotBecomeFacts(){
  ChatModel model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"items\":[{\"name\":\"米饭\",\"energyKcal\":100,\"foodId\":\"forged\"}]}")))));
  var gateway=new SpringAiGateway(model,new ObjectMapper());assertEquals("INVALID_MODEL_OUTPUT",assertThrows(Problem.class,()->gateway.identifyFoods(input())).code());
 }
 @Test void invalidJsonAndUpstreamSecretsAreNotReturned(){
  ChatModel model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("secret-provider-detail"));
  var p=assertThrows(Problem.class,()->new SpringAiGateway(model,new ObjectMapper()).identifyFoods(input()));assertEquals("MODEL_UNAVAILABLE",p.code());assertFalse(p.getMessage().contains("secret"));
 }
 private Contracts.ValidCase input(){return IdentificationRules.validate(new Contracts.IdentifyRequest("c1",1,"午饭",List.of(),"solo"));}
}

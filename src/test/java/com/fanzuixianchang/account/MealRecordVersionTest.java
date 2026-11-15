package com.fanzuixianchang.account;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanzuixianchang.core.Problem;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MealRecordVersionTest {
 private final ObjectMapper mapper=new ObjectMapper();
 @Test void protectsEstimatePayloadFromOlderClient()throws Exception{var old=mapper.readTree("{\"recordSchemaVersion\":2}");assertThrows(Problem.class,()->MealRecordVersion.validate(mapper.readTree("{}"),old));}
 @Test void acceptsOldRecordAndExplicitUpgrade()throws Exception{MealRecordVersion.validate(mapper.readTree("{}"),null);MealRecordVersion.validate(mapper.readTree("{\"recordSchemaVersion\":2}"),mapper.readTree("{}"));}
 @Test void rejectsFutureOrMalformedSchema()throws Exception{for(String value:new String[]{"3","0","1.5","\"2\""})assertThrows(Problem.class,()->MealRecordVersion.validate(mapper.readTree("{\"recordSchemaVersion\":"+value+"}"),null));}
 @Test void estimatesCannotMasqueradeAsOldPayload()throws Exception{assertThrows(Problem.class,()->MealRecordVersion.validate(mapper.readTree("{\"nutritionItems\":[{\"type\":\"estimate\"}]}"),null));}
}

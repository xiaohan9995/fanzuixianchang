package com.fanzuixianchang.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fanzuixianchang.core.Problem;

/** Reject silent schema downgrade, including old clients that have read a newer cloud revision. */
final class MealRecordVersion {
 private MealRecordVersion(){}
 static void validate(JsonNode incoming,JsonNode stored){
  int next=version(incoming);
  if(next>2)throw incompatible();
  if(stored!=null&&version(stored)>next)throw incompatible();
  JsonNode items=incoming.path("nutritionItems");
  if(next<2&&items.isArray())for(JsonNode item:items)if("estimate".equals(item.path("type").asText()))throw incompatible();
 }
 private static int version(JsonNode record){
  JsonNode value=record.get("recordSchemaVersion");
  if(value==null)return 1;
  if(!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<1)throw incompatible();
  return value.intValue();
 }
 private static Problem incompatible(){return new Problem(409,"RECORD_SCHEMA_UNSUPPORTED","这份饭案需要更新小程序后再同步，云端记录已保留。");}
}

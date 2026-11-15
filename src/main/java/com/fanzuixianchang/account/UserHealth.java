package com.fanzuixianchang.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fanzuixianchang.core.Problem;

/**
 * Confirmed health profile plus the append-only goal history.
 * Another device replays these straight into its local health document, so every field the
 * client validates is re-checked here instead of trusting whatever this account uploaded.
 */
final class UserHealth {
    private UserHealth(){}
    static final int MAX_GOALS=100;
    static final int MAX_PROFILE_CHARS=4000;
    static final int MAX_GOALS_CHARS=48000;

    /** Null stays null: an account may hold goals without a saved profile. */
    static JsonNode profile(ObjectMapper mapper,JsonNode value){
        if(value==null||value.isNull())return null;
        if(!value.isObject())throw Problem.input("健康档案格式无效。");
        JsonNode screening=value.get("screening");
        if(screening==null||!screening.isObject())throw Problem.input("健康档案缺少适用性确认。");
        ObjectNode result=mapper.createObjectNode();
        result.put("age",integer(value.get("age"),1,120,"年龄"));
        String sex=text(value.get("sex"),8,"性别");
        if(!"male".equals(sex)&&!"female".equals(sex))throw Problem.input("健康档案性别参数无效。");
        result.put("sex",sex);
        result.put("heightCm",decimal(value.get("heightCm"),80,250,"身高"));
        result.put("weightKg",decimal(value.get("weightKg"),20,350,"体重"));
        result.put("activityFactor",decimal(value.get("activityFactor"),1.4,2.5,"活动系数"));
        ObjectNode flags=mapper.createObjectNode();
        for(String key:new String[]{"pregnantOrBreastfeeding","medicalNutrition","eatingDisorder"}){
            JsonNode flag=screening.get(key);
            if(flag==null||!flag.isBoolean())throw Problem.input("健康档案适用性确认不完整。");
            flags.put(key,flag.booleanValue());
        }
        result.set("screening",flags);
        return result;
    }

    /** Rebuilt goal by goal so unknown keys cannot ride along into another device's storage. */
    static ArrayNode goals(ObjectMapper mapper,JsonNode value){
        ArrayNode result=mapper.createArrayNode();
        if(value==null||value.isNull())return result;
        if(!value.isArray()||value.size()>MAX_GOALS)throw Problem.input("饮食目标数量超出范围。");
        for(JsonNode row:value){
            if(!row.isObject())throw Problem.input("饮食目标格式无效。");
            String id=text(row.get("id"),120,"目标编号");
            if(!id.matches("[A-Za-z0-9_-]{1,120}"))throw Problem.input("饮食目标编号无效。");
            String kind=text(row.get("kind"),16,"目标类型");
            if(!"maintenance".equals(kind)&&!"professional".equals(kind))throw Problem.input("饮食目标类型无效。");
            JsonNode note=row.get("note");
            if(note!=null&&!note.isNull()&&(!note.isTextual()||note.asText().length()>500))throw Problem.input("饮食目标说明过长。");
            ObjectNode goal=mapper.createObjectNode();
            goal.put("id",id);
            goal.put("order",integer(row.get("order"),0,1000000,"目标次序"));
            goal.put("kind",kind);
            goal.put("kcal",decimal(row.get("kcal"),1000,10000,"每日目标"));
            String effectiveDate=text(row.get("effectiveDate"),10,"生效日期");
            if(!effectiveDate.matches("\\d{4}-\\d{2}-\\d{2}"))throw Problem.input("饮食目标生效日期无效。");
            goal.put("effectiveDate",effectiveDate);
            goal.put("note",note==null||note.isNull()?"":note.asText());
            JsonNode estimate=row.get("estimate");
            if(estimate!=null&&!estimate.isNull()&&!estimate.isObject())throw Problem.input("目标估算依据格式无效。");
            goal.set("estimate",estimate==null||estimate.isNull()?mapper.nullNode():estimate);
            result.add(goal);
        }
        return result;
    }

    static void checkSize(String profileJson,String goalsJson){
        if(profileJson!=null&&profileJson.length()>MAX_PROFILE_CHARS)throw Problem.input("健康档案过大，请减少内容后重试。");
        if(goalsJson.length()>MAX_GOALS_CHARS)throw Problem.input("饮食目标记录过多，请精简后重试。");
    }

    private static long integer(JsonNode value,int min,int max,String label){
        if(value==null||!value.isIntegralNumber()||!value.canConvertToLong())throw Problem.input("请核对"+label+"。");
        long number=value.longValue();
        if(number<min||number>max)throw Problem.input("请核对"+label+"。");
        return number;
    }

    private static double decimal(JsonNode value,double min,double max,String label){
        if(value==null||!value.isNumber()||!Double.isFinite(value.doubleValue()))throw Problem.input("请核对"+label+"。");
        double number=value.doubleValue();
        if(number<min||number>max)throw Problem.input("请核对"+label+"。");
        return number;
    }

    private static String text(JsonNode value,int max,String label){
        if(value==null||!value.isTextual()||value.asText().isBlank()||value.asText().length()>max)throw Problem.input("请核对"+label+"。");
        return value.asText();
    }
}

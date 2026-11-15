package com.fanzuixianchang.core;

import java.util.*;

/** Regression checks for the MET-based workout estimate. */
public final class ActivityChecks {
    private static int checks;
    public static void main(String[] args){
        var walk=apply(Map.of("items",List.of(Map.of("name","快走","minutes",30,"intensity","中","metMin",3.5,"metMax",4.5))),request("快走 30 分钟",30,62.0));
        check(num(walk,"totalKcal")==130,"representative total from the MET midpoint");
        check(num(walk,"totalMin")==114&&num(walk,"totalMax")==146,"range comes from the MET range, not a fixed percentage");
        check("user".equals(walk.get("weightSource")),"stated weight is credited to the user");
        check(((List<?>)walk.get("items")).size()==1&&num(asMap(((List<?>)walk.get("items")).get(0)),"minutes")==30,"item keeps the stated duration");

        var modelOnly=apply(Map.of("items",List.of(Map.of("name","慢跑","minutes",20,"metMin",7,"metMax",8))),request("慢跑了一会儿",null,null));
        check("default".equals(modelOnly.get("weightSource")),"missing weight falls back to the disclosed default");
        check(num(modelOnly,"weightKg")==ActivityRules.DEFAULT_WEIGHT_KG,"disclosed default weight is reported back");
        check(num(modelOnly,"minutes")==20,"model duration is used when the user gave none");

        // 用户选的时长优先：模型只负责把总时长分给各项运动。
        var stated=apply(Map.of("items",List.of(Map.of("name","快走","minutes",20,"metMin",3.5,"metMax",4.5),Map.of("name","拉伸","minutes",10,"metMin",2.3,"metMax",2.8))),request("出去走了一圈再拉伸",60,62.0));
        var rows=(List<?>)stated.get("items");
        check(rows.size()==2,"both activities are kept");
        check(num(asMap(rows.get(0)),"minutes")==40&&num(asMap(rows.get(1)),"minutes")==20,"stated total is split across items");
        check(num(stated,"totalKcal")==num(asMap(rows.get(0)),"kcal")+num(asMap(rows.get(1)),"kcal"),"total is the sum of the rows");

        // 模型自报的千卡与体重不参与计算。
        var inflated=apply(Map.of("items",List.of(Map.of("name","快走","minutes",30,"metMin",3.5,"metMax",4.5)),"totalKcal",9999,"weightKg",40),request("快走 30 分钟",30,62.0));
        check(num(inflated,"totalKcal")==130,"model-declared total is discarded");
        check(num(inflated,"weightKg")==62,"model-declared weight is discarded");

        var empty=apply(Map.of("items",List.of(),"note","说一下做了什么运动、大概多久。"),request("今天有点累",null,null));
        check(empty.get("totalKcal")==null&&!String.valueOf(empty.get("hint")).isBlank(),"unrecognised description asks for a clearer one instead of guessing");
        var emptyWithoutNote=apply(Map.of("items",List.of()),request("今天有点累",null,null));
        check(!String.valueOf(emptyWithoutNote.get("hint")).isBlank(),"missing model note still yields a usable prompt");

        rejects(()->apply(Map.of("items",List.of(Map.of("name","快走","minutes",30,"metMin",3.5,"metMax",25))),request("快走",null,null)),"impossible MET ceiling rejected");
        rejects(()->apply(Map.of("items",List.of(Map.of("name","快走","minutes",30,"metMin",6,"metMax",4))),request("快走",null,null)),"inverted MET range rejected");
        rejects(()->apply(Map.of("items",List.of(Map.of("name","快走","minutes",0,"metMin",3.5,"metMax",4.5))),request("快走",null,null)),"zero minutes rejected");
        rejects(()->apply(Map.of("items",Collections.nCopies(7,Map.of("name","快走","minutes",10,"metMin",3.5,"metMax",4.5))),request("走了很久",null,null)),"more than six items rejected");
        rejects(()->apply(Map.of("items",List.of(Map.of("name"," ","minutes",30,"metMin",3.5,"metMax",4.5))),request("快走",null,null)),"blank activity name rejected");

        rejects(()->ActivityRules.validate(new Contracts.ActivityRequest(" ",null,null)),"empty description rejected");
        rejects(()->ActivityRules.validate(new Contracts.ActivityRequest("快走",0,null)),"zero duration rejected");
        rejects(()->ActivityRules.validate(new Contracts.ActivityRequest("快走",721,null)),"duration above twelve hours rejected");
        rejects(()->ActivityRules.validate(new Contracts.ActivityRequest("快走",null,10.0)),"implausible weight rejected");
        check(ActivityRules.validate(new Contracts.ActivityRequest("快走 30 分钟",null,null)).text().equals("快走 30 分钟"),"description is kept for the model");
        System.out.println("PASS "+checks+" activity estimate checks");
    }
    static Contracts.ActivityRequest request(String text,Integer minutes,Double weight){return new Contracts.ActivityRequest(text,minutes,weight);}
    static Map<String,Object> apply(Map<String,Object> raw,Contracts.ActivityRequest request){return ActivityRules.apply(raw,ActivityRules.validate(request));}
    static double num(Map<String,Object> map,String key){Object value=map.get(key);if(!(value instanceof Number n))throw new AssertionError("missing number "+key);return n.doubleValue();}
    @SuppressWarnings("unchecked") static Map<String,Object> asMap(Object value){return (Map<String,Object>)value;}
    static void check(boolean valid,String label){if(!valid)throw new AssertionError(label);checks++;}
    static void rejects(Runnable action,String label){try{action.run();throw new AssertionError("accepted: "+label);}catch(Problem p){if(!p.code().equals("INVALID_MODEL_OUTPUT")&&!p.code().equals("INVALID_INPUT"))throw p;checks++;}}
}

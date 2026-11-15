package com.fanzuixianchang.core;

import java.util.*;

/**
 * Energy cost of a described workout. The model only names the activity and an intensity
 * range; the MET arithmetic, the per-item split and every total are computed here so a
 * model cannot hand back an inflated or self-rounded number.
 */
public final class ActivityRules {
    public static final String VERSION="activity-met-v1";
    public static final double DEFAULT_WEIGHT_KG=65;
    private static final int MAX_ITEMS=6;
    private static final Set<String> INTENSITY=Set.of("低","中","高");
    private static final Map<String,Object> SOURCE=Map.of(
        "title","Compendium of Physical Activities（MET 参考表）",
        "url","https://pubmed.ncbi.nlm.nih.gov/21681120/");
    private ActivityRules() {}
    public record Valid(String text,Integer minutes,Double weightKg) {}
    public static Valid validate(Contracts.ActivityRequest request){
        if(request==null)throw Problem.input("请描述今天做了什么运动。");
        String text=RequestRules.bounded(request.text(),400,"运动描述").trim();
        if(text.length()<2)throw Problem.input("请用一句话写下运动，例如「快走 30 分钟」。");
        Integer minutes=request.minutes();
        if(minutes!=null&&(minutes<1||minutes>720))throw Problem.input("运动时长需在 1 至 720 分钟之间。");
        Double weight=request.weightKg();
        if(weight!=null&&(!Double.isFinite(weight)||weight<20||weight>350))throw Problem.input("体重需在 20 至 350 公斤之间。");
        return new Valid(text,minutes,weight);
    }
    public static Map<String,Object> apply(Map<String,Object> raw,Valid input){
        if(raw==null||(raw.get("items")!=null&&!(raw.get("items") instanceof List<?>)))throw invalidOutput();
        double weight=input.weightKg()==null?DEFAULT_WEIGHT_KG:input.weightKg();
        var rows=new ArrayList<Map<String,Object>>();
        var parsed=new ArrayList<Parsed>();
        double modelMinutes=0;
        for(Object value:raw.get("items") instanceof List<?> list?list:List.of()){
            if(!(value instanceof Map<?,?> row))throw invalidOutput();
            String name=text(row.get("name"),40);
            double minutes=number(row.get("minutes"),1,720);
            double metLow=number(row.get("metMin"),1,20);
            double metHigh=number(row.get("metMax"),1,20);
            if(metLow>metHigh)throw invalidOutput();
            String intensity=row.get("intensity") instanceof String level&&INTENSITY.contains(level)?level:"中";
            parsed.add(new Parsed(name,intensity,minutes,metLow,metHigh));
            modelMinutes+=minutes;
        }
        if(parsed.size()>MAX_ITEMS)throw invalidOutput();
        var result=new LinkedHashMap<String,Object>();
        result.put("version",VERSION);
        result.put("source",SOURCE);
        result.put("weightKg",weight);
        result.put("weightSource",input.weightKg()==null?"default":"user");
        var assumptions=new ArrayList<String>();
        assumptions.add(input.weightKg()==null
            ?"按 "+decimal(weight)+" 公斤估算；在健康档案里补上体重会更贴近你的实际消耗。"
            :"按你健康档案里的 "+decimal(weight)+" 公斤体重估算。");
        if(parsed.isEmpty()){
            result.put("items",List.of());
            result.put("totalKcal",null);result.put("totalMin",null);result.put("totalMax",null);
            result.put("assumptions",List.copyOf(assumptions));
            result.put("hint",note(raw.get("note"),"没看出具体的运动，补充一下做了什么、大概多久？"));
            return Collections.unmodifiableMap(result);
        }
        // 用户填的时长优先：模型只负责把总时长分给各项运动。
        int totalMinutes=input.minutes()==null?(int)Math.round(modelMinutes):input.minutes();
        if(totalMinutes<parsed.size())throw invalidOutput();
        double perMetMinute=3.5*weight/200.0;
        int assigned=0;double kcalSum=0,kcalLowSum=0,kcalHighSum=0;
        for(int i=0;i<parsed.size();i++){
            Parsed item=parsed.get(i);
            int share=i==parsed.size()-1?totalMinutes-assigned:Math.max(1,(int)Math.round(item.minutes()/modelMinutes*totalMinutes));
            assigned+=share;
            double low=item.metLow()*perMetMinute*share,high=item.metHigh()*perMetMinute*share;
            long kcal=Math.round((low+high)/2),kcalLow=Math.round(low),kcalHigh=Math.round(high);
            kcalSum+=kcal;kcalLowSum+=kcalLow;kcalHighSum+=kcalHigh;
            var row=new LinkedHashMap<String,Object>();
            row.put("name",item.name());row.put("minutes",share);row.put("intensity",item.intensity());
            row.put("metMin",round1(item.metLow()));row.put("metMax",round1(item.metHigh()));
            row.put("kcal",kcal);row.put("kcalMin",kcalLow);row.put("kcalMax",kcalHigh);
            rows.add(row);
        }
        if(assigned!=totalMinutes)throw invalidOutput();
        assumptions.add(input.minutes()==null?"时长取自你描述里的时长，未精确计时。":"按你选择的 "+totalMinutes+" 分钟分配各项运动。");
        assumptions.add("只算运动本身，不含日常活动、通勤与运动后额外消耗；强度未实测。");
        result.put("items",List.copyOf(rows));
        result.put("minutes",totalMinutes);
        result.put("totalKcal",Math.round(kcalSum));
        result.put("totalMin",Math.round(kcalLowSum));
        result.put("totalMax",Math.round(kcalHighSum));
        result.put("assumptions",List.copyOf(assumptions));
        result.put("hint",note(raw.get("note"),""));
        return Collections.unmodifiableMap(result);
    }
    private record Parsed(String name,String intensity,double minutes,double metLow,double metHigh) {}
    private static String note(Object value,String fallback){
        if(!(value instanceof String str))return fallback;
        String text=str.replace('\n',' ').trim();
        return text.isEmpty()||text.length()>200?fallback:text;
    }
    private static String text(Object value,int max){
        if(!(value instanceof String str))throw invalidOutput();
        String text=str.replace('\n',' ').trim();
        if(text.isEmpty()||text.length()>max)throw invalidOutput();
        return text;
    }
    private static double number(Object value,double min,double max){
        if(!(value instanceof Number n))throw invalidOutput();
        double parsed=n.doubleValue();
        if(!Double.isFinite(parsed)||parsed<min||parsed>max)throw invalidOutput();
        return parsed;
    }
    private static double round1(double value){return Math.round(value*10)/10.0;}
    private static String decimal(double value){
        return value==Math.floor(value)?String.valueOf((long)value):String.valueOf(round1(value));
    }
    public static Problem invalidOutput(){
        return new Problem(502,"INVALID_MODEL_OUTPUT","这次没算出运动消耗，补充做了什么、多久后再试；已有记录不会被覆盖。");
    }
}

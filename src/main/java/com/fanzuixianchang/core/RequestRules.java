package com.fanzuixianchang.core;

import java.util.*;

public final class RequestRules {
    public static final int MAX_IMAGE_BYTES=2*1024*1024;
    public static final int MAX_BODY_BYTES=9*1024*1024;
    private RequestRules() {}
    public static Contracts.ValidCase validate(Contracts.AnalyzeRequest r) {
        if(r==null)throw Problem.input("请提供本次饭案。");
        String id=bounded(r.caseId(),100,"饭案编号");
        if(id.isBlank()||!id.matches("[a-zA-Z0-9_-]+"))throw Problem.input("饭案编号格式无效。");
        if(r.revision()==null||r.revision()<0||r.revision()>1_000_000)throw Problem.input("饭案版本无效。");
        if(!Set.of("before","after","label").contains(safe(r.mode())))throw Problem.input("请选择吃前、吃后或标签调查。");
        if(!Set.of("solo","group","night").contains(safe(r.scene())))throw Problem.input("就餐场景无效。");
        String text=bounded(r.text(),4000,"餐食描述");
        String question=bounded(r.question(),1000,"追问");
        var conditions=conditions(r.conditions(),r.mode(),r.scene());
        var context=ContextRules.sanitize(r.context());
        var dailyContext=DailyContextRules.sanitize(r.dailyContext());
        var images=new ArrayList<Contracts.ValidImage>();
        var raw=r.images()==null?List.<Contracts.ImageInput>of():r.images();
        if(raw.size()>3)throw Problem.input("每次最多提供3张图片。");
        int total=0;
        for(var image:raw) {
            if(image==null||image.base64()==null||image.base64().length()>MAX_IMAGE_BYTES*4L/3+8)throw tooLarge();
            if(!Set.of("image/jpeg","image/png","image/webp").contains(safe(image.mimeType())))throw Problem.input("请使用 JPEG、PNG 或 WebP 图片。");
            byte[] bytes;
            try{bytes=Base64.getDecoder().decode(image.base64());}catch(IllegalArgumentException e){throw Problem.input("图片编码无效，请重新选择。");}
            total+=bytes.length;
            if(bytes.length>MAX_IMAGE_BYTES||total>3*MAX_IMAGE_BYTES)throw tooLarge();
            if(!matches(bytes,image.mimeType()))throw Problem.input("图片内容与格式不一致，请重新选择。");
            images.add(new Contracts.ValidImage(image.mimeType(),bytes));
        }
        if(text.isBlank()&&images.isEmpty()&&!r.mode().equals("label"))throw Problem.input("请先添加文字或餐食图片。");
        return new Contracts.ValidCase(id,r.revision(),r.mode(),r.scene(),text,Collections.unmodifiableMap(conditions),List.copyOf(images),preferences(r.preferences()),question,question.isBlank()?Map.of():context,r.mode().equals("before")?dailyContext:Map.of());
    }
    private static LinkedHashMap<String,Object> conditions(Map<String,Object> raw,String mode,String scene) {
        var input=raw==null?Map.<String,Object>of():raw;var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("priority","amount","unit"))if(input.get(key)!=null) result.put(key,bounded(String.valueOf(input.get(key)),200,key));
        if("预算合适".equals(result.get("priority")))result.remove("priority");
        if(input.get("dislikes")!=null)result.put("dislikes",strings(input.get("dislikes"),"忌口"));
        if(present(input.get("people"))){double people=number(input.get("people"),1,30,"人数");if(people!=Math.floor(people))throw Problem.input("人数需为整数。");result.put("people",(int)people);}
        if(mode.equals("label")){
            if(!present(input.get("per100"))||!present(input.get("amount"))||!present(input.get("unit")))throw Problem.input("请核对每100克/毫升的数值、实际食用量与单位。");
            double per100=number(input.get("per100"),0,100_000,"标签数值");double amount=number(input.get("amount"),0.000001,100_000,"食用量");
            String unit=String.valueOf(input.get("unit"));LabelCalculator.calculate(per100,amount,unit);
            result.put("per100",per100);result.put("amount",amount);result.put("unit",unit);
        }
        return result;
    }
    private static Map<String,Object> preferences(Map<String,Object> raw) {
        if(raw==null||!Boolean.TRUE.equals(raw.get("memoryEnabled")))return Map.of();
        var clean=new LinkedHashMap<String,Object>();clean.put("memoryEnabled",true);
        if(raw.get("dislikes")!=null)clean.put("dislikes",strings(raw.get("dislikes"),"口味偏好"));
        if(raw.get("priority")!=null)clean.put("priority",bounded(String.valueOf(raw.get("priority")),100,"偏好"));
        if("预算合适".equals(clean.get("priority")))clean.remove("priority");
        return Collections.unmodifiableMap(clean);
    }
    private static List<String> strings(Object value,String field){
        if(value instanceof String str){ if(str.isBlank())return List.of();return Arrays.stream(str.split("[,，、\\n]")).map(String::trim).filter(s->!s.isBlank()).map(s->bounded(s,40,field)).limit(21).collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(),list->{if(list.size()>20)throw Problem.input(field+"最多20项。");return List.copyOf(list);}));}
        if(!(value instanceof List<?> list)||list.size()>20)throw Problem.input(field+"格式无效。");
        return list.stream().map(v->{if(!(v instanceof String))throw Problem.input(field+"格式无效。");return bounded((String)v,40,field);}).filter(s->!s.isBlank()).toList();
    }
    public static double number(Object raw,double min,double max,String field){
        double value;try{value=Double.parseDouble(String.valueOf(raw));}catch(RuntimeException e){throw Problem.input(field+"需为有效数字。");}
        if(!Double.isFinite(value)||value<min||value>max)throw Problem.input(field+"超出可用范围。");return value;
    }
    public static String bounded(String value,int max,String field){String text=safe(value).trim();if(text.length()>max)throw Problem.input(field+"过长。");return text;}
    public static String safe(String value){return value==null?"":value;}
    private static boolean present(Object value){return value!=null&&!String.valueOf(value).isBlank();}
    private static Problem tooLarge(){return new Problem(413,"REQUEST_TOO_LARGE","图片过大，请压缩后重试（每张不超过2MB，最多3张）。");}
    public static boolean matches(byte[] b,String mime) {
        if(b.length<12)return false;
        if(mime.equals("image/jpeg"))return (b[0]&255)==255&&(b[1]&255)==216&&(b[2]&255)==255;
        if(mime.equals("image/png"))return Arrays.equals(Arrays.copyOf(b,8),new byte[]{(byte)137,80,78,71,13,10,26,10});
        return b[0]=='R'&&b[1]=='I'&&b[2]=='F'&&b[3]=='F'&&b[8]=='W'&&b[9]=='E'&&b[10]=='B'&&b[11]=='P';
    }
}

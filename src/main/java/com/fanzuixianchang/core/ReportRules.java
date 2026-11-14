package com.fanzuixianchang.core;

import java.util.*;
import java.util.regex.Pattern;
import java.text.Normalizer;

/** Restricts structured model output; factual correctness still requires product review. */
public final class ReportRules {
    private static final Pattern NUMERIC_NUTRITION=Pattern.compile("(?i)(?:[0-9]+(?:[.,][0-9]+)?|[零〇一二两三四五六七八九十百千万点]+)\\s*(?:千卡|大卡|千焦|kcal|kJ|mg|毫克|微克|μg|克|g|公斤|kg|斤|%)(?![A-Za-z])");
    private static final Pattern UNSAFE=Pattern.compile("(?:建议|应该|必须|需要|可以)(?:你|您)?(?:自行)?(?:停药|加药|催吐|泻药|绝食)|(?:保证|承诺|必定)(?:能|会)?(?:瘦|减重|治愈)|(?:每日|每天)(?:仅|只)(?:摄入|吃)");
    private ReportRules() {}
    public static Map<String,Object> apply(Map<String,Object> raw,Contracts.ValidCase input) {
        if(raw==null)throw Problem.output();
        var result=new LinkedHashMap<String,Object>();
        result.put("title",text(raw.get("title"),120,true,input));
        result.put("summary",text(raw.get("summary"),2000,true,input));
        result.put("quip",text(raw.get("quip"),200,false,input));
        result.put("suggestion",text(raw.get("suggestion"),1200,true,input));
        result.put("unknowns",stringList(raw.get("unknowns"),10,400,input));
        result.put("questions",stringList(raw.get("questions"),3,200,input));
        var choices=objectList(raw.get("choices"),5,List.of("id","name","reason","tradeoff"),input);
        var choiceIds=new HashSet<String>();
        for(var choice:choices){String id=(String)choice.get("id");if(!id.matches("[a-zA-Z0-9_-]{1,80}")||!choiceIds.add(id))throw Problem.output();}
        result.put("choices",choices);
        var foods=new ArrayList<Map<String,Object>>();
        for(var row:maps(raw.get("foods"),20)){
            String name=text(row.get("name"),600,true,input);
            if(name.matches("蛋白质|脂肪|碳水化合物|膳食纤维|能量|热量|糖|钠"))throw Problem.output();
            foods.add(Map.of("name",name,"amount",portion(row.get("amount"),input),"source",text(row.get("source"),600,false,input)));
        }
        result.put("foods",List.copyOf(foods));
        var used=new LinkedHashSet<String>();
        Object rawSources=raw.get("sources");
        for(var row:maps(rawSources,5)) {
            Object id=row.get("id");if(!(id instanceof String str)||!Knowledge.SOURCES.containsKey(str))throw Problem.output();used.add(str);
        }
        var evidence=new ArrayList<Map<String,Object>>();
        for(var row:maps(raw.get("evidence"),20)) {
            String kind=String.valueOf(row.get("kind"));
            if(!Set.of("user","image","guideline","calculation").contains(kind))throw Problem.output();
            // Model-produced arithmetic is never retained. Server appends its own result below.
            if(kind.equals("calculation"))continue;
            var item=new LinkedHashMap<String,Object>();item.put("kind",kind);
            String evidenceText;
            if(kind.equals("user") && row.get("text") instanceof String candidate && NUMERIC_NUTRITION.matcher(Normalizer.normalize(candidate,Normalizer.Form.NFKC)).find()){
                String quoted=candidate.replaceFirst("^用户(?:确认|提供|补充|表示|输入)?[：:]?\\s*","").trim();
                if(quoted.length()>1000||quoted.isEmpty()||!(input.text().contains(quoted)||String.valueOf(input.conditions().getOrDefault("amount","")).contains(quoted)))throw Problem.output();
                evidenceText="用户提供："+quoted;
            }else evidenceText=text(row.get("text"),1000,true,input);
            item.put("text",evidenceText);
            if(kind.equals("guideline")){
                if(!(row.get("sourceId") instanceof String id)||!Knowledge.SOURCES.containsKey(id))throw Problem.output();
                item.put("sourceId",id);used.add(id);
            }
            evidence.add(item);
        }
        result.put("scope",input.scene().equals("group")?"table":"personal");
        result.put("calculation",null);
        if(input.mode().equals("label")){
            var c=input.conditions();
            var calc=LabelCalculator.calculate(((Number)c.get("per100")).doubleValue(),((Number)c.get("amount")).doubleValue(),(String)c.get("unit"));
            result.put("calculation",calc.asMap());
            evidence.add(Map.of("kind","calculation","text",calc.formula()));
            result.put("summary","按你确认的标签数值与实际用量，已完成单位换算。具体数值见下方计算过程。");
        }
        ChoiceRules.normalize(result,input);
        result.put("evidence",List.copyOf(evidence));
        result.put("sources",used.stream().map(Knowledge.SOURCES::get).toList());
        return Collections.unmodifiableMap(result);
    }
    public static String safeAnswer(Object value,Contracts.ValidCase input){return text(value,3000,false,input);}
    public static String answerFor(Object value,Contracts.ValidCase input,Map<String,Object> report){
        String answer=safeAnswer(value,input);
        if(input.mode().equals("before")&&(ChoiceRules.hasIngredientAvoidance(input)||(ChoiceRules.hasHardConstraints(input)&&Pattern.compile("预算|忌口|价格|不辣|不含|能吃|放心|都可以|推荐|建议选|便宜|省钱|划算").matcher(answer).find()))){
            var lines=new ArrayList<String>();lines.add((String)report.get("summary"));
            @SuppressWarnings("unchecked") var choices=(List<Map<String,Object>>)report.get("choices");
            for(var choice:choices)lines.add(choice.get("name")+"："+choice.get("reason"));
            lines.add((String)report.get("suggestion"));String checked=String.join("\n",lines);
            return checked.length()>2800?checked.substring(0,2800)+"（更多线索请查看报告）":checked;
        }
        return answer;
    }
    private static String text(Object value,int max,boolean required,Contracts.ValidCase input) {
        if(value==null){if(required)throw Problem.output();return "";}
        if(!(value instanceof String str)||str.length()>max)throw Problem.output();
        str=str.trim();if(required&&str.isEmpty())throw Problem.output();
        if(UNSAFE.matcher(str).find())throw Problem.output();
        if(NUMERIC_NUTRITION.matcher(Normalizer.normalize(str,Normalizer.Form.NFKC)).find()) {
            if(input.mode().equals("label"))str=NUMERIC_NUTRITION.matcher(Normalizer.normalize(str,Normalizer.Form.NFKC)).replaceAll("（数值请见已核对计算）");
            else throw Problem.output();
        }
        return str;
    }
    private static String portion(Object value,Contracts.ValidCase input){
        if(!(value instanceof String str)||str.isBlank()||str.length()>600)throw Problem.output();
        if(UNSAFE.matcher(str).find())throw Problem.output();
        if(NUMERIC_NUTRITION.matcher(Normalizer.normalize(str,Normalizer.Form.NFKC)).find()){
            if(Pattern.compile("蛋白质|脂肪|碳水|纤维|能量|热量|千卡|大卡|千焦|(?i:kcal|kJ)").matcher(str).find())throw Problem.output();
            String amount=String.valueOf(input.conditions().getOrDefault("amount",""));
            if(!input.text().contains(str)&&!amount.contains(str))throw Problem.output();
        }
        return str.trim();
    }
    private static List<String> stringList(Object value,int max,int maxLength,Contracts.ValidCase input){
        if(value==null)return List.of();if(!(value instanceof List<?> list)||list.size()>max)throw Problem.output();
        return list.stream().map(v->text(v,maxLength,true,input)).toList();
    }
    private static List<Map<String,Object>> objectList(Object value,int max,List<String> fields,Contracts.ValidCase input){
        var list=new ArrayList<Map<String,Object>>();
        for(var row:maps(value,max)){
            var item=new LinkedHashMap<String,Object>();
            for(String field:fields)item.put(field,text(row.get(field),600,!field.equals("tradeoff")&&!field.equals("source"),input));
            list.add(item);
        }
        return List.copyOf(list);
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> maps(Object value,int max) {
        if(value==null)return List.of();if(!(value instanceof List<?> list)||list.size()>max)throw Problem.output();
        var out=new ArrayList<Map<String,Object>>();
        for(Object row:list){if(!(row instanceof Map<?,?> map)||map.keySet().stream().anyMatch(k->!(k instanceof String)))throw Problem.output();out.add((Map<String,Object>)map);}
        return out;
    }
}

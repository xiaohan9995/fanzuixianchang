package com.fanzuixianchang.core;

import java.util.*;

/** Bounded provisional dialogue, explicitly separate from current evidence. */
final class ContextRules {
    private ContextRules(){}
    static Map<String,Object> sanitize(Map<String,Object> raw){
        if(raw==null||raw.isEmpty())return Map.of();
        if(jsonLength(raw,0)>12000)throw Problem.input("本案对话上下文过长，请从当前线索继续调查。");
        var out=new LinkedHashMap<String,Object>();var ids=new HashSet<String>();
        if(raw.get("report")!=null){
            var report=map(raw.get("report"));var clean=new LinkedHashMap<String,Object>();
            copyText(report,clean,"title",120);copyText(report,clean,"summary",2000);copyText(report,clean,"suggestion",1200);
            var choices=rows(report.get("choices"),5);var validChoices=new ArrayList<Map<String,Object>>();
            for(var choice:choices){
                var item=new LinkedHashMap<String,Object>();copyText(choice,item,"id",80);copyText(choice,item,"name",120);copyText(choice,item,"reason",600);copyText(choice,item,"tradeoff",600);
                if(item.get("id") instanceof String id){if(!ids.add(id))throw Problem.input("暂定报告候选编号重复。");}
                if(choice.get("price")!=null)item.put("price",RequestRules.number(choice.get("price"),0,100000,"暂定价格"));
                if(choice.get("eligible") instanceof Boolean eligible)item.put("eligible",eligible);
                if(choice.get("conflicts")!=null)item.put("conflicts",texts(choice.get("conflicts"),4,100));
                validChoices.add(Collections.unmodifiableMap(item));
            }
            clean.put("choices",List.copyOf(validChoices));var foods=new ArrayList<Map<String,Object>>();
            for(var food:rows(report.get("foods"),20)){var item=new LinkedHashMap<String,Object>();copyText(food,item,"name",120);copyText(food,item,"amount",200);copyText(food,item,"source",120);foods.add(Collections.unmodifiableMap(item));}
            clean.put("foods",List.copyOf(foods));clean.put("unknowns",texts(report.get("unknowns"),10,400));out.put("report",Collections.unmodifiableMap(clean));
        }
        if(raw.get("selectedChoiceId")!=null){String id=string(raw.get("selectedChoiceId"),80);if(!ids.contains(id))throw Problem.input("已选候选不在当前暂定报告中。");out.put("selectedChoiceId",id);}
        var turns=new ArrayList<Map<String,Object>>();
        for(var turn:rows(raw.get("turns"),3)){
            var item=new LinkedHashMap<String,Object>();copyText(turn,item,"question",1000);copyText(turn,item,"answer",3000);turns.add(Collections.unmodifiableMap(item));
        }
        out.put("turns",List.copyOf(turns));return Collections.unmodifiableMap(out);
    }
    private static void copyText(Map<String,Object> from,Map<String,Object> to,String key,int limit){if(from.get(key)!=null)to.put(key,string(from.get(key),limit));}
    private static String string(Object o,int limit){if(!(o instanceof String s)||s.length()>limit)throw Problem.input("本案对话上下文格式或长度无效。");return s;}
    private static List<String> texts(Object o,int max,int length){if(o==null)return List.of();if(!(o instanceof List<?> list)||list.size()>max)throw Problem.input("本案对话上下文条目过多。");return list.stream().map(v->string(v,length)).toList();}
    private static List<Map<String,Object>> rows(Object o,int max){if(o==null)return List.of();if(!(o instanceof List<?> list)||list.size()>max)throw Problem.input("本案对话上下文条目过多。");return list.stream().map(ContextRules::map).toList();}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object o){if(!(o instanceof Map<?,?> m)||m.keySet().stream().anyMatch(k->!(k instanceof String)))throw Problem.input("本案对话上下文格式无效。");return (Map<String,Object>)m;}
    private static int jsonLength(Object o,int depth){
        if(depth>8)throw Problem.input("本案对话上下文嵌套过深。");
        if(o==null)return 4;if(o instanceof Boolean b)return b?4:5;if(o instanceof Number n){if(!Double.isFinite(n.doubleValue()))throw Problem.input("上下文含无效数值。");return o.toString().length();}
        if(o instanceof String s){int count=2;for(int i=0;i<s.length();i++){char c=s.charAt(i);count+=(c=='"'||c=='\\'||c=='\n'||c=='\r'||c=='\t'||c=='\b'||c=='\f')?2:c<32?6:1;if(count>12000)return count;}return count;}
        int sum=2;int count=0;
        if(o instanceof Map<?,?> m){for(var e:m.entrySet()){if(!(e.getKey() instanceof String))throw Problem.input("上下文键无效。");sum+=jsonLength(e.getKey(),depth+1)+jsonLength(e.getValue(),depth+1)+1+(count++>0?1:0);if(sum>12000)return sum;}return sum;}
        if(o instanceof List<?> list){for(Object value:list){sum+=jsonLength(value,depth+1)+(count++>0?1:0);if(sum>12000)return sum;}return sum;}
        throw Problem.input("本案对话上下文格式无效。");
    }
}

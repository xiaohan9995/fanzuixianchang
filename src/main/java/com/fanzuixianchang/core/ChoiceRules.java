package com.fanzuixianchang.core;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Conservative checks over explicit user text, not arbitrary model claims or image guesses. */
final class ChoiceRules {
    private static final Pattern PRICE_IN_NAME=Pattern.compile("[¥￥]?[0-9]+(?:\\.[0-9]+)?\\s*(?:元(?:[／/]人)?|块(?:钱)?)");
    private static final Pattern CERTAINTY=Pattern.compile("预算|忌口|口味|不辣|不含|无香菜|价格|元|块|肯定|一定|便宜|省钱|划算");
    private record Grounded(Double price,boolean perPerson,String clause){}
    private ChoiceRules(){}
    @SuppressWarnings("unchecked")
    static void normalize(Map<String,Object> report,Contracts.ValidCase input){
        var source=(List<Map<String,Object>>)report.get("choices");
        report.put("recommendationId",null);
        if(!input.mode().equals("before"))return;
        Double budget=value(input.conditions().get("budget"));
        if(budget==null)budget=value(input.preferences().get("budget"));
        List<String> avoid=(List<String>)input.conditions().getOrDefault("dislikes",List.of());
        if(avoid.isEmpty())avoid=(List<String>)input.preferences().getOrDefault("dislikes",List.of());
        boolean constrained=budget!=null||!avoid.isEmpty();
        Integer people=input.conditions().get("people") instanceof Number n?n.intValue():null;
        boolean perPersonBudget="perPerson".equals(input.conditions().get("budgetType"));
        boolean group=input.scene().equals("group");
        var names=source.stream().map(c->name((String)c.get("name"))).toList();
        var choices=new ArrayList<Map<String,Object>>();var addedUnknowns=new LinkedHashSet<String>();
        for(int i=0;i<source.size();i++){
            var original=source.get(i);String name=names.get(i);var facts=ground(name,names,input.text());
            var conflicts=new ArrayList<String>();var unknowns=new ArrayList<String>();var factsText=new ArrayList<String>();
            Double comparablePrice=facts.price();Double comparableBudget=budget;
            if(facts.price()!=null)factsText.add("用户提供的候选标价为"+decimal(facts.price())+"元"+(facts.perPerson()?"/人":"")+"。");
            else if(constrained)unknowns.add(name+"的价格尚未得到可定位的文字确认");
            if(group){
                if((perPersonBudget||facts.perPerson())&&people==null){comparablePrice=null;comparableBudget=null;if(budget!=null)unknowns.add("聚餐人数未确认，无法换算总价与人均预算");}
                else if(people!=null){if(perPersonBudget&&budget!=null)comparableBudget=budget*people;if(facts.perPerson()&&comparablePrice!=null)comparablePrice*=people;}
            }
            if(budget!=null){
                if(comparablePrice!=null&&comparableBudget!=null){
                    if(comparablePrice>comparableBudget)conflicts.add("已知标价超过本次预算");
                    else factsText.add("仅按用户提供的标价未超预算；实际结算总价与费用仍需核对。");
                }else if(unknowns.isEmpty())unknowns.add("候选价格或预算口径尚未核对");
            }
            for(String ingredient:avoid){
                String token=ingredient.replaceFirst("^(?:不吃|不要|不放|忌|避免|去掉)","").trim();
                if(token.isEmpty()){unknowns.add("忌口条目含义尚未明确，请补充具体食材或口味");continue;}
                String clause=facts.clause();
                // MVP has no verified structured ingredient list. Free-form exclusion never proves absence.
                // Negated/partial/conditional prose always stays pending rather than growing an NLP exception list.
                boolean provisional=clause.isEmpty()||Pattern.compile("不|无|没|未|去掉|去除|是否|能否|可否|可能|[?？]").matcher(clause).find();
                if(!provisional&&(name.contains(token)||clause.contains(token)))conflicts.add("已知菜名或描述与不吃“"+token+"”的条件冲突");
                else unknowns.add(name+"的完整配料/做法待核对，不能确认是否避开“"+token+"”");
            }
            Boolean eligible=conflicts.isEmpty()?(unknowns.isEmpty()||!constrained?Boolean.TRUE:null):Boolean.FALSE;
            var c=new LinkedHashMap<String,Object>();c.put("id",original.get("id"));c.put("name",name);
            String reason=(String)original.get("reason");String tradeoff=(String)original.get("tradeoff");
            if(constrained){
                var notes=new ArrayList<String>(factsText);notes.addAll(conflicts);notes.addAll(unknowns);
                if(avoid.isEmpty()&&!reason.isBlank()&&!CERTAINTY.matcher(reason).find())notes.add(reason);
                reason=String.join(" ",notes);
                var caveats=new ArrayList<String>(conflicts);caveats.addAll(unknowns);
                if(avoid.isEmpty()&&!tradeoff.isBlank()&&!CERTAINTY.matcher(tradeoff).find())caveats.add(tradeoff);
                tradeoff=String.join("；",caveats);
            }else if(CERTAINTY.matcher(reason).find())reason="请核对实际配料、做法与份量后再决定。";
            c.put("reason",reason);c.put("tradeoff",tradeoff);c.put("price",facts.price());c.put("eligible",eligible);c.put("conflicts",List.copyOf(conflicts));
            choices.add(c);addedUnknowns.addAll(unknowns);
        }
        choices.sort(Comparator.comparing(c->!Boolean.TRUE.equals(c.get("eligible"))));
        Map<String,Object> best=choices.stream().filter(c->Boolean.TRUE.equals(c.get("eligible"))).findFirst().orElse(null);
        report.put("recommendationId",best==null?null:best.get("id"));report.put("choices",List.copyOf(choices));
        if(constrained){
            if(!avoid.isEmpty()){report.put("quip","这条线索，得问你。");addedUnknowns.add("完整配料/做法待核对，文字中的排除描述不能确认食材不存在");}
            if(best==null){report.put("summary","现有候选有条件冲突或关键线索未确认，暂时不能确定满足条件的选择。");report.put("suggestion","先核对候选的实际配料、制作方式与份量；有明确冲突时换一个候选，或由你调整条件。");}
            else{report.put("summary","就已核对的忌口线索，可优先比较“"+best.get("name")+"”；实际配料与做法仍需确认。");report.put("suggestion","先核对实际配料与做法，再由你决定是否选择；未确认的信息仍保留为未知。");}
            var unknowns=new ArrayList<String>((List<String>)report.get("unknowns"));
            for(String item:addedUnknowns)if(!unknowns.contains(item)&&unknowns.size()<20)unknowns.add(item);
            report.put("unknowns",List.copyOf(unknowns));
        }
    }
    static boolean hasIngredientAvoidance(Contracts.ValidCase input){
        return !((List<?>)input.conditions().getOrDefault("dislikes",List.of())).isEmpty()||!((List<?>)input.preferences().getOrDefault("dislikes",List.of())).isEmpty();
    }
    static boolean hasHardConstraints(Contracts.ValidCase input){
        return input.conditions().get("budget")!=null||input.preferences().get("budget")!=null||
            !((List<?>)input.conditions().getOrDefault("dislikes",List.of())).isEmpty()||!((List<?>)input.preferences().getOrDefault("dislikes",List.of())).isEmpty();
    }
    private static String name(String value){String s=PRICE_IN_NAME.matcher(value).replaceAll("").replaceAll("^[\\s:：,，]+|[\\s:：,，]+$","");if(s.isBlank())throw Problem.output();return s;}
    private static Grounded ground(String name,List<String> names,String text){
        int first=text.indexOf(name);if(first<0||name.length()<2)return new Grounded(null,false,"");
        int end=text.length();
        for(String marker:List.of("。","；",";","\n")){int p=text.indexOf(marker,first);if(p>=0)end=Math.min(end,p);}
        for(String other:names)if(!other.equals(name)){int p=text.indexOf(other,first+name.length());if(p>=0)end=Math.min(end,p);}
        end=Math.min(end,first+400);
        String clause=text.substring(first,end);
        var pattern=Pattern.compile(Pattern.quote(name)+"[\\s:：()（）]*(?:(?:标价|价格|售价|套餐价|为|是)[\\s:：]*)?([0-9]+(?:\\.[0-9]{1,2})?)\\s*(?:元|块(?:钱)?)([／/]人|每人|/位|每位)?");
        var found=pattern.matcher(clause);Double price=null;boolean perPerson=false;
        if(found.find()){try{price=Double.valueOf(found.group(1));perPerson=found.group(2)!=null;}catch(NumberFormatException ignored){return new Grounded(null,false,clause);}}
        // Repeated name with a different stated price is ambiguous, not a reliable price fact.
        var all=pattern.matcher(text);while(all.find()){double next=Double.parseDouble(all.group(1));if(price!=null&&Double.compare(price,next)!=0)return new Grounded(null,false,clause);}
        return new Grounded(price,perPerson,clause);
    }
    private static Double value(Object o){return o instanceof Number n?n.doubleValue():null;}
    private static String decimal(double value){return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}
}

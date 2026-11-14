package com.fanzuixianchang.core;

import java.util.*;

/** Regression checks from independent review. */
@SuppressWarnings("unchecked")
public final class PolicyChecks {
    private static int checks;
    public static void main(String[] args){
        var portion=input("after","solo","这顿有100克鸡肉",Map.of());
        var bad=report();bad.put("summary","这一顿含有100克蛋白质。");
        rejects(()->ReportRules.apply(bad,portion),"portion is not protein quantity");
        rejects(()->ReportRules.safeAnswer("你吃了100克蛋白质。",portion),"followup must not reinterpret portion");
        var rawPortion=report();rawPortion.put("foods",List.of(Map.of("name","鸡肉","amount","100克鸡肉","source","用户确认")));
        check(!((List<?>)ReportRules.apply(rawPortion,portion).get("foods")).isEmpty(),"explicit portion may be repeated as portion");
        var money=report();money.put("summary","可以比较2份菜单和25元标价。");ReportRules.apply(money,portion);check(true,"harmless count/currency accepted");
        var menu=input("before","solo","甲套餐20元，乙套餐35元。",Map.of("budget",25));
        var over=report();over.put("choices",List.of(choice("b","乙套餐35元")));over.put("summary","推荐乙套餐，符合预算。");
        var normalized=ReportRules.apply(over,menu);
        check(normalized.get("recommendationId")==null,"overbudget is not recommendation");
        check(!String.valueOf(normalized.get("summary")).contains("符合预算"),"overbudget certainty removed");
        check(!ReportRules.answerFor("35元符合预算，推荐乙。",menu,normalized).contains("35元符合预算"),"followup cannot restore invalid recommendation");
        var alternatives=report();alternatives.put("choices",List.of(choice("b","乙套餐35元"),choice("a","甲套餐20元")));
        var options=ReportRules.apply(alternatives,menu);
        check(options.get("recommendationId").equals("a"),"feasible grounded candidate first");
        check(((List<Map<String,Object>>)options.get("choices")).get(0).get("id").equals("a"),"order agrees with recommendation");
        check(Boolean.FALSE.equals(((List<Map<String,Object>>)options.get("choices")).get(1).get("eligible")),"truthful conflicting alternative remains marked");
        var unknown=ReportRules.apply(alternatives,input("before","solo","预算25元。菜单是图片。",Map.of("budget",25)));
        check(unknown.get("recommendationId")==null,"standalone budget not candidate price");
        check(((List<Map<String,Object>>)unknown.get("choices")).get(0).get("eligible")==null,"unknown-only candidates remain pending, not conflicting");
        check(((List<Map<String,Object>>)unknown.get("choices")).stream().allMatch(c->c.get("price")==null),"model price embedded in name not trusted alone");
        var spicy=report();spicy.put("choices",List.of(choice("a","香辣鸡腿饭")));
        var noChili=ReportRules.apply(spicy,input("before","solo","香辣鸡腿饭20元",Map.of("dislikes",List.of("辣"))));
        check(noChili.get("recommendationId")==null,"known spicy dish not no-chili recommendation");
        var bland=report();bland.put("choices",List.of(choice("a","清蒸鸡腿饭")));
        var ingredientsUnknown=ReportRules.apply(bland,input("before","solo","清蒸鸡腿饭20元",Map.of("dislikes",List.of("香菜"))));
        check(ingredientsUnknown.get("recommendationId")==null,"unknown ingredient isn't verified absence");
        var confirmed=ReportRules.apply(bland,input("before","solo","清蒸鸡腿饭20元，不放香菜",Map.of("budget",25,"dislikes",List.of("香菜"))));
        check(confirmed.get("recommendationId")==null,"free-form exclusion remains pending until structured ingredient verification exists");
        check(((List<Map<String,Object>>)confirmed.get("choices")).get(0).get("eligible")==null,"ingredient exclusion is never certified from prose");
        var mixed=report();mixed.put("choices",List.of(choice("a","甲饭")));
        for(String detail:List.of("不含花生油但有花生碎","不含花生但配花生酱")){
            var mixedResult=ReportRules.apply(mixed,input("before","solo","甲饭22元，"+detail,Map.of("budget",25,"dislikes",List.of("花生"))));
            check(mixedResult.get("recommendationId")==null,"partial exclusion is never a recommendation");
            check(((List<Map<String,Object>>)mixedResult.get("choices")).get(0).get("eligible")==null,"partial exclusion stays pending");
            check(!ReportRules.answerFor("这份不含花生，可以放心吃。",input("before","solo","甲饭22元，"+detail,Map.of("budget",25,"dislikes",List.of("花生"))),mixedResult).contains("可以放心吃"),"followup cannot certify prose ingredient absence");
        }
        var unsupported=report();unsupported.put("choices",List.of(Map.of("id","a","name","甲饭","reason","没有花生，适合你。","tradeoff","不用担心花生。")));
        var avoided=input("before","solo","甲饭22元，不含花生但配花生酱",Map.of("budget",25,"dislikes",List.of("花生")));
        var unsupportedResult=ReportRules.apply(unsupported,avoided);
        check(!String.valueOf(((List<Map<String,Object>>)unsupportedResult.get("choices")).get(0).get("reason")).contains("没有花生"),"pending choice cannot keep raw absence guarantee in reason");
        check(!ReportRules.answerFor("没有花生，适合你。",avoided,unsupportedResult).contains("没有花生"),"all avoid-related followups stay grounded without phrase detector");
        var emptyChoices=report();emptyChoices.put("summary","没有花生，可以吃。");
        check(!String.valueOf(ReportRules.apply(emptyChoices,avoided).get("summary")).contains("没有花生"),"missing choices cannot bypass pending ingredient rule");
        var doubleNegative=report();doubleNegative.put("choices",List.of(choice("a","甲饭")));
        var notFree=ReportRules.apply(doubleNegative,input("before","solo","甲饭22元，不是无香菜款",Map.of("dislikes",List.of("香菜"))));
        check(notFree.get("recommendationId")==null,"double negative must not certify exclusion");
        var notUnspicy=ReportRules.apply(doubleNegative,input("before","solo","甲饭22元，并非无辣",Map.of("dislikes",List.of("辣"))));
        check(notUnspicy.get("recommendationId")==null,"negated no-chili statement remains unverified");
        var invented=report();invented.put("choices",List.of(choice("a","不辣套餐")));
        check(ReportRules.apply(invented,input("before","solo","菜单见图片",Map.of("dislikes",List.of("辣")))).get("recommendationId")==null,"model-only ingredient-free name isn't verified fact");
        check(RequestRules.validate(new Contracts.AnalyzeRequest("zero",1,"before","solo","公司餐食",Map.of("budget",0),List.of(),Map.of("memoryEnabled",true,"budget",0),null)).conditions().get("budget").equals(0.0),"zero budget accepted");
        rejects(()->input("before","solo","菜单",Map.of("budget",-1)),"negative budget rejected");
        var context=Map.<String,Object>of("report",Map.of("title","前次饭案","summary","已给建议","choices",List.of(choice("a","甲套餐")),"foods",List.of(),"unknowns",List.of(),"suggestion","核对价格"),"selectedChoiceId","a","turns",List.of(Map.of("question","第二个呢？","answer","先核对配料。")));
        var withContext=new Contracts.AnalyzeRequest("case-1",1,"before","solo","甲套餐20元",Map.of(),List.of(),Map.of(),"第二个呢？",context);
        check(RequestRules.validate(withContext).context().containsKey("turns"),"bounded previous dialogue retained");
        var withoutQuestion=new Contracts.AnalyzeRequest("case-1",1,"before","solo","甲套餐20元",Map.of(),List.of(),Map.of(),null,context);
        check(RequestRules.validate(withoutQuestion).context().isEmpty(),"no hidden context on ordinary analysis");
        var oversized=new Contracts.AnalyzeRequest("case-1",1,"before","solo","菜单",Map.of(),List.of(),Map.of(),"问一下",Map.of("report",Map.of("summary","字".repeat(12001))));
        rejects(()->RequestRules.validate(oversized),"oversized context rejected");
        var tooManyTurns=new Contracts.AnalyzeRequest("case-1",1,"before","solo","菜单",Map.of(),List.of(),Map.of(),"问一下",Map.of("turns",Collections.nCopies(4,Map.of("question","问题","answer","回答"))));
        rejects(()->RequestRules.validate(tooManyTurns),"more than three turns rejected");
        System.out.println("PASS "+checks+" backend policy regression checks");
    }
    static Contracts.ValidCase input(String mode,String scene,String text,Map<String,Object> c){return RequestRules.validate(new Contracts.AnalyzeRequest("case-1",1,mode,scene,text,c,List.of(),Map.of(),null));}
    static Map<String,Object> report(){var m=new LinkedHashMap<String,Object>();m.put("title","调查");m.put("summary","构成待核对");m.put("suggestion","核对份量");m.put("choices",List.of());m.put("foods",List.of());m.put("evidence",List.of());m.put("sources",List.of());m.put("unknowns",List.of());m.put("questions",List.of());return m;}
    static Map<String,Object> choice(String id,String name){return Map.of("id",id,"name",name,"reason","符合预算和口味，推荐它。","tradeoff","");}
    static void check(boolean valid,String label){if(!valid)throw new AssertionError(label);checks++;}
    static void rejects(Runnable action,String label){try{action.run();throw new AssertionError("accepted: "+label);}catch(Problem p){checks++;}}
}

package com.fanzuixianchang.core;
import java.util.*;
public final class IdentificationChecks {
 private static int count;
 public static void main(String[] args){
  var catalog=FoodCatalog.load();
  var raw=Map.<String,Object>of("items",List.of(Map.of("name","米饭","preparation","熟","searchTerms",List.of("米饭"),"portion",Map.of("min",100,"max",200,"unit","g","basis","用户明确描述吃了100至200克","origin","user"),"unknowns",List.of("酱汁用量未知"))));
  var input=IdentificationRules.validate(new Contracts.IdentifyRequest("c1",1,"吃了100至200克米饭",List.of(),"solo"));
  var result=IdentificationRules.apply(raw,input,catalog);var row=(Map<?,?>)((List<?>)result.get("items")).get(0);
  check(((List<?>)row.get("candidates")).size()<=3&&!((List<?>)row.get("candidates")).isEmpty(),"candidate rows from actual catalog");check(((Map<?,?>)row.get("portion")).get("origin").equals("user"),"user portion reference retained");check(!row.containsKey("energyKcal"),"no model nutrients exposed");
  var photoOnly=Map.<String,Object>of("items",List.of(Map.of("name","米饭","preparation","熟","searchTerms",List.of("米饭"),"portion",Map.of("min",100,"max",200,"unit","g","basis","一碗通常200克","origin","image-estimate"),"unknowns",List.of())));
  row=(Map<?,?>)((List<?>)IdentificationRules.apply(photoOnly,input,catalog).get("items")).get(0);check(row.get("portion")==null,"text without explicit quantity evidence cannot claim image estimate");
  var incomplete=Map.<String,Object>of("items",List.of(Map.of("name","未知菜","preparation","不清楚","searchTerms",List.of("thisfooddoesnotexist"),"unknowns",List.of())));
  row=(Map<?,?>)((List<?>)IdentificationRules.apply(incomplete,input,catalog).get("items")).get(0);check(((List<?>)row.get("candidates")).isEmpty()&&row.get("portion")==null,"unmatched unknown stays unresolved");
  rejects(()->IdentificationRules.validate(new Contracts.IdentifyRequest("c1",1,"整桌",List.of(),"group")),"group attribution rejected");
  rejects(()->IdentificationRules.apply(Map.of("items",List.of(Map.of("name","米饭","energyKcal",200,"searchTerms",List.of("米饭")))),input,catalog),"model nutrients rejected");
  rejects(()->IdentificationRules.apply(Map.of("items",List.of(Map.of("name","米饭","preparation","熟","searchTerms",List.of("米饭"),"portion",Map.of("min",200,"max",100,"unit","g","basis","数量线索","origin","user"),"unknowns",List.of()))),input,catalog),"reversed model range rejected");
  byte[] image={(byte)137,80,78,71,13,10,26,10,0,0,0,0};
  var photoInput=IdentificationRules.validate(new Contracts.IdentifyRequest("c2",1,"餐具尺寸参照",List.of(new Contracts.ImageInput("image/png",Base64.getEncoder().encodeToString(image))),"solo"));
  var photo=Map.<String,Object>of("items",List.of(Map.of("name","米饭","preparation","熟","searchTerms",List.of("米饭"),"portion",Map.of("min",100,"max",250,"unit","g","basis","可见餐具刻度参照，深度遮挡仍待用户确认","origin","image-estimate"),"unknowns",List.of())));
  row=(Map<?,?>)((List<?>)IdentificationRules.apply(photo,photoInput,catalog).get("items")).get(0);check(row.get("portion")!=null,"supported photo estimate retained for explicit client confirmation");
  photo=Map.<String,Object>of("items",List.of(Map.of("name","米饭","preparation","熟","searchTerms",List.of("米饭"),"portion",Map.of("min",100,"max",200,"unit","g","basis","标准碗通常200克","origin","image-estimate"),"unknowns",List.of())));
  row=(Map<?,?>)((List<?>)IdentificationRules.apply(photo,photoInput,catalog).get("items")).get(0);check(row.get("portion")==null,"generic bowl conversions are not calibration");
  check(((List<?>)IdentificationRules.apply(Map.of("items",List.of()),input,catalog).get("items")).isEmpty(),"empty recognition is honest valid output");
  var mixedInput=IdentificationRules.validate(new Contracts.IdentifyRequest("mixed",1,"米饭100克，鸡蛋50克",List.of(),"solo"));
  var wrongFood=Map.<String,Object>of("items",List.of(Map.of("name","鸡蛋","preparation","水煮","searchTerms",List.of("鸡蛋"),"portion",Map.of("min",100,"max",100,"unit","g","basis","用户给出100克","origin","user"),"unknowns",List.of())));
  row=(Map<?,?>)((List<?>)IdentificationRules.apply(wrongFood,mixedInput,catalog).get("items")).get(0);check(row.get("portion")==null,"user amount must belong to the identified food, not another dish");
  var rightFood=Map.<String,Object>of("items",List.of(Map.of("name","鸡蛋","preparation","水煮","searchTerms",List.of("鸡蛋"),"portion",Map.of("min",50,"max",50,"unit","g","basis","用户给出鸡蛋50克","origin","user"),"unknowns",List.of())));
  row=(Map<?,?>)((List<?>)IdentificationRules.apply(rightFood,mixedInput,catalog).get("items")).get(0);check(row.get("portion")!=null,"food-bound user amount is retained");
  var many=Map.<String,Object>of("items",List.of(Map.of("name","未匹配食物","preparation","不清楚","searchTerms",List.of("unknownzzzz"),"unknowns",java.util.stream.IntStream.range(0,10).mapToObj(i->"待核对"+i).toList())));
  row=(Map<?,?>)((List<?>)IdentificationRules.apply(many,input,catalog).get("items")).get(0);var hints=(List<?>)row.get("unknowns");check(hints.size()<=10,"generated unknown hints respect the client field limit");check(hints.contains("实际份量待确认")&&hints.contains("尚无已匹配的食物数据，请搜索或保留未知"),"essential missing hints remain visible");
  for(String missingBasis:List.of("餐具无刻度，无已知参照，无法判断真实尺寸","没有可用的参照或尺寸","餐具尺寸未知，估计100克")){
   var noReference=Map.<String,Object>of("items",List.of(Map.of("name","米饭","preparation","熟","searchTerms",List.of("米饭"),"portion",Map.of("min",100,"max",200,"unit","g","basis",missingBasis,"origin","image-estimate"),"unknowns",List.of())));
   row=(Map<?,?>)((List<?>)IdentificationRules.apply(noReference,photoInput,catalog).get("items")).get(0);check(row.get("portion")==null,"negated reference cannot justify an image estimate");
  }
  System.out.println("PASS "+count+" food identification checks");
 }
 private static void check(boolean value,String why){count++;if(!value)throw new AssertionError(why);}
 private static void rejects(Runnable r,String why){try{r.run();throw new AssertionError(why);}catch(Problem p){check(true,why);}}
}

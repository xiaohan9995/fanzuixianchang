package com.fanzuixianchang.core;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** The model observes; the server resolves catalog references. All portions remain unconfirmed. */
public final class IdentificationRules {
 public static final String VERSION="food-identification-v1";
 private IdentificationRules(){}
 public static Contracts.ValidCase validate(Contracts.IdentifyRequest request){
  if(request==null||!Set.of("solo","night").contains(RequestRules.safe(request.scene())))throw Problem.input("食物核对仅用于个人实际餐食，整桌须另行确认个人食用情况。");
  return RequestRules.validate(new Contracts.AnalyzeRequest(request.caseId(),request.revision(),"after",request.scene(),request.text(),Map.of(),request.images(),Map.of(),null));
 }
 public static Map<String,Object> apply(Map<String,Object> raw,Contracts.ValidCase input,FoodCatalog catalog){
  if(raw==null||!allowed(raw,Set.of("items"))||!(raw.get("items") instanceof List<?> items)||items.size()>20)throw Problem.output();
  var result=new ArrayList<Map<String,Object>>();
  for(Object value:items){
   if(!(value instanceof Map<?,?> item)||!allowed(item,Set.of("name","preparation","searchTerms","portion","unknowns")))throw Problem.output();
   String name=text(item.get("name"),200,false),preparation=text(item.get("preparation"),200,true);
   var terms=strings(item.get("searchTerms"),5,100);var unknowns=new ArrayList<>(strings(item.get("unknowns"),10,300));
   var candidates=new LinkedHashMap<String,FoodCatalog.Food>();
   for(String query:terms){for(var food:catalog.search(query)){if(candidates.size()<3 && food.name().matches(".*[\\p{IsHan}].*"))candidates.putIfAbsent(food.id(),food);}}
   if(candidates.isEmpty()){for(var food:catalog.search(name)){if(candidates.size()<3 && food.name().matches(".*[\\p{IsHan}].*"))candidates.putIfAbsent(food.id(),food);}}
   Object portion=portion(item.get("portion"),input,name);
   // Required server hints take priority, but must remain inside the client cap.
   var hints=new LinkedHashSet<String>();
   if(portion==null)hints.add("实际份量待确认");
   if(candidates.isEmpty())hints.add("尚无已匹配的食物数据，请搜索或保留未知");
   for(String hint:unknowns){if(hints.size()<10)hints.add(hint);}
   unknowns=new ArrayList<>(hints);
   var row=new LinkedHashMap<String,Object>();row.put("id","food-"+(result.size()+1));row.put("name",name);row.put("preparation",preparation);row.put("portion",portion);row.put("candidates",List.copyOf(candidates.values()));row.put("unknowns",List.copyOf(unknowns));result.add(Collections.unmodifiableMap(row));
  }
  return Map.of("version",VERSION,"items",List.copyOf(result));
 }
 private static Object portion(Object raw,Contracts.ValidCase input,String foodName){
  if(raw==null)return null;
  if(!(raw instanceof Map<?,?> map)||!allowed(map,Set.of("min","max","unit","basis","origin")))throw Problem.output();
  double min=number(map.get("min")),max=number(map.get("max"));if(max<min||!"g".equals(map.get("unit")))throw Problem.output();
  String basis=text(map.get("basis"),500,true),origin=text(map.get("origin"),30,false);if(!Set.of("user","image-estimate").contains(origin))throw Problem.output();
  if(basis.isBlank())return null;
  if(origin.equals("user")){
   String lo=BigDecimal.valueOf(min).stripTrailingZeros().toPlainString(),hi=BigDecimal.valueOf(max).stripTrailingZeros().toPlainString();
   String numeric=min==max?Pattern.quote(lo):Pattern.quote(lo)+"\\s*(?:至|到|[-–~～])\\s*"+Pattern.quote(hi);
   // A quantity elsewhere in the meal cannot be attributed to this food.
   String amount="(?<![\\d.\\-−])"+numeric+"\\s*(?:克|g)(?![a-zA-Z])";
   String food=Pattern.quote(foodName);
   String link="\\s*(?:(?:实际食用|实际吃了|吃了|食用|份量|重量|大约|大概|约|为|是|共|[:：])\\s*)*";
   String bound="(?:"+food+link+amount+"|"+amount+"\\s*(?:的\\s*)?"+food+")";
   if(!Pattern.compile(bound,Pattern.CASE_INSENSITIVE).matcher(input.text()).find())return null;
  }else{
   if(input.images().isEmpty()||Pattern.compile("通常|标准碗|默认|一般一碗|常见份量").matcher(basis).find())return null;
   String absentReference="(?:无|没有|未见|未提供|未知|缺少|不存在|看不清|无法判断|无法确认|不能确定)[^，。；;]{0,16}(?:参照|刻度|尺寸|直径|厘米|cm|标注|秤)|(?:参照|刻度|尺寸|直径|餐具|标注)[^，。；;]{0,12}(?:不明|不详|未知|不可见|看不清|无法|缺失|没有|无刻度|不清楚)";
   if(Pattern.compile(absentReference,Pattern.CASE_INSENSITIVE).matcher(basis).find())return null;
   if(!Pattern.compile("秤|标注|参照|直径|厘米|cm|刻度|餐具",Pattern.CASE_INSENSITIVE).matcher(basis).find())return null;
  }
  return Map.of("min",min,"max",max,"unit","g","basis",basis,"origin",origin);
 }
 private static boolean allowed(Map<?,?> value,Set<String> keys){return value.keySet().stream().allMatch(keys::contains);}
 private static double number(Object raw){if(!(raw instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<=0||n.doubleValue()>100000)throw Problem.output();return n.doubleValue();}
 private static String text(Object raw,int max,boolean optional){if(raw==null&&optional)return "";if(!(raw instanceof String s)||s.length()>max||!optional&&s.isBlank())throw Problem.output();return s.trim();}
 private static List<String> strings(Object raw,int max,int length){if(raw==null)return List.of();if(!(raw instanceof List<?> values)||values.size()>max)throw Problem.output();var result=new ArrayList<String>();for(Object value:values){var s=text(value,length,false);result.add(s);}return List.copyOf(result);}
}

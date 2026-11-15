package com.fanzuixianchang.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable offline USDA SR Legacy2018 edible-weight catalog; never invent recipe equivalents. */
public final class FoodCatalog {
 public static final String VERSION="usda-sr-2018-04-zh-v2";
 private final Map<String,Food> foods;
 private static final Map<String,String[]> LOCALIZED=loadLocalized();
 private static Map<String,String[]> loadLocalized(){
  try(var stream=FoodCatalog.class.getResourceAsStream("/nutrition/food-names-zh.tsv")){
   if(stream==null)throw new IllegalStateException("Chinese catalog names missing");
   var result=new LinkedHashMap<String,String[]>();
   try(var reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){
    reader.readLine();String line;
    while((line=reader.readLine())!=null){String[] p=line.split("\\t",-1);
     if(p.length!=4||p[1].isBlank()||!p[1].matches(".*[\\p{IsHan}].*")||result.putIfAbsent(p[0],new String[]{p[1],p[2],p[3]})!=null)throw new IllegalStateException("Invalid Chinese catalog name");
    }
   }
   return Collections.unmodifiableMap(result);
  }catch(IOException e){throw new IllegalStateException("Cannot load Chinese catalog names",e);}
 }
 public record Food(String id,String name,String nameEn,String basisUnit,double energyKcalPer100,Double proteinGPer100,Double fatGPer100,Double carbGPer100,Map<String,Object> source,String foodGroup){}
 private FoodCatalog(Map<String,Food> foods){this.foods=Collections.unmodifiableMap(foods);}
 public static FoodCatalog load(){
  try(var stream=FoodCatalog.class.getResourceAsStream("/nutrition/usda-sr2018.tsv")){
   if(stream==null)throw new IllegalStateException("Bundled USDA catalog missing; run scripts/import-usda.py");
   var rows=new LinkedHashMap<String,Food>();
   try(var reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){
    reader.readLine();String line;
    while((line=reader.readLine())!=null){
     String[] p=line.split("\t",-1);if(p.length!=6)throw new IllegalStateException("Invalid bundled USDA row");
     double energy=Double.parseDouble(p[2]);if(!Double.isFinite(energy)||energy<0)throw new IllegalStateException("Invalid bundled energy");
     if(LOCALIZED.containsKey(p[0])&&!LOCALIZED.get(p[0])[2].equals(p[1]))throw new IllegalStateException("Chinese name source mismatch: "+p[0]);
     String name=LOCALIZED.containsKey(p[0])?LOCALIZED.get(p[0])[0]:p[1];
     var source=Map.<String,Object>of("id",p[0],"title","美国农业部食物营养数据库（标准参考版）","version","2018-04","description",p[1],"url","https://fdc.nal.usda.gov/food-details/"+p[0]+"/nutrients");
     if(rows.put(p[0],new Food(p[0],name,p[1],"g",energy,optional(p[3]),optional(p[4]),optional(p[5]),source,foodGroup(p[0])))!=null)throw new IllegalStateException("Duplicate catalog id");
    }
   }
   if(!rows.keySet().containsAll(LOCALIZED.keySet()))throw new IllegalStateException("Chinese name references missing food");
   return new FoodCatalog(rows);
  }catch(IOException|NumberFormatException e){throw new IllegalStateException("Cannot load bundled catalog",e);}
 }
 // Reviewed semantic groups apply only to the exact basic-food entries above.
 // Unknown/unreviewed and composite foods are deliberately not classified.
 private static String foodGroup(String id){
  if(Set.of("168878","169704","170438","168483","168919","173904","173905","172688","169999").contains(id))return "staple";
  if(Set.of("169967","170457","168409","170393","168463","168462","169976","169975","169249","169251","170000","169228","169986").contains(id))return "vegetable";
  if(Set.of("173944","171688","169097","174683","167762","167765","168177","168153").contains(id))return "fruit";
  if(Set.of("173424","172476","171477","175168","172430","167819","171794","171971","172446","170187","170567").contains(id))return "protein";
  if(Set.of("172217","170886","171284").contains(id))return "dairy";
  if(id.equals("171413"))return "oil";
  if(id.equals("169655"))return "other";
  return "unknown";
 }
 private static Double optional(String s){if(s.isEmpty())return null;double value=Double.parseDouble(s);if(!Double.isFinite(value)||value<0||value>1000)throw new IllegalStateException("Invalid bundled nutrient");return value;}
 public int size(){return foods.size();}
 public Food get(String id){var f=foods.get(id);if(f==null)throw Problem.input("这个食物条目不存在，请重新搜索并确认。");return f;}
 public int localizedSize(){return LOCALIZED.size();}
 private static int score(String id,String query){
  var entry=LOCALIZED.get(id);String name=entry[0];
  if(name.equals(query))return 100;
  var aliases=entry[1].split(" ");
  if(Arrays.stream(aliases).anyMatch(query::equals))return 90;
  if(name.startsWith(query+"（"))return 80;
  if(name.contains(query))return 50;
  if(Arrays.stream(aliases).anyMatch(alias->alias.contains(query)))return 40;
  return 0;
 }
 public List<Food> search(String query){return search(query,false);}
 public List<Food> searchLocalized(String query){return search(query,true);}
 private List<Food> search(String query,boolean localizedOnly){
  if(query==null||query.isBlank())return List.of();if(query.length()>100)throw Problem.input("搜索词过长。");
  String q=query.trim().toLowerCase(Locale.ROOT);if(q.matches(".*[\\p{IsHan}].*"))q=q.replaceAll("\\s+","");
  final String term=q;String[] words=q.split("\\s+");
  var result=new ArrayList<Food>();
  // Exact names and aliases outrank substring matches; never match a dish by one ingredient.
  LOCALIZED.keySet().stream().filter(id->score(id,term)>0)
   .sorted(Comparator.<String>comparingInt(id->score(id,term)).reversed().thenComparing(id->id))
   .forEach(id->result.add(foods.get(id)));
  for(var f:foods.values()){
   if(result.size()>=20)break;if(result.contains(f)||localizedOnly&&!LOCALIZED.containsKey(f.id()))continue;
   String hay=f.nameEn().toLowerCase(Locale.ROOT);
   if(f.id().equals(q)||Arrays.stream(words).allMatch(hay::contains))result.add(f);
  }
  return List.copyOf(result.subList(0,Math.min(20,result.size())));
 }
}

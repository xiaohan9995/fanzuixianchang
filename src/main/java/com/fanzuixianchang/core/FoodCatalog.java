package com.fanzuixianchang.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable offline USDA SR Legacy2018 edible-weight catalog; never invent recipe equivalents. */
public final class FoodCatalog {
 public static final String VERSION="usda-sr-2018-04-v1";
 private final Map<String,Food> foods;
 private static final Map<String,String[]> LOCALIZED=Map.ofEntries(
  Map.entry("171869",new String[]{"汤力汽水（含糖）","汤力水 汤力汽水"}),
  Map.entry("171922",new String[]{"依云瓶装水（无气）","依云 瓶装水"}),
  Map.entry("171926",new String[]{"果味瓶装水（无气、低热量甜味剂）","果味水"}),
  Map.entry("173204",new String[]{"低热量汽水（非可乐类、不含咖啡因）","无糖汽水 低热量汽水"}),
  Map.entry("171876",new String[]{"低热量可乐类汽水（糖精钠、含咖啡因）","无糖可乐"}),
  Map.entry("168878",new String[]{"熟白米饭（长粒、强化）","米饭 白米饭 熟米饭 白饭 米饭熟"}),
  Map.entry("169704",new String[]{"熟糙米饭（长粒）","糙米饭 糙米"}),
  Map.entry("173424",new String[]{"水煮全蛋","鸡蛋 水煮蛋 煮鸡蛋 白煮蛋 熟鸡蛋"}),
  Map.entry("172476",new String[]{"普通豆腐（生，硫酸钙凝固）","豆腐 普通豆腐"}),
  Map.entry("171477",new String[]{"鸡胸肉（去皮、熟、烤）","鸡胸 鸡胸肉 烤鸡胸 去皮鸡胸 熟鸡胸"}),
  Map.entry("173944",new String[]{"香蕉（生）","香蕉"}),
  Map.entry("171688",new String[]{"带皮苹果（生）","苹果"}),
  Map.entry("169967",new String[]{"西兰花（水煮、沥干、不加盐）","西兰花"}),
  Map.entry("171413",new String[]{"橄榄油","橄榄油 油"}),
  Map.entry("170457",new String[]{"红番茄（生）","番茄 西红柿"}),
  Map.entry("168409",new String[]{"带皮黄瓜（生）","黄瓜"}),
  Map.entry("170393",new String[]{"胡萝卜（生）","胡萝卜"}),
  Map.entry("170438",new String[]{"土豆（水煮、带皮煮后取肉、无盐）","土豆 马铃薯"}),
  Map.entry("168483",new String[]{"红薯（带皮烤后取肉、无盐）","红薯 地瓜"}),
  Map.entry("168919",new String[]{"鸡蛋面（强化、煮熟、加盐）","面条 鸡蛋面"}),
  Map.entry("175168",new String[]{"大西洋养殖三文鱼（熟、干热烹调）","三文鱼 鲑鱼"}),
  Map.entry("169655",new String[]{"白砂糖","白糖 糖 白砂糖"}),
  Map.entry("172430",new String[]{"花生（生）","花生 生花生"}),
  Map.entry("172217",new String[]{"全脂牛奶（3.25%脂肪，未额外添加维生素A/D）","牛奶 全脂牛奶 纯牛奶"}),
  Map.entry("173904",new String[]{"原味燕麦片（干，未强化）","燕麦 燕麦片 干燕麦"}),
  Map.entry("173905",new String[]{"燕麦粥（水煮、不加盐）","燕麦粥 熟燕麦"}),
  Map.entry("172688",new String[]{"全麦面包（商品通用参考）","全麦面包 面包"}),
  Map.entry("169999",new String[]{"甜玉米（黄粒、水煮、无盐）","玉米 甜玉米"}),
  Map.entry("167819",new String[]{"猪里脊部位（带可分离脂肪、熟、炖）","猪肉 炖猪肉"}),
  Map.entry("171794",new String[]{"牛肉碎（90%瘦、10%脂肪、熟、煎）","牛肉 牛肉碎"}),
  Map.entry("171971",new String[]{"虾（熟、湿热烹调，可能含保水添加剂）","虾 熟虾"}),
  Map.entry("170886",new String[]{"原味低脂酸奶","酸奶 低脂酸奶"}),
  Map.entry("171284",new String[]{"原味全脂酸奶","酸奶 全脂酸奶"}),
  Map.entry("172446",new String[]{"豆乳饮料（原味/香草，未强化）","豆奶 豆乳"}),
  Map.entry("168463",new String[]{"菠菜（水煮、沥干、无盐）","菠菜 熟菠菜"}),
  Map.entry("168462",new String[]{"菠菜（生）","菠菜 生菠菜"}),
  Map.entry("169976",new String[]{"卷心菜（水煮、沥干、无盐）","卷心菜 包菜 熟包菜"}),
  Map.entry("169975",new String[]{"卷心菜（生）","卷心菜 包菜 生包菜"}),
  Map.entry("169249",new String[]{"绿叶生菜（生）","生菜"}),
  Map.entry("169251",new String[]{"白蘑菇（生）","蘑菇 白蘑菇"}),
  Map.entry("170000",new String[]{"洋葱（生）","洋葱"}),
  Map.entry("169228",new String[]{"茄子（生）","茄子"}),
  Map.entry("169986",new String[]{"花椰菜（生）","花菜 花椰菜 菜花"}),
  Map.entry("169097",new String[]{"橙子（生，通用商业品种）","橙子 橙"}),
  Map.entry("174683",new String[]{"红或绿葡萄（欧洲型，生）","葡萄"}),
  Map.entry("167762",new String[]{"草莓（生）","草莓"}),
  Map.entry("167765",new String[]{"西瓜（生）","西瓜"}),
  Map.entry("168177",new String[]{"亚洲梨（生）","梨 亚洲梨"}),
  Map.entry("170187",new String[]{"核桃仁","核桃 核桃仁"}),
  Map.entry("170567",new String[]{"扁桃仁（巴旦木）","扁桃仁 巴旦木"}),
  Map.entry("168153",new String[]{"绿心猕猴桃（生）","猕猴桃 奇异果"})
 );
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
     String name=LOCALIZED.containsKey(p[0])?LOCALIZED.get(p[0])[0]:p[1];
     var source=Map.<String,Object>of("id",p[0],"title","美国农业部食物营养数据库（标准参考版）","version","2018-04","description",p[1],"url","https://fdc.nal.usda.gov/food-details/"+p[0]+"/nutrients");
     if(rows.put(p[0],new Food(p[0],name,p[1],"g",energy,optional(p[3]),optional(p[4]),optional(p[5]),source,foodGroup(p[0])))!=null)throw new IllegalStateException("Duplicate catalog id");
    }
   }
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
 public List<Food> search(String query){return search(query,false);}
 public List<Food> searchLocalized(String query){return search(query,true);}
 private List<Food> search(String query,boolean localizedOnly){
  if(query==null||query.isBlank())return List.of();if(query.length()>100)throw Problem.input("搜索词过长。");
  String q=query.trim().toLowerCase(Locale.ROOT);String[] words=q.split("\\s+");
  var result=new ArrayList<Food>();
  // Localized exact-ID matches come first, never a generic automatic substitution.
  LOCALIZED.keySet().stream().sorted().forEach(id->{var f=foods.get(id);if(f!=null&&(f.name().contains(q)||Arrays.stream(LOCALIZED.get(id)[1].split(" ")).anyMatch(a->a.contains(q)||q.equals(a))))result.add(f);});
  for(var f:foods.values()){
   if(result.size()>=20)break;if(result.contains(f)||localizedOnly&&!LOCALIZED.containsKey(f.id()))continue;
   String hay=f.nameEn().toLowerCase(Locale.ROOT);
   if(f.id().equals(q)||Arrays.stream(words).allMatch(hay::contains))result.add(f);
  }
  return List.copyOf(result.subList(0,Math.min(20,result.size())));
 }
}

package com.fanzuixianchang.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ChineseCatalogTest {
 private final FoodCatalog catalog=FoodCatalog.load();
 @Test void coversCommonChineseFoodQueries(){
  assertEquals(154,catalog.localizedSize());
  for(String q:new String[]{"米饭","米粉","米线","荞麦面","粉丝","馄饨皮","腐乳","嫩豆腐","毛豆","黄豆芽","绿豆芽","大白菜","小白菜","芥蓝","莲藕","香菇","金针菇","平菇","冬瓜","芋头","马蹄","白萝卜","西葫芦","四季豆","荷兰豆","荔枝","龙眼","榴莲","芒果","车厘子","板栗","腰果","莲子","芝麻酱","香油","酱油","鸭蛋","鹌鹑蛋","无糖汽水"}){
   assertFalse(catalog.searchLocalized(q).isEmpty(),q);
   assertTrue(catalog.searchLocalized(q).stream().allMatch(f->f.name().matches(".*[\\p{IsHan}].*")),q);
  }
 }
 @Test void aliasesPreferExactFoodAndKeepPreparationDistinct(){
  assertEquals("169941",catalog.searchLocalized("柿子").get(0).id());
  assertEquals("169942",catalog.searchLocalized("柿饼").get(0).id());
  assertEquals("169242",catalog.searchLocalized("鲜香菇").get(0).id());
  assertEquals("168436",catalog.searchLocalized("干香菇").get(0).id());
  assertEquals("168914",catalog.searchLocalized("米 线").get(0).id());
 }
 @Test void neverPretendCompositeMealsAreSingleIngredients(){
  for(String q:new String[]{"红烧肉","番茄炒蛋","麻辣烫","宫保鸡丁","珍珠奶茶","油条","馒头"})assertTrue(catalog.searchLocalized(q).isEmpty(),q);
 }
 @Test void numbersAndEnglishProvenanceStayUnchanged(){
  var f=catalog.get("169382");assertEquals("金针菇（生）",f.name());assertEquals("Mushrooms, enoki, raw",f.nameEn());assertTrue(f.source().get("url").toString().contains("169382"));assertEquals(37,f.energyKcalPer100());
 }
}

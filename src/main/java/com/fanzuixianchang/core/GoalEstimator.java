package com.fanzuixianchang.core;
import java.util.*;

/** A disclosed, unreviewed maintenance reference. No deficit or disease-specific prescription. */
public final class GoalEstimator {
 public static final String VERSION="mifflin-maintenance-reference-v1";
 private static final Map<String,Object> SOURCE=Map.of("title","Mifflin–St Jeor1990，活动系数由用户确认","url","https://pubmed.ncbi.nlm.nih.gov/2305711/");
 private GoalEstimator(){}
 public static Map<String,Object> estimate(Map<String,Object> input){
  if(input==null)throw Problem.input("请提供必要资料。");
  double age=NutritionCalculator.number(input.get("age"),0,120,"年龄");if(age!=Math.floor(age))throw Problem.input("年龄应为整数。");
  String sex=NutritionCalculator.string(input.get("sex"),10,"公式参数");if(!Set.of("male","female").contains(sex))throw Problem.input("请选择公式所需的生理性别参数。");
  double height=NutritionCalculator.number(input.get("heightCm"),80,250,"身高"),weight=NutritionCalculator.number(input.get("weightKg"),20,350,"体重"),activity=NutritionCalculator.number(input.get("activityFactor"),1.4,2.5,"活动系数");
  if(!(input.get("screening") instanceof Map<?,?> screening))throw Problem.input("请确认自动估算适用情况。");
  boolean risk=false;
  for(String key:List.of("pregnantOrBreastfeeding","medicalNutrition","eatingDisorder")){if(!(screening.get(key) instanceof Boolean))throw Problem.input("请逐项确认自动估算适用情况。");risk|=Boolean.TRUE.equals(screening.get(key));}
  var assumptions=List.of("仅为未完成专业审阅的维持能量参考，不是减脂或治疗处方。","活动系数由你确认，未测量实际消耗；此简化方法不等同于NIDDK动态体重模型。","身高体重以本次填写为准；未加入任何自动减脂幅度。","计算出处采用Mifflin–St Jeor简化式，活动系数参考范围说明：https://www.niddk.nih.gov/bwp");
  var result=new LinkedHashMap<String,Object>();result.put("version",VERSION);result.put("source",SOURCE);result.put("assumptions",assumptions);
  if(age<18||age>64||weight/Math.pow(height/100,2)<18.5||risk){result.put("eligible",false);result.put("reason","当前情况不适用普通成人自动参考。可以继续记录饮食，并咨询适合你的专业指导。");return Collections.unmodifiableMap(result);}
  double ree=10*weight+6.25*height-5*age+(sex.equals("male")?5:-161);
  if(ree<=0)throw Problem.input("输入组合不适用于该估算，请重新核对。");
  result.put("eligible",true);result.put("reeKcal",ree);result.put("maintenanceKcal",Math.round(ree*activity/50)*50);
  return Collections.unmodifiableMap(result);
 }
}

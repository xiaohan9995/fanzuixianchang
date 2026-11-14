package com.fanzuixianchang.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public final class LabelCalculator {
    private LabelCalculator() {}
    public record Calculation(double per100,double amount,String unit,double total,String formula) {
        public Map<String,Object> asMap(){return Map.of("per100",per100,"amount",amount,"unit",unit,"total",total,"formula",formula);}
    }
    public static Calculation calculate(double per100,double amount,String unit) {
        if(!Double.isFinite(per100)||!Double.isFinite(amount)||per100<0||per100>100_000||amount<=0||amount>100_000)
            throw Problem.input("标签数值和食用量需为有效数值，食用量必须大于零。");
        if(!Set.of("kJ","kcal","g").contains(unit))throw Problem.input("标签单位支持 kJ、kcal 或 g。");
        var p=BigDecimal.valueOf(per100);var a=BigDecimal.valueOf(amount);
        double total=p.multiply(a).divide(BigDecimal.valueOf(100),6,RoundingMode.HALF_UP).doubleValue();
        String formula=p.stripTrailingZeros().toPlainString()+" × "+a.stripTrailingZeros().toPlainString()+" ÷ 100 = "+BigDecimal.valueOf(total).stripTrailingZeros().toPlainString()+" "+unit;
        return new Calculation(per100,amount,unit,total,formula);
    }
}

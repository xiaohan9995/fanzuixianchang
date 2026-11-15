package com.fanzuixianchang.ai;

import com.fanzuixianchang.core.Contracts;
import com.fanzuixianchang.core.Problem;
import java.util.Map;

public interface ModelGateway {
    boolean configured();
    Contracts.AnalysisResponse analyze(Contracts.ValidCase input);
    default Map<String,Object> estimateMeal(Contracts.ValidCase input){throw new Problem(503,"NOT_CONFIGURED","餐食估算尚未配置，可以先保存草稿或手动记录。");}
    default Map<String,Object> identifyFoods(Contracts.ValidCase input){throw new Problem(503,"NOT_CONFIGURED","食物识别尚未配置，仍可手动录入并核算。");}
}

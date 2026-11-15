package com.fanzuixianchang.ai;

import com.fanzuixianchang.core.Contracts;
import com.fanzuixianchang.core.Problem;
import java.util.Map;

public interface ModelGateway {
    boolean configured();
    Contracts.AnalysisResponse analyze(Contracts.ValidCase input);
    default Map<String,Object> estimateMeal(Contracts.ValidCase input){throw new Problem(503,"NOT_CONFIGURED","餐食估算尚未配置，可以先保存草稿或手动记录。");}
    default Map<String,Object> identifyFoods(Contracts.ValidCase input){throw new Problem(503,"NOT_CONFIGURED","食物识别尚未配置，仍可手动录入并核算。");}
    default Map<String,Object> estimateActivity(com.fanzuixianchang.core.ActivityRules.Valid input){throw new Problem(503,"NOT_CONFIGURED","运动估算尚未配置，可以先手填消耗。");}
    /** 周复盘：facts 是客户端算好的统计量，网关只负责把它讲成人话。 */
    default Map<String,Object> weekReview(Map<String,Object> facts){throw new Problem(503,"NOT_CONFIGURED","周复盘尚未配置，先看这周的统计。");}
}

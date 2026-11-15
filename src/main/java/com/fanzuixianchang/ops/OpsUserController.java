package com.fanzuixianchang.ops;

import com.fanzuixianchang.account.AdminUserStore;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public final class OpsUserController {
    private static final String ACTOR="fanzuixianchang.adminActor";
    private final AdminUserStore users;

    public OpsUserController(AdminUserStore users){this.users=users;}

    @GetMapping("/api/ops/user-dashboard")
    public Map<String,Object> dashboard(){return users.dashboard();}

    @GetMapping("/api/ops/users")
    public Map<String,Object> users(@RequestParam(required=false)String query,
                                    @RequestParam(required=false)String status,
                                    @RequestParam(required=false)Boolean sync,
                                    @RequestParam(required=false)String tier,
                                    @RequestParam(defaultValue="1")Integer page,
                                    @RequestParam(defaultValue="20")Integer pageSize){
        return users.users(new AdminUserStore.UserQuery(query,status,sync,tier,page,pageSize));
    }

    @GetMapping("/api/ops/users/{id}")
    public Map<String,Object> user(@PathVariable String id){return users.detail(id);}

    @PostMapping("/api/ops/users/{id}/points")
    public Map<String,Object> points(@PathVariable String id,@RequestBody AdminUserStore.PointInput input,@RequestAttribute(ACTOR)String actor){return users.adjustPoints(id,input,actor);}

    @GetMapping("/api/ops/plans")
    public Map<String,Object> plans(){return users.plans();}

    @PutMapping("/api/ops/plans/{code}")
    public Map<String,Object> plan(@PathVariable String code,@RequestBody AdminUserStore.PlanInput input,@RequestAttribute(ACTOR)String actor){return users.savePlan(code,input,actor);}

    @PostMapping("/api/ops/users/{id}/entitlements")
    public Map<String,Object> entitlement(@PathVariable String id,@RequestBody AdminUserStore.EntitlementInput input,@RequestAttribute(ACTOR)String actor){return users.assignEntitlement(id,input,actor);}

    @PostMapping("/api/ops/users/{id}/entitlements/{entitlementId}/revoke")
    public Map<String,Object> revoke(@PathVariable String id,@PathVariable String entitlementId,@RequestBody RevokeInput input,@RequestAttribute(ACTOR)String actor){return users.revokeEntitlement(id,entitlementId,input==null?null:input.reason(),actor);}

    @GetMapping("/api/ops/settings")
    public Map<String,Object> settings(){return users.settings();}

    @GetMapping("/api/content/announcement")
    public Map<String,Object> announcement(){return users.settings();}

    @PutMapping("/api/ops/settings")
    public Map<String,Object> settings(@RequestBody AdminUserStore.SettingsInput input,@RequestAttribute(ACTOR)String actor){return users.saveSettings(input,actor);}

    @GetMapping("/api/ops/admin-audit")
    public Map<String,Object> audit(){return users.audit();}

    public record RevokeInput(String reason){}
}

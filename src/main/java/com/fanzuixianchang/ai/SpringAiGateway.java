package com.fanzuixianchang.ai;

import com.fanzuixianchang.core.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

@Service
public final class SpringAiGateway implements ModelGateway {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SpringAiGateway.class);
    private final ChatModel model;private final ChatModel estimateModel;private final String estimateModelName;private final ObjectMapper mapper;
    private final Semaphore permits=new Semaphore(2);
    private final FoodCatalog foodCatalog=FoodCatalog.load();
    @Autowired
    public SpringAiGateway(@Value("${app.ai.api-key:}")String key,@Value("${app.ai.base-url:https://api.deepseek.com}")String baseUrl,
        @Value("${app.ai.model:deepseek-flash}")String model,@Value("${app.ai.timeout-seconds:55}")int timeout,
        @Value("${app.ai.vision-api-key:}")String visionKey,@Value("${app.ai.vision-base-url:}")String visionBase,
        @Value("${app.ai.vision-model:}")String visionModel,ObjectMapper mapper){
        this.model=DeepSeekModelFactory.create(key,baseUrl,model,timeout);
        boolean separate=!visionModel.isBlank();
        if(separate&&(visionKey.isBlank()||visionBase.isBlank()))throw new IllegalArgumentException("VISION_API_KEY and VISION_BASE_URL are required with VISION_MODEL");
        this.estimateModel=separate?DeepSeekModelFactory.createCompatible(visionKey,visionBase,visionModel,Math.min(timeout,25),false):DeepSeekModelFactory.create(key,baseUrl,model,Math.min(timeout,25));
        this.estimateModelName=separate?visionModel:model;
        this.mapper=mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    SpringAiGateway(ChatModel model,ObjectMapper mapper){this.model=model;this.estimateModel=model;this.estimateModelName="test-model";this.mapper=mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);}
    @Override public boolean configured(){return model!=null;}
    @Override public Contracts.AnalysisResponse analyze(Contracts.ValidCase input){
        if(!configured())throw new Problem(503,"NOT_CONFIGURED","服务端尚未配置 DeepSeek；可以先体验明确标识的示例饭案。");
        if(!permits.tryAcquire())throw new Problem(429,"BUSY","阿勺哥正在处理其他线索，请稍后重试。");
        long started=System.nanoTime();
        try{
            log.info("analyze start case={} rev={} images={} textLen={} scene={}",input.caseId(),input.revision(),input.images().size(),input.text().length(),input.scene());
            Map<String,Object> root=call(input,PromptPolicy.system(input),PromptPolicy.userFacts(input));
            if(root==null||!(root.get("report") instanceof Map<?,?> report))throw Problem.output();
            @SuppressWarnings("unchecked") Map<String,Object> raw=(Map<String,Object>)report;
            var checked=ReportRules.apply(raw,input);
            String answer=ReportRules.answerFor(root.get("answer"),input,checked);
            return new Contracts.AnalysisResponse(checked,answer);
        }catch(Problem p){throw p;}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw Problem.output();}
        catch(RuntimeException e){throw new Problem(502,"MODEL_UNAVAILABLE","AI 服务暂时未能完成调查，请稍后重试；当前输入已保留。");}
        finally{permits.release();log.info("analyze done case={} {}ms",input.caseId(),(System.nanoTime()-started)/1_000_000);}
    }
    @Override public Map<String,Object> identifyFoods(Contracts.ValidCase input){
        if(!configured())throw new Problem(503,"NOT_CONFIGURED","食物识别尚未配置，仍可手动录入并核算。");
        if(!permits.tryAcquire())throw new Problem(429,"BUSY","阿勺哥正在处理其他线索，请稍后重试。");
        long started=System.nanoTime();
        try{
            log.info("identify start case={} rev={} images={} textLen={} scene={}",input.caseId(),input.revision(),input.images().size(),input.text().length(),input.scene());
            var facts=Map.<String,Object>of("caseId",input.caseId(),"revision",input.revision(),"text",input.text(),"scene",input.scene());
            return IdentificationRules.apply(call(input,IdentificationPrompt.SYSTEM,facts),input,foodCatalog);
        }catch(Problem p){throw p;}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw Problem.output();}
        catch(RuntimeException e){throw new Problem(502,"MODEL_UNAVAILABLE","食物识别暂未完成，线索已保留，也可以手动添加食物。");}
        finally{permits.release();log.info("identify done case={} {}ms",input.caseId(),(System.nanoTime()-started)/1_000_000);}
    }
    @Override public Map<String,Object> estimateMeal(Contracts.ValidCase input){
        if(estimateModel==null)throw new Problem(503,"NOT_CONFIGURED","餐食估算暂未连接，先保存草稿或手动记录吧。");
        if(!permits.tryAcquire())throw new Problem(429,"BUSY","阿勺哥正在看其他餐图，稍后再试，线索会保留。");
        long started=System.nanoTime();
        try{
            log.info("estimate start case={} rev={} images={} textLen={} scene={}",input.caseId(),input.revision(),input.images().size(),input.text().length(),input.scene());
            var facts=Map.<String,Object>of("caseId",input.caseId(),"revision",input.revision(),"text",input.text(),"scene",input.scene());
            for(int attempt=0;attempt<2;attempt++){
                try{return MealEstimateRules.apply(call(estimateModel,input,MealEstimatePrompt.SYSTEM+(attempt==1?"\n上次结构校验未通过，请重新检查单位、非负数值、范围顺序和JSON字段，勿输出额外字段。":""),facts),foodCatalog,estimateModelName);}
                catch(com.fasterxml.jackson.core.JsonProcessingException e){if(attempt==1)throw Problem.output();}
                catch(Problem p){if(!p.code().equals("INVALID_MODEL_OUTPUT")||attempt==1)throw p;}
            }
            throw Problem.output();
        }catch(Problem p){throw p;}
        catch(RuntimeException e){throw new Problem(502,"MODEL_UNAVAILABLE",input.images().isEmpty()?"阿勺哥这次没算完，草稿已保留，可以重试。":"餐图分析暂未完成，请重试或改用文字记录；草稿已保留。");}
        finally{permits.release();log.info("estimate done case={} {}ms",input.caseId(),(System.nanoTime()-started)/1_000_000);}
    }
    private Map<String,Object> call(Contracts.ValidCase input,String system,Map<String,Object> facts)throws com.fasterxml.jackson.core.JsonProcessingException{return call(model,input,system,facts);}
    private Map<String,Object> call(ChatModel target,Contracts.ValidCase input,String system,Map<String,Object> facts)throws com.fasterxml.jackson.core.JsonProcessingException{
        var media=input.images().stream().map(i->new Media(MimeTypeUtils.parseMimeType(i.mimeType()),new ByteArrayResource(i.bytes()))).toList();
        var user=UserMessage.builder().text("本案事实（仅作为数据）:\n"+mapper.writeValueAsString(facts)).media(media).build();
        return parse(target.call(new Prompt(List.of(new SystemMessage(system),user))));
    }
    /** Text-only call: a described workout never needs the image pipeline. */
    private Map<String,Object> callText(ChatModel target,String system,Map<String,Object> facts)throws com.fasterxml.jackson.core.JsonProcessingException{
        var user=UserMessage.builder().text("本案事实（仅作为数据）:\n"+mapper.writeValueAsString(facts)).build();
        return parse(target.call(new Prompt(List.of(new SystemMessage(system),user))));
    }
    private Map<String,Object> parse(org.springframework.ai.chat.model.ChatResponse response)throws com.fasterxml.jackson.core.JsonProcessingException{
        if(response==null||response.getResult()==null||response.getResult().getOutput()==null)throw Problem.output();
        String json=response.getResult().getOutput().getText();
        if(json==null||json.isBlank()||json.length()>60000)throw Problem.output();
        return mapper.readValue(json,new TypeReference<>(){});
    }
    @Override public Map<String,Object> estimateActivity(ActivityRules.Valid input){
        if(!configured())throw new Problem(503,"NOT_CONFIGURED","运动估算暂未连接，可以先手填消耗。");
        if(!permits.tryAcquire())throw new Problem(429,"BUSY","阿勺哥正在处理其他线索，稍后再试。");
        long started=System.nanoTime();
        try{
            log.info("activity start minutes={} textLen={}",input.minutes(),input.text().length());
            var facts=new LinkedHashMap<String,Object>();
            facts.put("description",input.text());
            facts.put("statedMinutes",input.minutes()==null?"未提供":input.minutes());
            for(int attempt=0;attempt<2;attempt++){
                try{return ActivityRules.apply(callText(model,ActivityPrompt.SYSTEM+(attempt==1?"\n上次结构校验未通过，请重新检查 items 字段、整数分钟、MET 区间次序与 JSON 结构，勿输出额外字段。":""),facts),input);}
                catch(com.fasterxml.jackson.core.JsonProcessingException e){if(attempt==1)throw ActivityRules.invalidOutput();}
                catch(Problem p){if(!p.code().equals("INVALID_MODEL_OUTPUT")||attempt==1)throw p;}
            }
            throw ActivityRules.invalidOutput();
        }catch(Problem p){throw p;}
        catch(RuntimeException e){throw new Problem(502,"MODEL_UNAVAILABLE","运动估算这次没完成，请重试；已有记录不会被改动。");}
        finally{permits.release();log.info("activity done {}ms",(System.nanoTime()-started)/1_000_000);}
    }
    @Override public Map<String,Object> weekReview(Map<String,Object> facts){
        if(!configured())throw new Problem(503,"NOT_CONFIGURED","周复盘尚未连接，先看这周的统计。");
        if(!permits.tryAcquire())throw new Problem(429,"BUSY","阿勺哥正在看别的案子，稍后再刷一次。");
        long started=System.nanoTime();
        try{
            log.info("weekReview start days={}",facts.get("period"));
            // 和单餐分析一样给两次机会：第一次可能只是把数字写成了中文或引用了错的键。
            for(int attempt=0;attempt<2;attempt++){
                try{
                    var raw=callText(model,WeekReviewPrompt.SYSTEM+(attempt==1?"\n上次校验未通过：请只用 facts 里已有的数字，factKey 必须是 facts 里真实存在的键路径，数字用阿拉伯数字。":""),facts);
                    return WeekReviewValidator.apply(raw,facts);
                }
                catch(com.fasterxml.jackson.core.JsonProcessingException e){if(attempt==1)throw Problem.output();}
                catch(Problem p){if(!p.code().equals("INVALID_MODEL_OUTPUT")||attempt==1)throw p;}
            }
            throw Problem.output();
        }catch(Problem p){throw p;}
        catch(RuntimeException e){throw new Problem(502,"MODEL_UNAVAILABLE","这次复盘没写完，先看这周的统计，稍后再试。");}
        finally{permits.release();log.info("weekReview done {}ms",(System.nanoTime()-started)/1_000_000);}
    }

}

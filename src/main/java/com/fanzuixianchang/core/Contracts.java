package com.fanzuixianchang.core;

import java.util.*;

public final class Contracts {
    private Contracts() {}
    public record IdentifyRequest(String caseId,Integer revision,String text,List<ImageInput> images,String scene) {}
    public record ImageInput(String mimeType,String base64) {}
    public record AnalyzeRequest(String caseId,Integer revision,String mode,String scene,String text,
        Map<String,Object> conditions,List<ImageInput> images,Map<String,Object> preferences,String question,Map<String,Object> context,Map<String,Object> dailyContext) {
        public AnalyzeRequest(String caseId,Integer revision,String mode,String scene,String text,Map<String,Object> conditions,List<ImageInput> images,Map<String,Object> preferences,String question,Map<String,Object> context){this(caseId,revision,mode,scene,text,conditions,images,preferences,question,context,Map.of());}
        public AnalyzeRequest(String caseId,Integer revision,String mode,String scene,String text,Map<String,Object> conditions,List<ImageInput> images,Map<String,Object> preferences,String question){this(caseId,revision,mode,scene,text,conditions,images,preferences,question,Map.of(),Map.of());}
    }
    public record ValidImage(String mimeType,byte[] bytes) {
        public ValidImage {bytes=bytes.clone();}
        @Override public byte[] bytes(){return bytes.clone();}
    }
    public record ValidCase(String caseId,int revision,String mode,String scene,String text,
        Map<String,Object> conditions,List<ValidImage> images,Map<String,Object> preferences,String question,Map<String,Object> context,Map<String,Object> dailyContext) {
        public ValidCase(String caseId,int revision,String mode,String scene,String text,Map<String,Object> conditions,List<ValidImage> images,Map<String,Object> preferences,String question,Map<String,Object> context){this(caseId,revision,mode,scene,text,conditions,images,preferences,question,context,Map.of());}
        public ValidCase(String caseId,int revision,String mode,String scene,String text,Map<String,Object> conditions,List<ValidImage> images,Map<String,Object> preferences,String question){this(caseId,revision,mode,scene,text,conditions,images,preferences,question,Map.of(),Map.of());}
    }
    public record AnalysisResponse(Map<String,Object> report,String answer) {}
    public record SessionRequest(String code) {}
}

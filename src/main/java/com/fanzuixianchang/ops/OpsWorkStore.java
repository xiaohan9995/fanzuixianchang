package com.fanzuixianchang.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanzuixianchang.core.Problem;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Bounded versioned workflow queue; approvals do not modify nutritional data. */
@Component
public final class OpsWorkStore {
 public record FeedbackInput(String category,String message,Boolean consent){}
 public record FeedbackChange(long version,String status,String resolution){}
 public record Feedback(String id,long version,String category,String message,String status,String resolution,String createdAt,String updatedAt){}
 public record ProposalInput(String name,String aliases,String sourceUrl,String sourceNote){}
 public record Review(long version,String status,String reviewNote){}
 public record Version(long version){}
 public record Proposal(String id,long version,String name,String aliases,String sourceUrl,String sourceNote,String status,String reviewNote,String createdAt,String updatedAt){}
 public record Audit(String type,String id,String action,long version,String at){}
 public record State(List<Feedback> feedback,List<Proposal> proposals,List<Audit> audit){}
 private static final int LIMIT=1000;
 private final ObjectMapper mapper;private final OpsPersistence persistence;private State state;private long persistedRevision=-1;
 public OpsWorkStore(ObjectMapper mapper,String dir){this(mapper,new FileOpsPersistence(mapper,Path.of(dir)));}
 @Autowired public OpsWorkStore(ObjectMapper mapper,OpsPersistence persistence){this.mapper=mapper;this.persistence=persistence;reload();}
 private void reload(){var snapshot=persistence.load("workflows");try{var loaded=snapshot==null?new State(List.of(),List.of(),List.of()):mapper.readValue(snapshot.json(),State.class);if(loaded.feedback()==null||loaded.proposals()==null||loaded.audit()==null||loaded.feedback().size()>LIMIT||loaded.proposals().size()>LIMIT||loaded.audit().size()>500)throw new IOException("Invalid workflow store");state=new State(List.copyOf(loaded.feedback()),List.copyOf(loaded.proposals()),List.copyOf(loaded.audit()));persistedRevision=snapshot==null?-1:snapshot.revision();}catch(IOException|IllegalArgumentException e){throw new Problem(503,"STORE_UNAVAILABLE","运营资料读取失败，请稍后重试。");}}
 public synchronized List<Feedback> feedback(){reload();return state.feedback();}
 public synchronized List<Proposal> proposals(){reload();return state.proposals();}
 public synchronized List<Audit> audit(){reload();return state.audit();}
 public synchronized Feedback createFeedback(FeedbackInput input){reload();
  if(input==null||!Boolean.TRUE.equals(input.consent())||input.category()==null||!Set.of("recognition","missing_food","portion","suggestion","other").contains(input.category()))throw invalid("请选择反馈类型并同意提交反馈文字。");
  String message=required(input.message(),2000,"反馈内容");capacity(state.feedback().size());String now=Instant.now().toString();
  var item=new Feedback(UUID.randomUUID().toString(),0,input.category(),message,"open","",now,now);var items=new ArrayList<>(state.feedback());items.add(0,item);commit(items,state.proposals(),new Audit("feedback",item.id(),"create",0,now));return item;
 }
 public synchronized Feedback updateFeedback(String id,FeedbackChange change){reload();
  if(change==null||change.status()==null||!Set.of("open","processing","resolved").contains(change.status()))throw invalid("反馈状态无效。");
  var old=state.feedback().stream().filter(f->f.id().equals(id)).findFirst().orElseThrow(OpsWorkStore::missing);check(old.version(),change.version());
  String resolution=optional(change.resolution(),1000,"处理说明");if(change.status().equals("resolved")&&resolution.isBlank())throw invalid("关闭反馈前请填写处理说明。");
  String now=Instant.now().toString();var next=new Feedback(old.id(),old.version()+1,old.category(),old.message(),change.status(),resolution,old.createdAt(),now);
  var items=state.feedback().stream().map(f->f.id().equals(id)?next:f).toList();commit(items,state.proposals(),new Audit("feedback",id,change.status(),next.version(),now));return next;
 }
 public synchronized Proposal createProposal(ProposalInput input){reload();
  if(input==null)throw invalid("请填写食物资料。");String name=required(input.name(),100,"名称"),aliases=optional(input.aliases(),300,"别名"),url=sourceUrl(input.sourceUrl()),note=required(input.sourceNote(),1000,"来源说明");capacity(state.proposals().size());String now=Instant.now().toString();
  var item=new Proposal(UUID.randomUUID().toString(),0,name,aliases,url,note,"pending","",now,now);var items=new ArrayList<>(state.proposals());items.add(0,item);commit(state.feedback(),items,new Audit("proposal",item.id(),"create",0,now));return item;
 }
 public synchronized Proposal review(String id,Review review){reload();
  if(review==null||review.status()==null||!Set.of("approved","rejected").contains(review.status()))throw invalid("审核状态无效。");var old=state.proposals().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(OpsWorkStore::missing);check(old.version(),review.version());if(!old.status().equals("pending"))throw invalid("仅待审核资料可以审核，请新建提案记录修订。");
  String note=required(review.reviewNote(),1000,"审核说明"),now=Instant.now().toString();
  var next=new Proposal(old.id(),old.version()+1,old.name(),old.aliases(),old.sourceUrl(),old.sourceNote(),review.status(),note,old.createdAt(),now);var items=state.proposals().stream().map(p->p.id().equals(id)?next:p).toList();commit(state.feedback(),items,new Audit("proposal",id,review.status(),next.version(),now));return next;
 }
 public synchronized void archiveFeedback(String id,Version request){reload();if(request==null)throw invalid("缺少版本。");var old=state.feedback().stream().filter(f->f.id().equals(id)).findFirst().orElseThrow(OpsWorkStore::missing);check(old.version(),request.version());if(!old.status().equals("resolved"))throw invalid("仅已解决反馈可以归档。");commit(state.feedback().stream().filter(f->!f.id().equals(id)).toList(),state.proposals(),new Audit("feedback",id,"archive",old.version()+1,Instant.now().toString()));}
 public synchronized void archiveProposal(String id,Version request){reload();if(request==null)throw invalid("缺少版本。");var old=state.proposals().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(OpsWorkStore::missing);check(old.version(),request.version());if(old.status().equals("pending"))throw invalid("请先审核资料再归档。");commit(state.feedback(),state.proposals().stream().filter(p->!p.id().equals(id)).toList(),new Audit("proposal",id,"archive",old.version()+1,Instant.now().toString()));}
 private void commit(List<Feedback> feedback,List<Proposal> proposals,Audit event){
  var audit=new ArrayList<>(state.audit());audit.add(0,event);while(audit.size()>500)audit.remove(audit.size()-1);var next=new State(List.copyOf(feedback),List.copyOf(proposals),List.copyOf(audit));
  try{persistence.save("workflows",persistedRevision,mapper.writeValueAsString(next));}catch(IOException e){throw new Problem(503,"STORE_UNAVAILABLE","保存失败，原数据未改变。");}persistedRevision++;state=next;
 }
 private static void capacity(int size){if(size>=LIMIT)throw new Problem(503,"QUEUE_FULL","资料队列已满，请联系管理员归档后再提交。");}
 private static void check(long current,long provided){if(current!=provided)throw new Problem(409,"VERSION_CONFLICT","资料已更新，请刷新后再操作。");}
 private static String required(String s,int max,String field){String value=optional(s,max,field);if(value.isBlank())throw invalid("请填写"+field+"。");return value;}
 private static String optional(String s,int max,String field){if(s==null)return "";if(s.length()>max||s.chars().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))throw invalid(field+"过长或包含无效字符。");return s.trim();}
 private static String sourceUrl(String value){String s=required(value,1000,"来源网址");try{URI u=URI.create(s);if(u.getScheme()==null||!Set.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)throw new IllegalArgumentException();return s;}catch(IllegalArgumentException e){throw invalid("来源网址必须为不含凭据的HTTP或HTTPS网址。");}}
 private static Problem missing(){return new Problem(404,"NOT_FOUND","资料不存在。");}
 private static Problem invalid(String message){return new Problem(400,"INVALID_INPUT",message);}
}

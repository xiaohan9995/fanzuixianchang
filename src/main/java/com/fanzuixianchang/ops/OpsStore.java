package com.fanzuixianchang.ops;
import com.fanzuixianchang.core.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
@Component
public final class OpsStore {
 public record Entry(String id,String title,String prompt){}
 public record Release(long version,String at,List<Entry> entries){}
 public record Audit(String action,long version,String at){}
 public record State(long version,List<Entry> draft,List<Entry> published,List<Release> history,List<Audit> audit){}
 public record Change(long version,List<Entry> entries){}
 public record Revision(long version,long targetVersion){}
 private final ObjectMapper mapper;private final OpsPersistence persistence;private State state;private long persistedRevision=-1;
 public OpsStore(ObjectMapper mapper,String dir){this(mapper,new FileOpsPersistence(mapper,Path.of(dir)));}
 @Autowired public OpsStore(ObjectMapper mapper,OpsPersistence persistence){this.mapper=mapper;this.persistence=persistence;reload();}
 private void reload(){var snapshot=persistence.load("content");try{var loaded=snapshot==null?initial():mapper.readValue(snapshot.json(),State.class);validate(loaded.draft());validate(loaded.published());if(loaded.history()==null||loaded.audit()==null||loaded.history().size()>20||loaded.audit().size()>100||loaded.version()<0)throw new IOException("Invalid content store");state=loaded;persistedRevision=snapshot==null?-1:snapshot.revision();}catch(IOException|IllegalArgumentException e){throw new Problem(503,"STORE_UNAVAILABLE","运营内容读取失败，请稍后重试。");}}
 public static List<Entry> defaults(){return List.of(new Entry("meal_compare","鸡腿饭和牛肉面，怎么选？","鸡腿饭和牛肉面怎么选？我会补上菜单和今天的预算、忌口，想请饭探帮我比较。"),new Entry("milk_tea","奶茶想喝，怎么点更合适？","想喝奶茶，帮我比较杯型、糖度和配料的选择。我会补充菜单和实际规格。"),new Entry("takeaway_balance","外卖怎么搭，吃得更均衡？","这顿想点外卖，帮我看看主食、蔬菜和蛋白质食物怎么搭。我会补充菜单和口味要求。"),new Entry("budget_compare","同样预算，这两份选哪个？","有两份菜单拿不定，想在预算内吃得合适。我会补上菜单、价格和忌口，请帮我比较取舍。"));}
 private static State initial(){return new State(0,defaults(),defaults(),List.of(new Release(0,Instant.now().toString(),defaults())),List.of());}
 public synchronized State get(){reload();return state;}
 public synchronized State save(Change change){reload();check(change.version());var entries=validate(change.entries());return commit("save",entries,state.published(),false);}
 public synchronized State publish(long version){reload();check(version);return commit("publish",state.draft(),state.draft(),true);}
 public synchronized State rollback(Revision revision){reload();check(revision.version());var target=state.history().stream().filter(r->r.version()==revision.targetVersion()).findFirst().orElseThrow(()->new Problem(400,"INVALID_INPUT","找不到可回滚版本。"));return commit("rollback",target.entries(),target.entries(),true);}
 private void check(long version){if(state.version()!=version)throw new Problem(409,"VERSION_CONFLICT","内容已更新，请刷新后再操作。");}
 private State commit(String action,List<Entry> draft,List<Entry> published,boolean release){long version=state.version()+1;String now=Instant.now().toString();var history=new ArrayList<>(state.history());if(release)history.add(new Release(version,now,published));while(history.size()>20)history.remove(0);var audit=new ArrayList<>(state.audit());audit.add(new Audit(action,version,now));while(audit.size()>100)audit.remove(0);var next=new State(version,draft,published,List.copyOf(history),List.copyOf(audit));try{persistence.save("content",persistedRevision,mapper.writeValueAsString(next));}catch(IOException e){throw new Problem(503,"STORE_UNAVAILABLE","保存失败，原版本未改变。");}persistedRevision++;state=next;return state;}
 private static List<Entry> validate(List<Entry> entries){if(entries==null||entries.size()!=4)throw invalid();var ids=new HashSet<String>();var allowed=Set.of("meal_compare","milk_tea","takeaway_balance","budget_compare");for(var e:entries){if(e==null||e.id()==null||!allowed.contains(e.id())||!ids.add(e.id())||!text(e.title(),60)||!text(e.prompt(),600))throw invalid();}return List.copyOf(entries);}
 private static boolean text(String s,int limit){return s!=null&&!s.isBlank()&&s.length()<=limit&&s.chars().noneMatch(c->Character.isISOControl(c)||c=='<'||c=='>');}
 private static Problem invalid(){return new Problem(400,"INVALID_INPUT","需提供四个固定问题，标题1—60字、问题1—600字，使用纯文本。");}
}

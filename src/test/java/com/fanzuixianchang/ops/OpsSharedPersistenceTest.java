package com.fanzuixianchang.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanzuixianchang.core.Problem;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpsSharedPersistenceTest {
 private static final class Memory implements OpsPersistence {
  final Map<String,Snapshot> data=new HashMap<>();boolean fail=false,conflict=false;
  public synchronized Snapshot load(String key){return data.get(key);}
  public synchronized void save(String key,long expected,String json){if(fail)throw new Problem(503,"STORE_UNAVAILABLE","offline");var old=data.get(key);long actual=old==null?-1:old.revision();if(conflict||actual!=expected)throw new Problem(409,"VERSION_CONFLICT","conflict");data.put(key,new Snapshot(expected+1,json));}
 }
 @Test void contentInstancesReadLatestAndOldVersionCannotOverwrite(){var p=new Memory();var m=new ObjectMapper();var a=new OpsStore(m,p);var b=new OpsStore(m,p);var entries=new ArrayList<>(OpsStore.defaults());entries.set(0,new OpsStore.Entry("meal_compare","共享标题","共享问题"));a.save(new OpsStore.Change(0,entries));assertEquals("共享标题",b.get().draft().getFirst().title());assertEquals(409,assertThrows(Problem.class,()->b.publish(0)).status());b.publish(1);assertEquals(entries,new OpsStore(m,p).get().published());assertEquals(1,p.data.get("content").revision());}
 @Test void workflowsReadLatestAndFailedSavesDoNotCreatePhantomEntries(){var p=new Memory();var m=new ObjectMapper();var a=new OpsWorkStore(m,p);var b=new OpsWorkStore(m,p);var first=a.createFeedback(new OpsWorkStore.FeedbackInput("other","已有反馈",true));assertEquals(first.id(),b.feedback().getFirst().id());p.fail=true;assertEquals(503,assertThrows(Problem.class,()->b.createFeedback(new OpsWorkStore.FeedbackInput("other","失败反馈",true))).status());p.fail=false;assertEquals(1,a.feedback().size());assertEquals(1,b.feedback().size());assertEquals(1,b.audit().size());b.updateFeedback(first.id(),new OpsWorkStore.FeedbackChange(0,"resolved","已核对"));assertEquals("resolved",new OpsWorkStore(m,p).feedback().getFirst().status());}
 @Test void compareAndSetConflictDoesNotReplacePreviousSnapshot(){var p=new Memory();var m=new ObjectMapper();var store=new OpsStore(m,p);p.conflict=true;assertEquals(409,assertThrows(Problem.class,()->store.publish(0)).status());assertNull(p.data.get("content"));p.conflict=false;assertEquals(0,store.get().version());assertEquals(0,store.get().audit().size());}
 @Test void storageReadFailureNeverReturnsDefaultData(){var p=new OpsPersistence(){public Snapshot load(String key){throw new Problem(503,"STORE_UNAVAILABLE","offline");}public void save(String key,long expected,String json){throw new AssertionError();}};assertEquals(503,assertThrows(Problem.class,()->new OpsStore(new ObjectMapper(),p)).status());assertEquals(503,assertThrows(Problem.class,()->new OpsWorkStore(new ObjectMapper(),p)).status());}
}

package com.fanzuixianchang.ops;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanzuixianchang.core.Problem;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class OpsStoreTest {
 @TempDir Path dir;
 @Test void draftIsSeparateAndVersionsPersist(){var mapper=new ObjectMapper();var store=new OpsStore(mapper,dir.toString());var next=new ArrayList<>(OpsStore.defaults());next.set(0,new OpsStore.Entry("meal_compare","改后的标题","改后的问题"));store.save(new OpsStore.Change(0,next));assertNotEquals(store.get().draft(),store.get().published());assertThrows(Problem.class,()->store.publish(0));store.publish(1);assertEquals(next,store.get().published());var loaded=new OpsStore(mapper,dir.toString());assertEquals(2,loaded.get().version());loaded.rollback(new OpsStore.Revision(2,0));assertEquals(OpsStore.defaults(),loaded.get().published());assertEquals(3,loaded.get().audit().size());}
 @Test void invalidCannotOverwrite(){var store=new OpsStore(new ObjectMapper(),dir.toString());assertThrows(Problem.class,()->store.save(new OpsStore.Change(0,List.of())));assertEquals(0,store.get().version());var bad=new ArrayList<>(OpsStore.defaults());bad.set(0,new OpsStore.Entry("meal_compare","<script>","ok"));assertThrows(Problem.class,()->store.save(new OpsStore.Change(0,bad)));assertEquals(0,store.get().version());bad.set(0,new OpsStore.Entry(null,"合法标题","问题"));assertThrows(Problem.class,()->store.save(new OpsStore.Change(0,bad)));assertEquals(0,store.get().version());}
 @Test void adminIsSeparateAndDisabledByDefault(){assertThrows(Problem.class,()->new OpsAuth("").authenticate("Bearer user"));var auth=new OpsAuth("operations-test-token-at-least-32-characters");assertThrows(Problem.class,()->auth.authenticate("Bearer local-development-token-123456"));assertDoesNotThrow(()->auth.authenticate("Bearer operations-test-token-at-least-32-characters"));assertDoesNotThrow(()->OpsAuth.open().authenticate(null));}
 @Test void legacyContentFileIsReadAndConvertedWithoutResettingVersion()throws Exception{var mapper=new ObjectMapper();var original=new OpsStore.State(8,OpsStore.defaults(),OpsStore.defaults(),List.of(new OpsStore.Release(8,"2026-11-14",OpsStore.defaults())),List.of());Files.writeString(dir.resolve("content.json"),mapper.writeValueAsString(original));var migrated=new OpsStore(mapper,dir.toString());assertEquals(8,migrated.get().version());migrated.publish(8);assertEquals(9,new OpsStore(mapper,dir.toString()).get().version());assertTrue(Files.exists(dir.resolve("content-snapshot.json")));}
}

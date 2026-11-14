package com.fanzuixianchang.storage;
import com.fanzuixianchang.core.Problem;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FileStorageServiceTest {
 private static final byte[] PNG={(byte)137,80,78,71,13,10,26,10,0};
 private static FileStorageService.Upload upload(String mime,byte[] bytes,Boolean consent){return new FileStorageService.Upload(mime,Base64.getEncoder().encodeToString(bytes),consent);}
 private static final class Memory implements ObjectStore {
  final Map<String,Info> objects=new HashMap<>();String lastKey;Instant expires;boolean enabled=true,fail;
  public boolean enabled(){return enabled;}
  public void put(String key,byte[] bytes,String mime){if(fail)throw new IllegalStateException("secret-provider-message");lastKey=key;objects.put(key,new Info(mime,bytes.length));}
  public Info head(String key){lastKey=key;return objects.get(key);}
  public String signedGet(String key,Instant expires){this.expires=expires;return "https://private-bucket.example.com/"+key+"?signed=short-lived";}
  public void delete(String key){lastKey=key;objects.remove(key);}
 }
 @Test void explicitConsentAndValidatedImageAreRequiredBeforeWriting(){var memory=new Memory();var service=new FileStorageService(memory);assertThrows(Problem.class,()->service.upload("user",upload("image/png",PNG,false)));assertThrows(Problem.class,()->service.upload("user",new FileStorageService.Upload("image/png","not-base64!",true)));assertThrows(Problem.class,()->service.upload("user",upload("image/jpeg",PNG,true)));assertThrows(Problem.class,()->service.upload("user",upload("text/html",PNG,true)));assertThrows(Problem.class,()->service.upload("user",upload("image/png",new byte[FileStorageService.MAX_BYTES+1],true)));assertTrue(memory.objects.isEmpty());}
 @Test void fileOwnershipUsesDerivedPrefixAndAccessExpiresInFiveMinutes(){var memory=new Memory();var now=Instant.parse("2026-11-14T00:00:00Z");var service=new FileStorageService(memory,Clock.fixed(now,ZoneOffset.UTC));var stored=service.upload("private-user-subject",upload("image/png",PNG,true));assertEquals("image/png",stored.mimeType());assertEquals(PNG.length,stored.sizeBytes());assertNotNull(UUID.fromString(stored.id()));String ownKey=memory.lastKey;assertTrue(ownKey.matches("private/users/[a-f0-9]{64}/[a-f0-9-]{36}"));assertFalse(ownKey.contains("private-user-subject"));var access=service.access("private-user-subject",stored.id());assertEquals(now.plusSeconds(300),memory.expires);assertEquals(now.plusSeconds(300).toString(),access.expiresAt());assertEquals(stored.id(),access.id());assertThrows(Problem.class,()->service.access("different-user",stored.id()));assertNotEquals(ownKey,memory.lastKey);service.delete("different-user",stored.id());assertTrue(memory.objects.containsKey(ownKey));service.delete("private-user-subject",stored.id());service.delete("private-user-subject",stored.id());assertTrue(memory.objects.isEmpty());}
 @Test void callerCannotChooseObjectPathAndStorageFailuresAreSanitized(){var memory=new Memory();var service=new FileStorageService(memory);for(String id:List.of("../other-owner/file","https://external.example/file","1-1-1-1-1")){var e=assertThrows(Problem.class,()->service.access("user",id));assertEquals(400,e.status());}assertNull(memory.lastKey);memory.fail=true;var e=assertThrows(Problem.class,()->service.upload("user",upload("image/png",PNG,true)));assertEquals(503,e.status());assertFalse(e.getMessage().contains("secret-provider-message"));}
 @Test void disabledStorageDoesNotRequireCredentialsOrCallProvider(){var memory=new Memory();memory.enabled=false;var e=assertThrows(Problem.class,()->new FileStorageService(memory).upload("user",upload("image/png",PNG,true)));assertEquals("STORAGE_NOT_CONFIGURED",e.code());assertNull(memory.lastKey);var object=new ObjectStorageConfiguration().objectStore(false,"","","","","");assertFalse(object.enabled());assertThrows(IllegalArgumentException.class,()->new ObjectStorageConfiguration().objectStore(true,"bad","ap-shanghai","id","secret",""));}
}

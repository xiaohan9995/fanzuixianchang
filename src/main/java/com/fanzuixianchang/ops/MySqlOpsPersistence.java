package com.fanzuixianchang.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanzuixianchang.core.Problem;
import java.net.URI;
import java.sql.*;
import java.util.Properties;
import javax.sql.DataSource;

/** MySQL snapshot persistence. The schema is applied separately; requests never create tables. */
public final class MySqlOpsPersistence implements OpsPersistence {
 private final ObjectMapper mapper;
 private final Connections connections;
 @FunctionalInterface interface Connections { Connection open() throws SQLException; }
 public MySqlOpsPersistence(ObjectMapper mapper,DataSource source){this(mapper,source::getConnection);}
 MySqlOpsPersistence(ObjectMapper mapper,Connections connections){this.mapper=mapper;this.connections=connections;}
 public MySqlOpsPersistence(ObjectMapper mapper,String url,String user,String password){
  this.mapper=mapper;
  validateUrl(url);
  if(user==null||user.isBlank()||password==null||password.isBlank())throw new IllegalArgumentException("MySQL runtime credentials required");
  var properties=new Properties();properties.setProperty("user",user);properties.setProperty("password",password);
  properties.setProperty("connectTimeout","10000");properties.setProperty("socketTimeout","20000");properties.setProperty("allowLoadLocalInfile","false");properties.setProperty("allowUrlInLocalInfile","false");
  this.connections=()->DriverManager.getConnection(url,properties);
 }
 private static void validateUrl(String url){
  try{
   if(url==null||!url.startsWith("jdbc:mysql://"))throw new IllegalArgumentException();
   var u=URI.create(url.substring(5));
   if(u.getHost()==null||u.getUserInfo()!=null||u.getFragment()!=null||u.getPath()==null||!u.getPath().matches("/[A-Za-z0-9_]+"))throw new IllegalArgumentException();
   String query=u.getRawQuery();
   if(query!=null)for(String pair:query.split("&")){String key=java.net.URLDecoder.decode(pair.split("=",2)[0],java.nio.charset.StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);if(java.util.Set.of("user","password","passwd","connecttimeout","sockettimeout","allowloadlocalinfile","allowurlinlocalinfile").contains(key))throw new IllegalArgumentException();}
  }catch(RuntimeException e){throw new IllegalArgumentException("MySQL URL must name a database without embedded credentials or timeout/file overrides");}
 }
 private static String namespace(String key){if(!"content".equals(key)&&!"workflows".equals(key))throw new IllegalArgumentException("Unknown operations namespace");return key;}
 @Override public Snapshot load(String key){
  namespace(key);
  try(var connection=connections.open();var statement=connection.prepareStatement("SELECT revision,payload FROM fanzui_ops_state WHERE namespace=?")){
   statement.setQueryTimeout(20);statement.setString(1,key);
   try(var result=statement.executeQuery()){
    if(!result.next())return null;
    long revision=result.getLong(1);String json=result.getString(2);
    if(revision<0||json==null||!mapper.readTree(json).isObject())throw unavailable();
    return new Snapshot(revision,json);
   }
  }catch(Problem e){throw e;}catch(Exception e){throw unavailable();}
 }
 @Override public void save(String key,long expectedRevision,String json){
  namespace(key);if(expectedRevision< -1||expectedRevision==Long.MAX_VALUE)throw new IllegalArgumentException("Invalid expected revision");
  try{
   if(json==null||!mapper.readTree(json).isObject())throw unavailable();
   boolean creating=expectedRevision== -1;
   String sql=creating?"INSERT INTO fanzui_ops_state(namespace,revision,payload) VALUES(?,?,?)":"UPDATE fanzui_ops_state SET revision=?,payload=?,updated_at=CURRENT_TIMESTAMP(6) WHERE namespace=? AND revision=?";
   try(var connection=connections.open();var statement=connection.prepareStatement(sql)){
    statement.setQueryTimeout(20);
    if(creating){statement.setString(1,key);statement.setLong(2,0);statement.setString(3,json);}
    else{statement.setLong(1,expectedRevision+1);statement.setString(2,json);statement.setString(3,key);statement.setLong(4,expectedRevision);}
    if(statement.executeUpdate()!=1)throw conflict();
   }
  }catch(Problem e){throw e;}catch(SQLException e){if(e.getErrorCode()==1062||"23505".equals(e.getSQLState()))throw conflict();throw unavailable();}catch(Exception e){throw unavailable();}
 }
 private static Problem conflict(){return new Problem(409,"VERSION_CONFLICT","数据已更新，请刷新后重试。");}
 private static Problem unavailable(){return new Problem(503,"STORE_UNAVAILABLE","MySQL持久存储暂不可用，请稍后重试。");}
}

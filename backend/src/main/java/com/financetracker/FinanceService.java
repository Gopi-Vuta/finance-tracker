package com.financetracker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.springframework.http.HttpStatus.*;
import static com.financetracker.FinanceValidation.*;

@Service
public class FinanceService {
    final JdbcTemplate db;
    final FinanceValidation validation;
    final JsonMapper json=JsonMapper.builder().build();
    final SecureRandom random=new SecureRandom();
    FinanceService(JdbcTemplate db,FinanceValidation validation){this.db=db;this.validation=validation;}
    String encode(Object v){return json.writeValueAsString(v);}
    @SuppressWarnings("unchecked") Map<String,Object> decode(String v){return json.readValue(v,Map.class);}
    static Map<String,Object> emptyRecurring(){return Map.of("accounts",List.of(),"cards",List.of(),"fixed",List.of(),"investments",List.of());}
    static Map<String,Object> defaultReminders(){return Map.of("enabled",false,"onlyIfIncomplete",true,"stopWhenTallied",true,"includeMissing",true,"schedules",List.of());}

    @Transactional
    public String user(OidcUser principal){
        if(principal==null||!Boolean.TRUE.equals(principal.getEmailVerified()))throw new ResponseStatusException(UNAUTHORIZED,"A verified Google email is required.");
        String subject=principal.getSubject(),email=principal.getEmail().toLowerCase(Locale.ROOT);
        List<String> ids=db.query("select id from app_users where google_subject=?",(rs,n)->rs.getString(1),subject);
        if(!ids.isEmpty()){
            db.update("update app_users set email=? where id=?",email,ids.get(0));return ids.get(0);
        }
        String uid=UUID.randomUUID().toString(),name=principal.getFullName();if(name==null||name.isBlank())name=email.split("@")[0];
        db.update("insert into app_users(id,google_subject,email,name,recurring_json,reminders_json) values(?,?,?,?,?,?)",uid,subject,email,name.substring(0,Math.min(100,name.length())),encode(emptyRecurring()),encode(defaultReminders()));
        return uid;
    }
    Map<String,Object> userRow(String uid,boolean self){
        return db.queryForObject("select * from app_users where id=?",(rs,n)->{
            Map<String,Object> u=new LinkedHashMap<>();u.put("id",rs.getString("id"));u.put("name",rs.getString("name"));u.put("email",rs.getString("email"));u.put("timezone",rs.getString("timezone"));u.put("preferences",decode(rs.getString("preferences_json")));u.put("budget",decode(rs.getString("budget_json")));u.put("recurring",decode(rs.getString("recurring_json")));u.put("reminders",self?decode(rs.getString("reminders_json")):defaultReminders());return u;
        },uid);
    }
    Map<String,Object> months(String uid){Map<String,Object> result=new TreeMap<>();db.query("select month_key,document_json from monthly_records where user_id=? order by month_key",rs->{result.put(rs.getString(1),decode(rs.getString(2)));},uid);return result;}
    boolean hasFinancialData(Map<String,Object> month){
        Object income=month.get("income");
        if(income instanceof Number number&&number.doubleValue()!=0)return true;
        for(String group:List.of("accounts","cards","fixed","investments","oneoffs","remarks","receipts","assets")){
            if(month.get(group) instanceof Collection<?> entries&&!entries.isEmpty())return true;
        }
        return false;
    }
    String familyId(String uid){List<String> rows=db.query("select family_id from family_members where user_id=?",(rs,n)->rs.getString(1),uid);return rows.isEmpty()?null:rows.get(0);}
    void lockUser(String uid){db.queryForObject("select id from app_users where id=? for update",String.class,uid);}
    @Transactional(readOnly=true)
    public Map<String,Object> state(String uid){
        String fid=familyId(uid);List<String> members=fid==null?List.of(uid):db.query("select user_id from family_members where family_id=? order by joined_at,user_id",(rs,n)->rs.getString(1),fid);
        List<Object> users=new ArrayList<>();Map<String,Object> allMonths=new LinkedHashMap<>();for(String member:members){users.add(userRow(member,member.equals(uid)));allMonths.put(member,months(member));}
        List<Object> families=new ArrayList<>();if(fid!=null){String name=db.queryForObject("select name from families where id=?",String.class,fid);families.add(Map.of("id",fid,"name",name,"memberIds",members));}
        long revision=db.queryForObject("select revision from app_users where id=?",Long.class,uid);
        return Map.of("version",2,"activeUserId",uid,"revision",revision,"users",users,"families",families,"months",allMonths);
    }
    @Transactional
    public Map<String,Object> save(String uid,Map<String,Object> request){
        lockUser(uid);
        long revision=db.queryForObject("select revision from app_users where id=?",Long.class,uid);
        if(!(request.get("revision") instanceof Number n)||n.longValue()!=revision)throw new ResponseStatusException(CONFLICT,"Data changed in another session. Export your unsaved draft, then reload before editing.");
        Map<String,Object> user=object(request.get("user"));if(!uid.equals(user.get("id")))throw new ResponseStatusException(FORBIDDEN,"You can only edit your own finances.");
        Map<String,Object> documents=object(request.get("months"));validation.user(user);validation.months(documents);
        db.update("update app_users set name=?,timezone=?,recurring_json=?,reminders_json=?,budget_json=?,preferences_json=?,revision=revision+1 where id=?",text(user.get("name"),100),text(user.get("timezone"),100),encode(user.get("recurring")),encode(user.get("reminders")),encode(user.getOrDefault("budget",Map.of())),encode(user.getOrDefault("preferences",userRow(uid,true).get("preferences"))),uid);
        // One owner's complete document set is replaced atomically under an optimistic revision + row lock.
        db.update("delete from monthly_records where user_id=?",uid);
        documents.forEach((key,value)->db.update("insert into monthly_records(user_id,month_key,document_json) values(?,?,?)",uid,key,encode(value)));
        return state(uid);
    }
    @Transactional
    public Map<String,Object> importLegacy(String uid, Map<String,Object> request){
        lockUser(uid);
        if (!(request.get("confirmed") instanceof Boolean confirmed) || !confirmed) {
            throw new ResponseStatusException(BAD_REQUEST,"Confirm the import before saving financial data.");
        }
        List<Map<String,Object>> existing=db.query("select document_json from monthly_records where user_id=?",(rs,row)->decode(rs.getString(1)),uid);
        if (existing.stream().anyMatch(this::hasFinancialData)) {
            throw new ResponseStatusException(CONFLICT,"Import is available only for an empty account so existing records cannot be overwritten.");
        }
        // Opening the dashboard creates an empty month document. It contains no financial data and must not prevent a first import.
        if(!existing.isEmpty())db.update("delete from monthly_records where user_id=?",uid);
        Map<String,Object> source=object(request.get("source"));
        if (!(source.get("version") instanceof Number version) || version.intValue()!=2) {
            throw new ResponseStatusException(BAD_REQUEST,"Use a PennyFolio or legacy FinanceTracker v2 JSON backup.");
        }
        String profileName=text(request.get("profileName"),100);
        Map<String,Object> legacyUser=list(source.get("users")).stream()
            .map(FinanceValidation::object)
            .filter(user -> profileName.equalsIgnoreCase(String.valueOf(user.get("name"))))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST,"No profile named '"+profileName+"' exists in this backup."));
        String legacyId=text(legacyUser.get("id"),100);
        Map<String,Object> allMonths=object(source.get("months"));
        Map<String,Object> importedMonths=object(allMonths.get(legacyId));
        Map<String,Object> target=new LinkedHashMap<>(userRow(uid,true));
        target.put("recurring",object(legacyUser.get("recurring")));
        if (legacyUser.get("timezone") instanceof String timezone) target.put("timezone",timezone);
        // The account owner keeps their Google identity, display name, email and reminder preferences.
        validation.user(target);
        validation.months(importedMonths);
        db.update("update app_users set timezone=?,recurring_json=?,revision=revision+1 where id=?",
            text(target.get("timezone"),100),encode(target.get("recurring")),uid);
        importedMonths.forEach((key,value)->db.update("insert into monthly_records(user_id,month_key,document_json) values(?,?,?)",uid,key,encode(value)));
        return state(uid);
    }
    @Transactional
    public Map<String,Object> createFamily(String uid,String name){
        lockUser(uid);if(familyId(uid)!=null)throw new ResponseStatusException(CONFLICT,"Leave your current family first.");
        String fid=UUID.randomUUID().toString();db.update("insert into families(id,name,created_by) values(?,?,?)",fid,text(name,100),uid);db.update("insert into family_members(user_id,family_id) values(?,?)",uid,fid);return state(uid);
    }
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    @Transactional
    public Map<String,Object> invite(String uid,String email){
        lockUser(uid);String fid=familyId(uid);if(fid==null)throw new ResponseStatusException(CONFLICT,"Create or join a family first.");
        email=text(email,320).toLowerCase(Locale.ROOT);if(!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))fail("Enter a valid email address.");
        byte[] bytes=new byte[32];random.nextBytes(bytes);String code=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);Instant expires=Instant.now().plus(Duration.ofDays(7));
        db.update("insert into family_invites(token_hash,family_id,invited_email,expires_at,created_by) values(?,?,?,?,?)",hash(code),fid,email,Timestamp.from(expires),uid);
        return Map.of("code",code,"email",email,"expiresAt",expires.toString());
    }
    @Transactional
    public Map<String,Object> join(String uid,String code){
        lockUser(uid);if(familyId(uid)!=null)throw new ResponseStatusException(CONFLICT,"Leave your current family first.");
        String token=hash(text(code,100));List<Map<String,Object>> rows=db.query("select family_id,invited_email,expires_at,used_at from family_invites where token_hash=? for update",(rs,n)->{Map<String,Object> row=new HashMap<>();row.put("family",rs.getString(1));row.put("email",rs.getString(2));row.put("expires",rs.getTimestamp(3).toInstant());row.put("used",rs.getTimestamp(4));return row;},token);
        String email=db.queryForObject("select email from app_users where id=?",String.class,uid);
        if(rows.isEmpty()||rows.get(0).get("used")!=null||((Instant)rows.get(0).get("expires")).isBefore(Instant.now())||!email.equalsIgnoreCase((String)rows.get(0).get("email")))throw new ResponseStatusException(BAD_REQUEST,"Invitation is invalid, expired, already used, or belongs to another email.");
        db.update("insert into family_members(user_id,family_id) values(?,?)",uid,rows.get(0).get("family"));db.update("update family_invites set used_at=current_timestamp where token_hash=?",token);return state(uid);
    }
    @Transactional
    public Map<String,Object> leave(String uid){
        lockUser(uid);String fid=familyId(uid);if(fid!=null){db.update("delete from family_members where user_id=?",uid);db.update("delete from family_invites where created_by=? and family_id=?",uid,fid);if(db.queryForObject("select count(*) from family_members where family_id=?",Integer.class,fid)==0)db.update("delete from families where id=?",fid);}return state(uid);
    }
}

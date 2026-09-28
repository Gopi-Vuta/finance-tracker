package com.financetracker;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.financetracker.FinanceValidation.*;

@SpringBootTest
@AutoConfigureMockMvc
class FinanceApiTests {
    @Autowired MockMvc mvc;
    @Autowired FinanceService service;
    @Autowired JdbcTemplate db;
    @BeforeEach void clean(){for(String table:List.of("reminder_deliveries","family_invites","family_members","families","monthly_records","app_users"))db.update("delete from "+table);}
    RequestPostProcessor google(String name){return oidcLogin().idToken(t->t.subject(name).claim("email",name+"@example.com").claim("email_verified",true).claim("name",name));}
    Map<String,Object> state(String name)throws Exception{return service.decode(mvc.perform(get("/api/state").with(google(name))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    Map<String,Object> postAs(String name,String path,Object body)throws Exception{return service.decode(mvc.perform(post(path).with(google(name)).with(csrf()).contentType("application/json").content(service.encode(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    Map<String,Object> payload(Map<String,Object> s){String uid=(String)s.get("activeUserId");return new HashMap<>(Map.of("revision",s.get("revision"),"user",object(list(s.get("users")).stream().map(FinanceValidation::object).filter(u->u.get("id").equals(uid)).findFirst().orElseThrow()),"months",object(s.get("months")).get(uid)));}
    Map<String,Object> save(String name,Map<String,Object> p)throws Exception{return service.decode(mvc.perform(put("/api/finance").with(google(name)).with(csrf()).contentType("application/json").content(service.encode(p))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    Map<String,Object> month(){Map<String,Object> m=new LinkedHashMap<>();m.put("income",1000);m.put("step",0);m.put("completed",List.of());for(String k:List.of("accounts","cards","fixed","investments","oneoffs","remarks"))m.put(k,new ArrayList<>());return m;}

    @Test void requiresAuthenticationAndCsrf()throws Exception{
        mvc.perform(get("/api/state")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/families").with(google("a")).contentType("application/json").content("{\"name\":\"Family\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
    }
    @Test void browserCsrfTokenWorksWithSessionCookie()throws Exception{
        var result=mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        var token=service.decode(result.getResponse().getContentAsString());
        var session=(org.springframework.mock.web.MockHttpSession)result.getRequest().getSession();
        mvc.perform(post("/api/families").session(session).with(google("browser"))
            .header((String)token.get("headerName"),(String)token.get("token"))
            .contentType("application/json").content("{\"name\":\"Browser family\"}"))
            .andExpect(status().isOk());
    }
    @Test void rejectsUnverifiedGoogleEmail()throws Exception{mvc.perform(get("/api/state").with(oidcLogin().idToken(t->t.subject("bad").claim("email","bad@example.com").claim("email_verified",false)))).andExpect(status().isUnauthorized());}
    @Test void createsSeparateGoogleUsersWithoutDemoRecords()throws Exception{
        var a=state("candy");var b=state("popcorn");assertNotEquals(a.get("activeUserId"),b.get("activeUserId"));assertEquals(1,list(a.get("users")).size());assertTrue(object(object(a.get("months")).get(a.get("activeUserId"))).isEmpty());
        assertEquals(a.get("activeUserId"),state("candy").get("activeUserId"));
    }
    @Test void savesOwnRecordsAndRejectsStaleRevision()throws Exception{
        var a=state("a");var p=payload(a);object(p.get("months")).put("2030-02",month());var saved=save("a",p);assertEquals(1,((Number)saved.get("revision")).intValue());
        assertEquals(1000,object(object(object(state("a").get("months")).get(a.get("activeUserId"))).get("2030-02")).get("income"));
        mvc.perform(put("/api/finance").with(google("a")).with(csrf()).contentType("application/json").content(service.encode(p))).andExpect(status().isConflict());
    }
    @Test void budgetPersistsAndInvalidTargetsAreRejected()throws Exception{
        var p=payload(state("budget-user"));
        var budget=new LinkedHashMap<String,Object>(Map.of("preset","Balanced","targets",Map.of("Needs",50,"Wants",30,"Savings",20),"mapping",Map.of("fixed","Needs","cards","Wants","oneoffs","Wants","investments","Savings")));
        object(p.get("user")).put("budget",budget);save("budget-user",p);
        var loaded=payload(state("budget-user"));assertEquals(budget,object(loaded.get("user")).get("budget"));
        budget.put("targets",Map.of("Needs",80,"Wants",30,"Savings",20));object(loaded.get("user")).put("budget",budget);
        mvc.perform(put("/api/finance").with(google("budget-user")).with(csrf()).contentType("application/json").content(service.encode(loaded))).andExpect(status().isBadRequest());
        assertEquals(50,object(object(object(payload(state("budget-user")).get("user")).get("budget")).get("targets")).get("Needs"));
    }
    @Test void rejectsEditingAnotherUser()throws Exception{
        var p=payload(state("a"));object(p.get("user")).put("id",state("b").get("activeUserId"));mvc.perform(put("/api/finance").with(google("a")).with(csrf()).contentType("application/json").content(service.encode(p))).andExpect(status().isForbidden());
    }
    @Test void importsOnlyPopCornIntoAnEmptySignedInAccount()throws Exception{
        var target=state("target");
        Map<String,Object> popcorn=new LinkedHashMap<>();
        popcorn.put("id","legacy-popcorn");popcorn.put("name","PopCorn");popcorn.put("email","popcorn@example.com");popcorn.put("timezone","Asia/Kolkata");
        popcorn.put("recurring",Map.of("accounts",List.of(),"cards",List.of(),"fixed",List.of(),"investments",List.of()));popcorn.put("reminders",Map.of("enabled",true,"onlyIfIncomplete",true,"stopWhenTallied",true,"includeMissing",true,"schedules",List.of()));
        Map<String,Object> legacy=Map.of("version",2,"users",List.of(popcorn),"months",Map.of("legacy-popcorn",Map.of("2030-02",month())));
        var imported=postAs("target","/api/import/legacy",Map.of("profileName","PopCorn","source",legacy,"confirmed",true));
        String targetId=(String)target.get("activeUserId");Map<String,Object> importedMonths=object(object(imported.get("months")).get(targetId));assertEquals(1000,object(importedMonths.get("2030-02")).get("income"));
        assertEquals("target@example.com",object(list(imported.get("users")).get(0)).get("email"));
        mvc.perform(post("/api/import/legacy").with(google("target")).with(csrf()).contentType("application/json").content(service.encode(Map.of("profileName","PopCorn","source",legacy,"confirmed",true)))).andExpect(status().isConflict());
    }
    @Test void rejectsInvalidMoneyAndDatesWithoutPartialSave()throws Exception{
        var p=payload(state("a"));var m=month();m.put("income",-4);object(p.get("months")).put("2030-02",m);
        mvc.perform(put("/api/finance").with(google("a")).with(csrf()).contentType("application/json").content(service.encode(p))).andExpect(status().isBadRequest());
        m.put("income",100);m.put("oneoffs",List.of(Map.of("id","x","name","Wrong month","amount",2,"date","2030-03-01")));
        mvc.perform(put("/api/finance").with(google("a")).with(csrf()).contentType("application/json").content(service.encode(p))).andExpect(status().isBadRequest());
        assertEquals(0,((Number)state("a").get("revision")).intValue());
    }
    @Test void supportsThreeMembersAndRevokesVisibilityOnLeave()throws Exception{
        state("a");state("b");state("c");postAs("a","/api/families",Map.of("name","Family"));
        for(String member:List.of("b","c")){var invite=postAs("a","/api/families/invites",Map.of("email",member+"@example.com"));postAs(member,"/api/families/join",Map.of("code",invite.get("code")));}
        assertEquals(3,list(state("b").get("users")).size());assertEquals(3,list(object(list(state("b").get("families")).get(0)).get("memberIds")).size());
        postAs("b","/api/families/leave",Map.of());assertEquals(1,list(state("b").get("users")).size());assertEquals(2,list(state("a").get("users")).size());
    }
    @Test void invitationChecksEmailExpiryAndSingleUse()throws Exception{
        state("a");state("b");state("other");postAs("a","/api/families",Map.of("name","Family"));var invite=postAs("a","/api/families/invites",Map.of("email","b@example.com"));var body=Map.of("code",invite.get("code"));
        mvc.perform(post("/api/families/join").with(google("other")).with(csrf()).contentType("application/json").content(service.encode(body))).andExpect(status().isBadRequest());
        postAs("b","/api/families/join",body);postAs("b","/api/families/leave",Map.of());
        mvc.perform(post("/api/families/join").with(google("b")).with(csrf()).contentType("application/json").content(service.encode(body))).andExpect(status().isBadRequest());
        var expired=postAs("a","/api/families/invites",Map.of("email","b@example.com"));db.update("update family_invites set expires_at=? where token_hash=?",Timestamp.from(Instant.now().minusSeconds(1)),FinanceService.hash((String)expired.get("code")));
        mvc.perform(post("/api/families/join").with(google("b")).with(csrf()).contentType("application/json").content(service.encode(Map.of("code",expired.get("code"))))).andExpect(status().isBadRequest());
    }
    @Test void reminderClampsLeapMonthAndDeduplicates()throws Exception{
        var p=payload(state("a"));var u=object(p.get("user"));u.put("timezone","UTC");u.put("reminders",Map.of("enabled",true,"onlyIfIncomplete",true,"stopWhenTallied",true,"includeMissing",true,"schedules",List.of(Map.of("id","monthly","day",31,"time","10:00","purpose","Start monthly check-in","enabled",true))));save("a",p);
        Recorder mail=new Recorder();ReminderScheduler scheduler=new ReminderScheduler(service,db,mail,"finance@localhost","http://localhost:8080");
        scheduler.tick(Instant.parse("2032-02-29T09:59:00Z"));assertEquals(0,mail.messages.size());scheduler.tick(Instant.parse("2032-02-29T10:00:00Z"));scheduler.tick(Instant.parse("2032-02-29T10:01:00Z"));assertEquals(1,mail.messages.size());assertEquals("a@example.com",mail.messages.get(0).getTo()[0]);assertTrue(mail.messages.get(0).getText().contains("Income"));
    }
    @Test void reminderStopsWhenTallied()throws Exception{
        var p=payload(state("a"));var u=object(p.get("user"));u.put("timezone","UTC");u.put("reminders",Map.of("enabled",true,"onlyIfIncomplete",false,"stopWhenTallied",true,"includeMissing",false,"schedules",List.of(Map.of("id","monthly","day",1,"time","10:00","purpose","Final reconciliation","enabled",true))));
        var m=month();m.put("accounts",List.of(Map.of("id","account","name","Bank","opening",0,"closing",1000)));m.put("completed",List.of(7));object(p.get("months")).put("2032-03",m);save("a",p);
        Recorder mail=new Recorder();new ReminderScheduler(service,db,mail,"finance@localhost","http://localhost:8080").tick(Instant.parse("2032-03-02T10:00:00Z"));assertTrue(mail.messages.isEmpty());
    }
    static class Recorder extends JavaMailSenderImpl {final List<SimpleMailMessage> messages=new ArrayList<>();@Override public void send(SimpleMailMessage message){messages.add(message);}}
}

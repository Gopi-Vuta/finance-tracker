package com.financetracker;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import static com.financetracker.FinanceValidation.*;

@Configuration
@EnableScheduling
class SchedulingConfig {}

@Component
@ConditionalOnProperty(name="app.reminders.enabled",havingValue="true")
class ReminderScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(ReminderScheduler.class);
    final FinanceService finance;
    final JdbcTemplate db;
    final JavaMailSender mail;
    final String from,baseUrl;
    ReminderScheduler(FinanceService finance,JdbcTemplate db,JavaMailSender mail,
                      @Value("${app.mail.from}") String from,@Value("${app.base-url}") String baseUrl){this.finance=finance;this.db=db;this.mail=mail;this.from=from;this.baseUrl=baseUrl;}
    @Scheduled(fixedDelayString="${app.reminders.poll-ms:60000}")
    void scheduled(){tick(Instant.now());}
    void tick(Instant now){
        List<String> ids=db.query("select id from app_users",(rs,n)->rs.getString(1));
        for(String uid:ids){try{check(uid,now);}catch(Exception e){LOG.warn("Reminder evaluation failed; it will be retried on the next scan. Cause: {}",e.getClass().getSimpleName());}}
    }
    void check(String uid,Instant now){
        Map<String,Object> user=finance.userRow(uid,true),prefs=object(user.get("reminders"));
        if(!Boolean.TRUE.equals(prefs.get("enabled")))return;
        ZonedDateTime local=now.atZone(ZoneId.of((String)user.get("timezone")));YearMonth month=YearMonth.from(local);
        Object raw=finance.months(uid).get(month.toString());Map<String,Object> record=raw==null?null:object(raw);
        boolean complete=record!=null&&list(record.get("completed")).stream().map(v->integer(v,0,7)).toList().containsAll(List.of(0,1,2,3,4,5,7));
        if(Boolean.TRUE.equals(prefs.get("onlyIfIncomplete"))&&complete)return;
        if(Boolean.TRUE.equals(prefs.get("stopWhenTallied"))&&record!=null&&tallied(record))return;
        for(Object schedule:list(prefs.get("schedules"))){
            Map<String,Object> s=object(schedule);if(!Boolean.TRUE.equals(s.get("enabled")))continue;
            int day=Math.min(integer(s.get("day"),1,31),month.lengthOfMonth());
            ZonedDateTime due=month.atDay(day).atTime(LocalTime.parse((String)s.get("time"))).atZone(local.getZone());
            if(local.isBefore(due))continue;
            String sid=(String)s.get("id");
            try{db.update("insert into reminder_deliveries(user_id,schedule_id,month_key,status,next_attempt) values(?,?,?,'PENDING',?)",uid,sid,month.toString(),Timestamp.from(now));}catch(DuplicateKeyException ignored){}
            int claimed=db.update("update reminder_deliveries set status='SENDING',attempts=attempts+1,next_attempt=? where user_id=? and schedule_id=? and month_key=? and attempts<5 and status<>'SENT' and next_attempt<=?",Timestamp.from(now.plusSeconds(900)),uid,sid,month.toString(),Timestamp.from(now));
            if(claimed==0)continue;
            try{
                SimpleMailMessage message=new SimpleMailMessage();message.setFrom(from);message.setTo((String)user.get("email"));message.setSubject("PennyFolio: "+month+" monthly check-in");
                String body="Hello "+user.get("name")+",\n\n"+s.get("purpose")+" for "+month+".\n";
                if(Boolean.TRUE.equals(prefs.get("includeMissing"))){List<String> labels=List.of("Income","Accounts","Credit cards","Fixed expenses","Investments","One-off expenses","Remarks","Reconciliation");Set<Integer> completed=new HashSet<>();if(record!=null)for(Object step:list(record.get("completed")))completed.add(integer(step,0,7));List<String> missing=new ArrayList<>();for(int i=0;i<8;i++)if(i!=6&&!completed.contains(i))missing.add(labels.get(i));body+="Sections to review: "+(missing.isEmpty()?"All reviewed; check any remaining balance difference.":String.join(", ",missing))+"\n";}
                message.setText(body+"\nOpen your tracker: "+baseUrl+"\n\nManage reminder settings in PennyFolio.");mail.send(message);
                db.update("update reminder_deliveries set status='SENT',sent_at=? where user_id=? and schedule_id=? and month_key=?",Timestamp.from(now),uid,sid,month.toString());
            }catch(Exception e){db.update("update reminder_deliveries set status='RETRY' where user_id=? and schedule_id=? and month_key=?",uid,sid,month.toString());LOG.warn("Reminder delivery failed and will retry; no finance content logged. Cause: {}",e.getClass().getSimpleName());}
        }
    }
}

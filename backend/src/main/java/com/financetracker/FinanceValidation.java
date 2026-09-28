package com.financetracker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Component
class FinanceValidation {
    static final List<String> GROUPS = List.of("accounts","cards","fixed","investments");
    static final BigDecimal MAX_MONEY = new BigDecimal("999999999999.99");
    static void fail(String message) { throw new ResponseStatusException(BAD_REQUEST, message); }
    @SuppressWarnings("unchecked")
    static Map<String,Object> object(Object value) {
        if (!(value instanceof Map<?,?>)) fail("Expected an object.");
        return (Map<String,Object>)value;
    }
    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        if (!(value instanceof List<?>)) fail("Expected a list.");
        List<Object> result=(List<Object>)value;
        if(result.size()>500) fail("Maximum 500 records per section.");
        return result;
    }
    static String text(Object value, int max) {
        if (!(value instanceof String s) || s.isBlank() || s.length()>max) fail("A required text field is missing or too long.");
        return ((String)value).trim();
    }
    static BigDecimal money(Object value, boolean negative) {
        try {
            BigDecimal n=new BigDecimal(value.toString());
            if(n.scale()>2 || n.abs().compareTo(MAX_MONEY)>0 || (!negative&&n.signum()<0)) fail("Amounts must have at most two decimals and be within range.");
            return n;
        } catch (NullPointerException|NumberFormatException e) {fail("Enter a valid amount.");return BigDecimal.ZERO;}
    }
    static int integer(Object value,int min,int max) {
        try {int n=new BigDecimal(value.toString()).intValueExact();if(n<min||n>max)fail("Number outside allowed range.");return n;}
        catch(Exception e){fail("Enter a valid whole number.");return 0;}
    }
    static YearMonth month(String key) {
        try {if(!key.matches("[0-9]{4}-(0[1-9]|1[0-2])"))throw new IllegalArgumentException();YearMonth m=YearMonth.parse(key);if(m.getYear()<1900)throw new IllegalArgumentException();return m;}
        catch(Exception e){fail("Use a month between 1900-01 and 9999-12.");return null;}
    }
    static void bool(Object v){if(!(v instanceof Boolean))fail("Expected a boolean.");}
    static void note(Map<String,Object> row){if(row.get("note")!=null && (!(row.get("note") instanceof String s)||s.length()>2000))fail("Notes may contain up to 2000 characters.");}
    static void unique(List<Object> rows){Set<String> ids=new HashSet<>();for(Object raw:rows)if(!ids.add(text(object(raw).get("id"),100)))fail("Duplicate record ID.");}
    void user(Map<String,Object> user) {
        if(user.get("budget")!=null&&!object(user.get("budget")).isEmpty()){
            Map<String,Object> budget=object(user.get("budget")),targets=object(budget.get("targets")),mapping=object(budget.get("mapping"));
            if(!List.of("Balanced","Debt payoff","Aggressive saver","Custom").contains(budget.get("preset")))fail("Invalid budget preset.");
            int sum=0;for(String key:List.of("Needs","Wants","Savings"))sum+=integer(targets.get(key),0,100);
            if(sum!=100)fail("Budget targets must total 100%.");
            for(String group:List.of("fixed","cards","oneoffs","investments"))if(!List.of("Needs","Wants","Savings").contains(mapping.get(group)))fail("Invalid budget category.");
        }
        text(user.get("name"),100);
        try {ZoneId.of(text(user.get("timezone"),100));}catch(Exception e){fail("Invalid timezone.");}
        Map<String,Object> recurring=object(user.get("recurring"));
        for(String g:GROUPS){List<Object> rows=list(recurring.get(g));unique(rows);for(Object raw:rows){Map<String,Object> r=object(raw);text(r.get("name"),100);YearMonth start=month(text(r.get("start"),7));if(r.get("end")!=null&&!r.get("end").equals("")){if(month(text(r.get("end"),7)).isBefore(start))fail("Recurring end precedes start.");}if(!g.equals("accounts"))money(r.get("amount"),false);}}
        Map<String,Object> reminders=object(user.get("reminders"));
        for(String key:List.of("enabled","onlyIfIncomplete","stopWhenTallied","includeMissing"))bool(reminders.get(key));
        List<Object> schedules=list(reminders.get("schedules"));if(schedules.size()>20)fail("Maximum 20 reminder schedules.");unique(schedules);
        for(Object raw:schedules){Map<String,Object> r=object(raw);integer(r.get("day"),1,31);if(!text(r.get("time"),5).matches("([01][0-9]|2[0-3]):[0-5][0-9]"))fail("Invalid reminder time.");if(!List.of("Start monthly check-in","Follow-up if incomplete","Final reconciliation").contains(r.get("purpose")))fail("Invalid reminder purpose.");bool(r.get("enabled"));}
    }
    void months(Map<String,Object> months){
        if(months.size()>1200)fail("Maximum 1200 stored months.");
        for(var entry:months.entrySet()){
            YearMonth key=month(entry.getKey());Map<String,Object> m=object(entry.getValue());money(m.get("income"),false);integer(m.get("step"),0,7);
            List<Object> done=list(m.get("completed"));Set<Integer> checked=new HashSet<>();for(Object step:done)if(!checked.add(integer(step,0,7)))fail("Duplicate completed step.");
            for(String g:List.of("accounts","cards","fixed","investments","oneoffs","remarks")){
                List<Object> rows=list(m.get(g));unique(rows);
                for(Object raw:rows){Map<String,Object> r=object(raw);if(g.equals("remarks")){text(r.get("text"),2000);continue;}text(r.get("name"),100);note(r);
                    if(g.equals("accounts")){money(r.get("opening"),true);if(r.get("closing")!=null)money(r.get("closing"),true);}
                    else money(r.get("amount"),false);
                    if(g.equals("oneoffs")){try{LocalDate date=LocalDate.parse(text(r.get("date"),10));if(!YearMonth.from(date).equals(key))fail("Expense date must belong to its month.");}catch(java.time.DateTimeException e){fail("Invalid expense date.");}}
                }
            }
            if(checked.contains(7)&&actual(m)==null)fail("Closing balances are required to review reconciliation.");
        }
    }
    static BigDecimal total(Map<String,Object> m,String group,String field){BigDecimal n=BigDecimal.ZERO;for(Object raw:list(m.get(group))){Object v=object(raw).get(field);if(v!=null)n=n.add(money(v,true));}return n;}
    static BigDecimal expected(Map<String,Object> m){return money(m.get("income"),false).add(total(m,"accounts","opening")).subtract(total(m,"cards","amount")).subtract(total(m,"fixed","amount")).subtract(total(m,"investments","amount")).subtract(total(m,"oneoffs","amount"));}
    static BigDecimal actual(Map<String,Object> m){List<Object> accounts=list(m.get("accounts"));if(accounts.isEmpty()||accounts.stream().anyMatch(a->object(a).get("closing")==null))return null;return total(m,"accounts","closing");}
    static boolean tallied(Map<String,Object> m){return actual(m)!=null&&actual(m).compareTo(expected(m))==0&&list(m.get("completed")).stream().anyMatch(v->integer(v,0,7)==7);}
}

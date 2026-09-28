package com.financetracker;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api")
class FinanceController {
    final FinanceService finance;
    @Value("${app.google-configured:}") String google;
    FinanceController(FinanceService finance){this.finance=finance;}
    @GetMapping("/config") Map<String,Object> config(){return Map.of("googleConfigured",!google.isBlank()&&!google.startsWith("configure-")&&!google.startsWith("replace-"));}
    @GetMapping("/csrf") Map<String,String> csrf(CsrfToken token){return Map.of("token",token.getToken(),"headerName",token.getHeaderName());}
    @GetMapping("/state") Map<String,Object> state(@AuthenticationPrincipal OidcUser p){return finance.state(finance.user(p));}
    @PutMapping("/finance") Map<String,Object> save(@AuthenticationPrincipal OidcUser p,@RequestBody Map<String,Object> body){return finance.save(finance.user(p),body);}
    @PostMapping("/import/legacy") Map<String,Object> importLegacy(@AuthenticationPrincipal OidcUser p,@RequestBody Map<String,Object> body){return finance.importLegacy(finance.user(p),body);}
    @PostMapping("/families") Map<String,Object> create(@AuthenticationPrincipal OidcUser p,@RequestBody Map<String,Object> body){return finance.createFamily(finance.user(p),FinanceValidation.text(body.get("name"),100));}
    @PostMapping("/families/invites") Map<String,Object> invite(@AuthenticationPrincipal OidcUser p,@RequestBody Map<String,Object> body){return finance.invite(finance.user(p),FinanceValidation.text(body.get("email"),320));}
    @PostMapping("/families/join") Map<String,Object> join(@AuthenticationPrincipal OidcUser p,@RequestBody Map<String,Object> body){return finance.join(finance.user(p),FinanceValidation.text(body.get("code"),100));}
    @PostMapping("/families/leave") Map<String,Object> leave(@AuthenticationPrincipal OidcUser p){return finance.leave(finance.user(p));}
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,Object>> status(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"Request could not be completed.":e.getReason()));}
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ResponseEntity<Map<String,Object>> conflict(Exception e){return ResponseEntity.status(409).body(Map.of("message","This record changed. Refresh and try again."));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<Map<String,Object>> malformed(Exception e){return ResponseEntity.badRequest().body(Map.of("message","Invalid request data."));}
}

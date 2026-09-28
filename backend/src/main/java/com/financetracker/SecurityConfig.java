package com.financetracker;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        // Session-bound CSRF token is fetched through /api/csrf, never stored in localStorage.
        http.authorizeHttpRequests(a -> a
                .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/api/config", "/api/csrf", "/error", "/oauth2/**", "/login/**").permitAll()
                .anyRequest().authenticated())
            .oauth2Login(o -> o.defaultSuccessUrl("/", true).failureUrl("/?login=failed"))
            .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                (req,res,ex) -> {res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"message\":\"Sign in to continue.\"}");},
                PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
            .logout(l -> l.logoutUrl("/api/logout").logoutSuccessHandler((req,res,auth) -> res.setStatus(204)))
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")));
        return http.build();
    }
}

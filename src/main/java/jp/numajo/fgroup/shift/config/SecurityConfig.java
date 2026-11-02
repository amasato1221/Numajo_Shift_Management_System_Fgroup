package jp.numajo.fgroup.shift.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import jp.numajo.fgroup.shift.repository.UserRepository;
import jp.numajo.fgroup.shift.security.HomeRedirectAccessDeniedHandler;
import jp.numajo.fgroup.shift.security.LoginFailureHandler;
import jp.numajo.fgroup.shift.security.LoginSuccessHandler;
import jp.numajo.fgroup.shift.security.LogoutOperationLogHandler;
import jp.numajo.fgroup.shift.security.PasswordChangeRequiredFilter;
import jp.numajo.fgroup.shift.security.UserActiveCheckFilter;

/**
 * Spring Security の設定（94_セキュリティ設計書）。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, LoginSuccessHandler successHandler,
            LoginFailureHandler failureHandler, LogoutOperationLogHandler logoutHandler,
            UserRepository userRepository) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/css/**", "/js/**", "/images/**", "/webjars/**", "/error",
                        "/ical/**").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/manager/**").hasRole("MANAGER")
                .requestMatchers("/staff/**").hasRole("STAFF")
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .usernameParameter("loginId")
                .passwordParameter("password")
                .successHandler(successHandler)
                .failureHandler(failureHandler)
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/logout")
                .addLogoutHandler(logoutHandler)
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID"))
            .sessionManagement(session -> session.invalidSessionUrl("/login?expired"))
            .exceptionHandling(ex -> ex.accessDeniedHandler(new HomeRedirectAccessDeniedHandler()))
            .addFilterBefore(new UserActiveCheckFilter(userRepository), AuthorizationFilter.class)
            .addFilterAfter(new PasswordChangeRequiredFilter(), UserActiveCheckFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

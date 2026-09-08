package com.clinevo.inbox.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class SecurityConfig {
    private static final String CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    @Bean
    @ConditionalOnProperty(name = "clinevo.auth.mode", havingValue = "demo", matchIfMissing = true)
    SecurityFilterChain demoSecurity(
            HttpSecurity http,
            @Qualifier("corsConfigurationSource") CorsConfigurationSource cors
    ) throws Exception {
        return common(http, cors)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "clinevo.auth.mode", havingValue = "oidc")
    SecurityFilterChain oidcSecurity(
            HttpSecurity http,
            @Qualifier("corsConfigurationSource") CorsConfigurationSource cors,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            SecurityErrorHandler errors
    ) throws Exception {
        common(http, cors)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/api/health").permitAll()
                        .requestMatchers("/actuator/info", "/actuator/metrics", "/actuator/metrics/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").hasAnyRole("REVIEWER", "ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(errors::authenticationRequired)
                        .accessDeniedHandler(errors::accessDenied))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errors::authenticationRequired)
                        .accessDeniedHandler(errors::accessDenied));
        return http.build();
    }

    private HttpSecurity common(HttpSecurity http, CorsConfigurationSource cors) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(corsCustomizer -> corsCustomizer.configurationSource(cors))
                .sessionManagement(session -> session.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP)));
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${clinevo.cors-origin:http://localhost:4200}") String origins) {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> allowed = Arrays.stream(origins.split(",")).map(String::trim).filter(value -> !value.isBlank()).toList();
        configuration.setAllowedOrigins(allowed);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-API-Key", "X-Request-ID"));
        configuration.setExposedHeaders(List.of("X-Request-ID"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    @ConditionalOnProperty(name = "clinevo.auth.mode", havingValue = "oidc")
    JwtDecoder jwtDecoder(
            @Value("${clinevo.auth.oidc.issuer-uri:}") String issuer,
            @Value("${clinevo.auth.oidc.jwk-set-uri:}") String jwkSetUri,
            @Value("${clinevo.auth.oidc.audience:clinevo-inbox}") String audience
    ) {
        if (issuer.isBlank() || jwkSetUri.isBlank() || audience.isBlank()) {
            throw new IllegalStateException("OIDC mode requires issuer-uri, jwk-set-uri and audience");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> audienceValidator = token -> token.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Required audience is missing", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
        return decoder;
    }

    @Bean
    @ConditionalOnProperty(name = "clinevo.auth.mode", havingValue = "oidc")
    JwtAuthenticationConverter jwtAuthenticationConverter(
            @Value("${clinevo.auth.oidc.roles-claim:roles}") String rolesClaim,
            @Value("${clinevo.auth.oidc.principal-claim:preferred_username}") String principalClaim
    ) {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(rolesClaim);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        converter.setPrincipalClaimName(principalClaim);
        return converter;
    }
}

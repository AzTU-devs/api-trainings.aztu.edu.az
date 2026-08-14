package com.eduplatform.eduplatform_backend.common.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder(AppSecurityProperties props) {
        int strength = props.bcryptStrength() == 0 ? 12 : props.bcryptStrength();
        return new BCryptPasswordEncoder(strength);
    }
}

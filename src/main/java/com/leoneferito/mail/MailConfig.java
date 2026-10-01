package com.leoneferito.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    public Mailer mailer(ObjectProvider<JavaMailSender> senderProvider,
                         @Value("${app.mail.from}") String from) {
        JavaMailSender sender = senderProvider.getIfAvailable();
        if (sender == null) {
            log.warn("메일 설정(spring.mail.host)이 없습니다. 메일을 보내지 않습니다.");
            return (to, subject, text) -> log.warn("메일 미발송(설정 없음) subject={}", subject);
        }
        return (to, subject, text) -> Thread.ofVirtual().name("mail").start(() -> {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            try {
                sender.send(message);
                log.info("메일 발송 subject={}", subject);
            } catch (MailException e) {
                log.error("메일 발송 실패 subject={}", subject, e);
            }
        });
    }
}
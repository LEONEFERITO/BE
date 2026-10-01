package com.leoneferito;

import com.leoneferito.mail.Mailer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 메일을 보내는 대신 받아 적는다. 테스트가 "메일이 갔는가 · 링크가 들었는가" 를 볼 수 있게.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestMailConfiguration {

    public record SentMail(String to, String subject, String text) {
    }

    public static class RecordingMailer implements Mailer {
        private final List<SentMail> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(String to, String subject, String text) {
            sent.add(new SentMail(to, subject, text));
        }

        public List<SentMail> sent() {
            return sent;
        }

        public void clear() {
            sent.clear();
        }
    }

    @Bean
    @Primary
    public RecordingMailer recordingMailer() {
        return new RecordingMailer();
    }
}

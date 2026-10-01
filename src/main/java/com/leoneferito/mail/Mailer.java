package com.leoneferito.mail;

public interface Mailer {
    void send(String to, String subject, String text);
}

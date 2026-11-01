package com.dypcet.vayora.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sends email for real through an SMTP server, and text messages through Twilio, when each
 * is configured; anything else goes to the simulated outbox.
 *
 * <p>An SMTP server is configured with SPRING_MAIL_HOST (and SPRING_MAIL_PORT,
 * SPRING_MAIL_USERNAME, SPRING_MAIL_PASSWORD as it needs) and VAYORA_MAIL_FROM. Without a
 * host, Spring makes no mail sender and nothing is emailed. Twilio: see {@link TwilioSms}.
 */
@Primary
@Component
public class DeliveringMessageSender implements MessageSender {

    public static final String SMTP = "SMTP";

    private static final Logger log = LoggerFactory.getLogger(DeliveringMessageSender.class);

    private final MockMessageSender mock;
    private final JavaMailSender mail;
    private final String from;
    private final TwilioSms sms;

    public DeliveringMessageSender(MockMessageSender mock, ObjectProvider<JavaMailSender> mail,
            @Value("${vayora.mail.from:}") String from, TwilioSms sms) {
        this.mock = mock;
        this.mail = mail.getIfAvailable();
        this.from = from;
        this.sms = sms;
        if (this.mail != null && from.isBlank()) {
            throw new IllegalStateException("An SMTP server is configured but VAYORA_MAIL_FROM is not");
        }
    }

    @Override
    public String name(Channel channel) {
        if (channel == Channel.PHONE) {
            return sms.configured() ? TwilioSms.NAME : mock.name(channel);
        }
        return mail != null ? SMTP : mock.name(channel);
    }

    @Override
    public void send(Channel channel, String to, String text) {
        if (channel == Channel.PHONE) {
            if (!sms.configured()) {
                mock.send(channel, to, text);
                return;
            }
            try {
                sms.send(to, text);
            } catch (RestClientException e) {
                log.warn("Text message to {} not sent: {}", to, e.getMessage());
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The text message could not be sent. Try again later.");
            }
            return;
        }
        if (mail == null) {
            mock.send(channel, to, text);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject(text));
        message.setText(text);
        try {
            mail.send(message);
        } catch (MailException e) {
            log.warn("Email to {} not sent: {}", to, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The email could not be sent. Try again later.");
        }
    }

    /** The first sentence, short enough for a subject line. */
    private static String subject(String text) {
        String body = text.startsWith("VAYORA: ") ? text.substring("VAYORA: ".length()) : text;
        int end = body.indexOf(". ");
        String first = end > 0 ? body.substring(0, end + 1) : body;
        return "VAYORA: " + (first.length() > 80 ? first.substring(0, 77) + "..." : first);
    }
}

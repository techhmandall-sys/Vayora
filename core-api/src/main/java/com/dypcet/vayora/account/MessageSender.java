package com.dypcet.vayora.account;

/** Sends an email or a text message to someone outside the platform. */
public interface MessageSender {

    enum Channel {
        EMAIL,
        PHONE
    }

    /**
     * Who sends messages of this kind, recorded wherever a message matters so a record says
     * whether it really went.
     */
    String name(Channel channel);

    void send(Channel channel, String to, String text);
}

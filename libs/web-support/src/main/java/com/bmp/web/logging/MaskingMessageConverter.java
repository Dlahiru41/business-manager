package com.bmp.web.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback converter for plain-text logs (the {@code local} profile): registered in {@code logback-spring.xml} for
 * {@code %m}, {@code %msg} and {@code %message} so every pattern prints the masked message.
 */
public class MaskingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogMasker.mask(super.convert(event));
    }
}

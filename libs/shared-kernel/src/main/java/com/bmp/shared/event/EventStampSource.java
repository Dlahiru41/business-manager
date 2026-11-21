package com.bmp.shared.event;

/** Supplies a new {@link EventStamp} for every event an aggregate records. */
@FunctionalInterface
public interface EventStampSource {

    EventStamp next();
}

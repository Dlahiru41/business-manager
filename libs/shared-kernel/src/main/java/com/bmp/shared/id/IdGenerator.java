package com.bmp.shared.id;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import java.util.UUID;
import java.util.function.Function;

/**
 * Generates identifiers. Production uses time-ordered UUIDv7 so ids sort by creation time and index well; tests may
 * supply a deterministic implementation.
 */
@FunctionalInterface
public interface IdGenerator {

    UUID newUuid();

    /** Creates a typed id, e.g. {@code ids.next(TenantId::new)}. */
    default <T> T next(Function<UUID, T> factory) {
        return factory.apply(newUuid());
    }

    /** The default, thread-safe UUIDv7 generator. */
    static IdGenerator uuidV7() {
        return UuidV7.INSTANCE;
    }

    /** Holder for the shared UUIDv7 generator. */
    final class UuidV7 implements IdGenerator {

        private static final UuidV7 INSTANCE = new UuidV7();

        private final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();

        private UuidV7() {}

        @Override
        public UUID newUuid() {
            return generator.generate();
        }
    }
}

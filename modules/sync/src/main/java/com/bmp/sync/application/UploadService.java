package com.bmp.sync.application;

import com.bmp.sales.api.SalesQueries;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.DomainException;
import com.bmp.shared.error.ErrorCode;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.id.RegisterId;
import com.bmp.sync.domain.error.SyncErrorCode;
import com.bmp.sync.domain.model.RecordOutcome;
import com.bmp.sync.domain.model.UploadRecordType;
import com.bmp.sync.domain.model.UploadStatus;
import com.bmp.sync.domain.port.SyncStates;
import com.bmp.sync.domain.port.UploadLog;
import com.bmp.sync.domain.service.ClockSkew;
import com.bmp.sync.domain.service.NumberGaps;
import com.bmp.web.problem.HttpStatuses;
import com.bmp.web.problem.ProblemFactory;
import com.bmp.web.problem.ProblemMessages;
import io.micrometer.observation.annotation.Observed;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Rules SYN-04..SYN-07: an ordered batch of what a till did offline. Each record runs in its own transaction, which
 * also stores its outcome, so a record takes effect exactly once however often the batch is sent, and a batch cut off
 * half-way resumes where it stopped. A record whose dependency was rejected is rejected too. After the batch, missing
 * bill numbers are reported once and the device's clock skew is recorded.
 */
@Service
public class UploadService {

    public static final int MAX_RECORDS = 1_000;
    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    private final SyncDevices devices;
    private final RecordDispatcher dispatcher;
    private final UploadLog uploads;
    private final SyncStates states;
    private final SalesQueries sales;
    private final SyncEvents events;
    private final ProblemMessages messages;
    private final ProblemFactory problems;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @SuppressWarnings("TooManyParameters") // the stores and modules an upload reaches
    public UploadService(
            SyncDevices devices,
            RecordDispatcher dispatcher,
            UploadLog uploads,
            SyncStates states,
            SalesQueries sales,
            SyncEvents events,
            MessageSource messageSource,
            ProblemFactory problems,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.devices = devices;
        this.dispatcher = dispatcher;
        this.uploads = uploads;
        this.states = states;
        this.sales = sales;
        this.events = events;
        this.messages = new ProblemMessages(messageSource);
        this.problems = problems;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /** A record as the till sent it. */
    public record Incoming(UploadRecordType type, UUID id, List<UUID> dependsOn, JsonNode payload) {

        public Incoming {
            dependsOn = List.copyOf(dependsOn);
        }
    }

    /** The outcome of every record, in order, and the counts. */
    public record Result(List<RecordOutcome> results, int accepted, int duplicates, int rejected) {

        public Result {
            results = List.copyOf(results);
        }
    }

    /** Thrown inside a record's transaction when another request stored the record first. */
    private static final class AlreadyStored extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AlreadyStored() {
            super(null, null, false, false);
        }
    }

    @Observed(name = "bmp.sync.upload")
    public Result upload(List<Incoming> records, @Nullable Instant deviceTime) {
        if (records.size() > MAX_RECORDS) {
            throw new BusinessRuleViolation(SyncErrorCode.BATCH_TOO_LARGE, Map.of("max", MAX_RECORDS));
        }
        SyncDevices.Till till = devices.current();
        Set<UUID> dependencies = new HashSet<>();
        records.forEach(r -> dependencies.addAll(r.dependsOn()));
        Map<UUID, Boolean> earlier =
                Objects.requireNonNull(transaction.execute(s -> uploads.rejectedById(dependencies)));
        Set<UUID> rejected = new HashSet<>();
        List<RecordOutcome> results = new ArrayList<>();
        for (Incoming record : records) {
            RecordOutcome outcome = process(till, record, rejected, earlier);
            if (outcome.rejected()) {
                rejected.add(record.id());
            }
            results.add(outcome);
        }
        Instant now = clock.instant();
        Long skew = deviceTime == null ? null : ClockSkew.seconds(deviceTime, now);
        transaction.executeWithoutResult(s -> {
            states.seen(till.deviceId(), till.registerId(), false, skew, now);
            reportGaps(till, now);
        });
        if (skew != null && ClockSkew.flagged(skew)) {
            log.atWarn()
                    .addKeyValue("deviceId", till.deviceId())
                    .addKeyValue("clockSkewSeconds", skew)
                    .log("Device clock is off by more than five minutes");
        }
        Result result = new Result(
                results,
                count(results, UploadStatus.ACCEPTED),
                count(results, UploadStatus.DUPLICATE),
                count(results, UploadStatus.REJECTED));
        log.atInfo()
                .addKeyValue("deviceId", till.deviceId())
                .addKeyValue("accepted", result.accepted())
                .addKeyValue("duplicates", result.duplicates())
                .addKeyValue("rejected", result.rejected())
                .log("Sync upload processed");
        return result;
    }

    private RecordOutcome process(
            SyncDevices.Till till, Incoming r, Set<UUID> rejectedNow, Map<UUID, Boolean> rejectedEarlier) {
        boolean blocked = r.dependsOn().stream()
                .anyMatch(d -> rejectedNow.contains(d) || Boolean.TRUE.equals(rejectedEarlier.get(d)));
        try {
            // One transaction per record: the replay check, the record's effect and its outcome commit together.
            return Objects.requireNonNull(transaction.execute(s -> {
                Optional<RecordOutcome> known = uploads.find(r.type(), r.id());
                if (known.isPresent()) {
                    return known.get().replayed();
                }
                RecordOutcome outcome = blocked
                        ? RecordOutcome.rejected(
                                r.type(),
                                r.id(),
                                problem(SyncErrorCode.DEPENDENCY_REJECTED, "A record it depends on was rejected"))
                        : RecordOutcome.accepted(r.type(), r.id());
                if (!blocked) {
                    dispatcher.dispatch(till, r.type(), r.id(), r.payload());
                }
                if (!uploads.record(outcome, till.deviceId())) {
                    throw new AlreadyStored();
                }
                return outcome;
            }));
        } catch (AlreadyStored e) {
            return Objects.requireNonNull(transaction.execute(s -> uploads.find(r.type(), r.id())))
                    .orElseThrow()
                    .replayed();
        } catch (DomainException e) {
            return store(
                    till,
                    RecordOutcome.rejected(
                            r.type(),
                            r.id(),
                            problem(e.errorCode(), messages.detail(e.errorCode(), e.details(), Locale.ENGLISH))));
        } catch (DataIntegrityViolationException e) {
            // A record clashing with stored data (an id used before) is rejected; it never blocks the till's batch.
            return store(
                    till,
                    RecordOutcome.rejected(
                            r.type(),
                            r.id(),
                            problem(
                                    PlatformErrorCode.VALIDATION_FAILED,
                                    "The record conflicts with data already stored")));
        } catch (InvalidPayloadException e) {
            // Only a payload that cannot be read is rejected here; any other failure stops the batch (ADR-029 §3).
            log.atInfo()
                    .addKeyValue("recordType", r.type())
                    .addKeyValue("recordId", r.id())
                    .log("Upload record rejected: unreadable payload");
            return store(
                    till,
                    RecordOutcome.rejected(
                            r.type(),
                            r.id(),
                            problem(
                                    PlatformErrorCode.VALIDATION_FAILED,
                                    "The payload does not match the " + r.type() + " record")));
        }
    }

    private RecordOutcome store(SyncDevices.Till till, RecordOutcome outcome) {
        Boolean stored = transaction.execute(s -> uploads.record(outcome, till.deviceId()));
        if (Boolean.TRUE.equals(stored)) {
            return outcome;
        }
        return Objects.requireNonNull(transaction.execute(s -> uploads.find(outcome.type(), outcome.id())))
                .orElse(outcome)
                .replayed();
    }

    private RecordOutcome.Problem problem(ErrorCode code, @Nullable String detail) {
        return new RecordOutcome.Problem(
                problems.typeOf(code).toString(),
                code.code(),
                messages.title(code, Locale.ENGLISH),
                HttpStatuses.of(code).value(),
                detail);
    }

    /** Rule SYN-06. */
    private void reportGaps(SyncDevices.Till till, Instant now) {
        List<Long> missing = NumberGaps.newGaps(
                sales.billSequences(RegisterId.of(till.registerId())), states.reportedGaps(till.registerId()));
        if (missing.isEmpty()) {
            return;
        }
        states.reportGaps(till.registerId(), missing, now);
        List<String> numbers = missing.stream()
                .map(n -> till.billPrefix() + "-" + "%06d".formatted(n))
                .toList();
        events.gapDetected(till.registerId(), till.billPrefix(), numbers);
        log.atWarn()
                .addKeyValue("registerId", till.registerId())
                .addKeyValue("missingNumbers", numbers)
                .log("Bill numbers missing");
    }

    private static int count(List<RecordOutcome> results, UploadStatus status) {
        return (int) results.stream().filter(r -> r.status() == status).count();
    }
}

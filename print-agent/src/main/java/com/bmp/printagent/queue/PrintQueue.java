package com.bmp.printagent.queue;

import com.bmp.printagent.printer.PrinterStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Print requests on disk ({@code print_request}), oldest first. */
@Repository
public class PrintQueue {

    private static final int ERROR_LENGTH = 500;

    private final JdbcClient jdbc;
    private final Clock clock;

    public PrintQueue(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Where a request came from. */
    public enum Source {
        LOCAL,
        CLOUD
    }

    /** Where a request stands. */
    public enum Status {
        QUEUED,
        PRINTED,
        FAILED
    }

    /** A print request without its document. */
    public record Request(
            UUID id,
            Source source,
            @Nullable UUID cloudJobId,
            Status status,
            int attempts,
            Instant nextAttemptAt,
            @Nullable String lastError,
            @Nullable String resultPrinterStatus,
            boolean reported,
            Instant createdAt) {}

    /** Keeps a document to print; a job the cloud sent again is kept once (its id is returned). */
    public UUID add(Source source, @Nullable UUID cloudJobId, byte[] document) {
        if (cloudJobId != null) {
            Optional<Request> known = findByCloudJob(cloudJobId);
            if (known.isPresent()) {
                return known.get().id();
            }
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime now = now();
        jdbc.sql("""
                        INSERT INTO print_request (id, source, cloud_job_id, document, status, next_attempt_at, created_at)
                        VALUES (:id, :source, :job, :document, 'QUEUED', :now, :now)""")
                .param("id", id)
                .param("source", source.name())
                .param("job", cloudJobId)
                .param("document", document)
                .param("now", now)
                .update();
        return id;
    }

    public Optional<Request> find(UUID id) {
        return jdbc.sql("SELECT * FROM print_request WHERE id = :id")
                .param("id", id)
                .query(PrintQueue::request)
                .optional();
    }

    public Optional<Request> findByCloudJob(UUID jobId) {
        return jdbc.sql("SELECT * FROM print_request WHERE cloud_job_id = :job")
                .param("job", jobId)
                .query(PrintQueue::request)
                .optional();
    }

    /** The oldest request still to print. */
    public Optional<Request> head() {
        return jdbc.sql("SELECT * FROM print_request WHERE status = 'QUEUED' ORDER BY seq FETCH FIRST 1 ROW ONLY")
                .query(PrintQueue::request)
                .optional();
    }

    public byte[] document(UUID id) {
        return jdbc.sql("SELECT document FROM print_request WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> rs.getBytes(1))
                .single();
    }

    public int queued() {
        return jdbc.sql("SELECT count(*) FROM print_request WHERE status = 'QUEUED'")
                .query(Integer.class)
                .single();
    }

    public void printed(UUID id, int attempts, PrinterStatus printer) {
        complete(id, Status.PRINTED, attempts, null, printer);
    }

    /** A request given up on (cloud jobs only): the result to report is FAILED. */
    public void failed(UUID id, int attempts, String error, PrinterStatus printer) {
        complete(id, Status.FAILED, attempts, error, printer);
    }

    /** A failed attempt; the request waits until {@code next}. */
    public void retryLater(UUID id, int attempts, String error, Instant next) {
        jdbc.sql("""
                        UPDATE print_request SET attempts = :attempts, last_error = :error, next_attempt_at = :next
                         WHERE id = :id AND status = 'QUEUED'""")
                .param("id", id)
                .param("attempts", attempts)
                .param("error", shorten(error))
                .param("next", next.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** Cloud jobs printed or given up on whose result the cloud has not taken yet, oldest first. */
    public List<Request> unreported() {
        return jdbc.sql("""
                        SELECT * FROM print_request
                         WHERE source = 'CLOUD' AND status <> 'QUEUED' AND NOT reported ORDER BY seq""").query(PrintQueue::request).list();
    }

    public void reported(UUID id, boolean reported) {
        jdbc.sql("UPDATE print_request SET reported = :reported WHERE id = :id")
                .param("id", id)
                .param("reported", reported)
                .update();
    }

    /** Forgets requests done before {@code before}; cloud jobs only once their result was taken. */
    public int purge(Instant before) {
        return jdbc.sql("""
                        DELETE FROM print_request
                         WHERE status <> 'QUEUED' AND (source = 'LOCAL' OR reported) AND completed_at < :before""").param("before", before.atOffset(ZoneOffset.UTC)).update();
    }

    private void complete(UUID id, Status status, int attempts, @Nullable String error, PrinterStatus printer) {
        jdbc.sql("""
                        UPDATE print_request
                           SET status = :status, attempts = :attempts, last_error = :error,
                               result_printer_status = :printer, completed_at = :now
                         WHERE id = :id AND status = 'QUEUED'""")
                .param("id", id)
                .param("status", status.name())
                .param("attempts", attempts)
                .param("error", error == null ? null : shorten(error))
                .param("printer", printer.name())
                .param("now", now())
                .update();
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    private static String shorten(String error) {
        return error.length() <= ERROR_LENGTH ? error : error.substring(0, ERROR_LENGTH);
    }

    private static Request request(ResultSet rs, int row) throws SQLException {
        return new Request(
                rs.getObject("id", UUID.class),
                Source.valueOf(rs.getString("source")),
                rs.getObject("cloud_job_id", UUID.class),
                Status.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getObject("next_attempt_at", OffsetDateTime.class).toInstant(),
                rs.getString("last_error"),
                rs.getString("result_printer_status"),
                rs.getBoolean("reported"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}

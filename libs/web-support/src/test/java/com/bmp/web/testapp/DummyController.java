package com.bmp.web.testapp;

import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.FieldError;
import com.bmp.shared.error.IntegrationException;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.error.TechnicalException;
import com.bmp.shared.error.ValidationFailure;
import com.bmp.shared.error.VersionConflict;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.shared.money.Money;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.CursorCodec;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test")
public class DummyController {

    public static final AtomicInteger CREATIONS = new AtomicInteger();
    public static final AtomicInteger EXPLOSIONS = new AtomicInteger();
    public static final UUID THING_ID = UUID.fromString("0193a6f1-0000-7000-8000-00000000abcd");
    public static final long CURRENT_VERSION = 3;

    public record CreateThing(
            @NotBlank @Size(max = 20) String name, @NotNull Money price) {}

    public record Thing(UUID id, String name, Money price, long version) {}

    @Idempotent
    @PostMapping("/things")
    ResponseEntity<Thing> create(@Valid @RequestBody CreateThing body) {
        CREATIONS.incrementAndGet();
        Thing thing = new Thing(THING_ID, body.name(), body.price(), 0);
        return ResponseEntity.created(URI.create("/test/things/" + THING_ID))
                .eTag(Etags.of(0))
                .body(thing);
    }

    @Idempotent
    @PostMapping("/explode")
    ResponseEntity<Void> explode() {
        EXPLOSIONS.incrementAndGet();
        throw new IllegalStateException("select secret from internal_table");
    }

    @GetMapping("/things/{id}")
    Thing get(@PathVariable UUID id) {
        return new Thing(id, "Eggs", Money.of("62.50", CurrencyCode.LKR), CURRENT_VERSION);
    }

    @PatchMapping("/things/{id}")
    ResponseEntity<Thing> update(
            @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody CreateThing body) {
        IfMatch.check(IfMatch.expectedVersion(ifMatch), CURRENT_VERSION);
        return ResponseEntity.ok()
                .eTag(Etags.of(CURRENT_VERSION + 1))
                .body(new Thing(id, body.name(), body.price(), CURRENT_VERSION + 1));
    }

    @GetMapping("/things")
    PageResponse<String> list(
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        PageQuery query = PageQuery.of(limit, cursor);
        if (query.cursor() != null) {
            CursorCodec.decode(query.cursor(), 1);
        }
        List<String> fetched =
                IntStream.range(0, query.fetchSize()).mapToObj(i -> "item-" + i).toList();
        return PageResponse.of(fetched, query, item -> List.of(item));
    }

    @GetMapping("/errors/{kind}")
    void fail(@PathVariable String kind) throws Exception {
        switch (kind) {
            case "validation" -> throw new BusinessRuleViolation(TestErrorCode.NAME_INVALID);
            case "fields" -> throw new ValidationFailure(List.of(new FieldError("name", "REQUIRED", "is required")));
            case "not-found" -> throw new NotFoundException(TestErrorCode.THING_NOT_FOUND, Map.of("thingId", THING_ID));
            case "conflict" -> throw new BusinessRuleViolation(TestErrorCode.THING_LOCKED, Map.of("thingId", THING_ID));
            case "forbidden" -> throw new BusinessRuleViolation(PlatformErrorCode.ACCESS_DENIED);
            case "precondition" -> throw new VersionConflict(5);
            case "rate-limited" -> throw new BusinessRuleViolation(PlatformErrorCode.RATE_LIMIT_EXCEEDED);
            case "dependency" ->
                throw new IntegrationException(
                        TestErrorCode.PRINTER_UNREACHABLE, Map.of("agentId", "a1"), new IOException("refused"));
            case "internal" ->
                throw new TechnicalException(PlatformErrorCode.INTERNAL_ERROR, Map.of("secret", "s3cr3t"), null);
            case "unique" ->
                throw new DataIntegrityViolationException(
                        "could not execute statement",
                        new SQLException(
                                "ERROR: duplicate key value violates unique constraint \"uk_thing_tenant_name\"",
                                "23505"));
            case "unknown-constraint" ->
                throw new DataIntegrityViolationException(
                        "could not execute statement",
                        new SQLException("ERROR: violates check constraint \"ck_other\"", "23514"));
            case "no-tenant" ->
                throw new UncategorizedSQLException(
                        "query", "select 1", new SQLException("TENANT_CONTEXT_MISSING", "BM001"));
            case "optimistic" -> throw new OptimisticLockingFailureException("stale");
            case "circuit" ->
                throw CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("idp"));
            default -> throw new IllegalStateException("select * from secret_table");
        }
    }
}

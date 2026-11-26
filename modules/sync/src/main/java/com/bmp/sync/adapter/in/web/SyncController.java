package com.bmp.sync.adapter.in.web;

import com.bmp.sync.adapter.in.web.generated.api.SyncApi;
import com.bmp.sync.adapter.in.web.generated.model.Bootstrap400Response;
import com.bmp.sync.adapter.in.web.generated.model.BootstrapResponse;
import com.bmp.sync.adapter.in.web.generated.model.ChangesResponse;
import com.bmp.sync.adapter.in.web.generated.model.Dataset;
import com.bmp.sync.adapter.in.web.generated.model.RegisterInfo;
import com.bmp.sync.adapter.in.web.generated.model.SyncDatasets;
import com.bmp.sync.adapter.in.web.generated.model.SyncStatus;
import com.bmp.sync.adapter.in.web.generated.model.UploadRecord;
import com.bmp.sync.adapter.in.web.generated.model.UploadRecordType;
import com.bmp.sync.adapter.in.web.generated.model.UploadRequest;
import com.bmp.sync.adapter.in.web.generated.model.UploadResponse;
import com.bmp.sync.adapter.in.web.generated.model.UploadResult;
import com.bmp.sync.adapter.in.web.generated.model.UploadStatus;
import com.bmp.sync.application.BootstrapService;
import com.bmp.sync.application.SyncDevices;
import com.bmp.sync.application.UploadService;
import com.bmp.sync.domain.model.RecordOutcome;
import com.bmp.web.idempotency.Idempotent;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Offline tills: bootstrap, changes, uploads and status (SYN-01..SYN-08). */
@RestController
class SyncController implements SyncApi {

    private final BootstrapService datasets;
    private final UploadService uploads;
    private final JsonMapper json;
    private final Clock clock;

    SyncController(BootstrapService datasets, UploadService uploads, JsonMapper json, Clock clock) {
        this.datasets = datasets;
        this.uploads = uploads;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'sync:read')")
    public ResponseEntity<BootstrapResponse> bootstrap(@Nullable OffsetDateTime deviceTime) {
        BootstrapService.Snapshot snapshot = datasets.bootstrap(instant(deviceTime));
        SyncDevices.Till till = snapshot.till();
        return ResponseEntity.ok(new BootstrapResponse()
                .deviceId(till.deviceId())
                .register(new RegisterInfo()
                        .id(till.registerId())
                        .code(till.registerCode())
                        .billPrefix(till.billPrefix())
                        .lastNumber(till.lastNumber())
                        .locationId(till.locationId()))
                .datasets(datasets(snapshot.datasets()))
                .serverTime(utc(snapshot.serverTime())));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'sync:read')")
    public ResponseEntity<ChangesResponse> changes(String cursors, @Nullable OffsetDateTime deviceTime) {
        BootstrapService.Snapshot snapshot = datasets.changes(cursors, instant(deviceTime));
        return ResponseEntity.ok(
                new ChangesResponse().datasets(datasets(snapshot.datasets())).serverTime(utc(snapshot.serverTime())));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'sync:upload')")
    public ResponseEntity<UploadResponse> upload(
            UUID idempotencyKey, UploadRequest request, @Nullable OffsetDateTime deviceTime) {
        List<UploadService.Incoming> records =
                request.getRecords().stream().map(this::incoming).toList();
        UploadService.Result result = uploads.upload(records, instant(deviceTime));
        Instant now = clock.instant();
        return ResponseEntity.ok(new UploadResponse()
                .results(result.results().stream().map(o -> result(o, now)).toList())
                .accepted(result.accepted())
                .duplicates(result.duplicates())
                .rejected(result.rejected()));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'sync:read')")
    public ResponseEntity<SyncStatus> syncStatus() {
        BootstrapService.Status status = datasets.status();
        SyncStatus body = new SyncStatus()
                .deviceId(status.till().deviceId())
                .registerId(status.till().registerId())
                .locationId(status.till().locationId())
                .flags(List.of())
                .missingNumbers(status.missingNumbers());
        status.state()
                .ifPresent(s -> body.lastBootstrapAt(time(s.lastBootstrapAt()))
                        .lastUploadAt(time(s.lastUploadAt()))
                        .clockSkewSeconds(s.clockSkewSeconds())
                        .flags(s.flags().stream()
                                .map(SyncStatus.FlagsEnum::fromValue)
                                .toList()));
        return ResponseEntity.ok(body);
    }

    private UploadService.Incoming incoming(UploadRecord r) {
        return new UploadService.Incoming(
                com.bmp.sync.domain.model.UploadRecordType.valueOf(r.getType().getValue()),
                r.getId(),
                r.getDependsOn() == null ? List.of() : r.getDependsOn(),
                json.valueToTree(r.getPayload()));
    }

    private static UploadResult result(RecordOutcome o, Instant now) {
        UploadResult result = new UploadResult()
                .type(UploadRecordType.fromValue(o.type().name()))
                .id(o.id())
                .status(UploadStatus.fromValue(o.status().name()));
        RecordOutcome.Problem p = o.problem();
        if (p != null) {
            result.problem(new Bootstrap400Response()
                    .type(URI.create(p.type()))
                    .title(p.title())
                    .status(p.status())
                    .detail(p.detail())
                    .code(p.code())
                    .timestamp(utc(now)));
        }
        return result;
    }

    private static SyncDatasets datasets(Map<String, BootstrapService.Dataset> sets) {
        SyncDatasets body = new SyncDatasets();
        sets.forEach((name, set) -> {
            Dataset dataset = new Dataset().version(set.version()).data(set.data());
            switch (name) {
                case "catalog" -> body.catalog(dataset);
                case "priceLists" -> body.priceLists(dataset);
                case "taxRates" -> body.taxRates(dataset);
                case "settings" -> body.settings(dataset);
                case "customers" -> body.customers(dataset);
                case "stockBalances" -> body.stockBalances(dataset);
                default -> throw new IllegalStateException("Unknown dataset " + name);
            }
        });
        return body;
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    private static @Nullable OffsetDateTime time(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

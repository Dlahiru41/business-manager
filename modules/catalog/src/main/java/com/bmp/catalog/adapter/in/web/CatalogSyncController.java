package com.bmp.catalog.adapter.in.web;

import com.bmp.catalog.adapter.in.web.generated.api.CatalogSyncApi;
import com.bmp.catalog.adapter.in.web.generated.model.CatalogSnapshot;
import com.bmp.catalog.application.query.CatalogQueryService;
import com.bmp.catalog.application.query.SnapshotView;
import com.bmp.web.etag.Etags;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** The catalog snapshot tills cache (CAT-13); the ETag is the catalog version. */
@RestController
class CatalogSyncController implements CatalogSyncApi {

    private final CatalogQueryService queries;
    private final CatalogWebMapper mapper;

    CatalogSyncController(CatalogQueryService queries, CatalogWebMapper mapper) {
        this.queries = queries;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:read')")
    public ResponseEntity<CatalogSnapshot> getCatalogSnapshot(
            @Nullable Long sinceVersion, @Nullable String ifNoneMatch) {
        OptionalLong known = Etags.parse(ifNoneMatch);
        long version = queries.catalogVersion();
        if (known.isPresent() && known.getAsLong() == version) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(Etags.of(version))
                    .build();
        }
        SnapshotView snapshot = queries.snapshot(sinceVersion);
        return ResponseEntity.ok().eTag(Etags.of(snapshot.version())).body(mapper.toDto(snapshot));
    }
}

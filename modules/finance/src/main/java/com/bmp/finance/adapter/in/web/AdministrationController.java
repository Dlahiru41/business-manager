package com.bmp.finance.adapter.in.web;

import com.bmp.finance.adapter.in.web.generated.api.AdministrationApi;
import com.bmp.finance.adapter.in.web.generated.model.RebuildResult;
import com.bmp.finance.application.command.RebuildPartyBalancesHandler;
import com.bmp.web.idempotency.Idempotent;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AdministrationController implements AdministrationApi {

    private final RebuildPartyBalancesHandler rebuild;

    AdministrationController(RebuildPartyBalancesHandler rebuild) {
        this.rebuild = rebuild;
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'finance:admin')")
    public ResponseEntity<RebuildResult> rebuildPartyBalances(UUID idempotencyKey) {
        RebuildPartyBalancesHandler.Result result = rebuild.rebuild();
        return ResponseEntity.ok(new RebuildResult().parties(result.parties()).changed(result.changed()));
    }
}

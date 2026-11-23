package com.bmp.party.application.command;

import com.bmp.party.application.PartyLoader;
import com.bmp.party.application.query.CustomerGroupView;
import com.bmp.party.domain.model.CustomerGroup;
import com.bmp.party.domain.model.PartyStatus;
import com.bmp.party.domain.port.CustomerGroupRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.IdGenerator;
import com.bmp.web.etag.IfMatch;
import io.micrometer.observation.annotation.Observed;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adds and changes customer groups; codes are unique per tenant (the store's unique key). */
@Service
public class CustomerGroupHandlers {

    private final CustomerGroupRepository groups;
    private final PartyLoader loader;
    private final IdGenerator ids;

    public CustomerGroupHandlers(CustomerGroupRepository groups, PartyLoader loader, IdGenerator ids) {
        this.groups = groups;
        this.loader = loader;
        this.ids = ids;
    }

    @Transactional
    @Observed(name = "bmp.party.create-customer-group")
    public CustomerGroupView create(String code, String name) {
        return CustomerGroupView.of(
                groups.saveAndFlush(CustomerGroup.create(ids.newUuid(), TenantContext.require(), code, name)));
    }

    @Transactional
    @Observed(name = "bmp.party.update-customer-group")
    public CustomerGroupView update(
            UUID id, long expectedVersion, @Nullable String name, @Nullable PartyStatus status) {
        CustomerGroup group = loader.group(id);
        IfMatch.check(expectedVersion, group.version());
        if (name != null) {
            group.rename(name);
        }
        if (status == PartyStatus.INACTIVE) {
            group.deactivate();
        } else if (status == PartyStatus.ACTIVE) {
            group.activate();
        }
        return CustomerGroupView.of(groups.saveAndFlush(group));
    }
}

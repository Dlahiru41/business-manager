package com.bmp.party.application.query;

import com.bmp.party.application.PartyLoader;
import com.bmp.party.domain.model.PartyStatus;
import com.bmp.party.domain.model.PartyType;
import com.bmp.party.domain.model.PhoneNumbers;
import com.bmp.party.domain.port.CustomerGroupRepository;
import com.bmp.party.domain.port.PartyRepository;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.web.pagination.CursorCodec;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read models of parties and customer groups. */
@Service
public class PartyQueryService {

    private final PartyRepository parties;
    private final CustomerGroupRepository groups;
    private final PartyLoader loader;

    public PartyQueryService(PartyRepository parties, CustomerGroupRepository groups, PartyLoader loader) {
        this.parties = parties;
        this.groups = groups;
        this.loader = loader;
    }

    @Transactional(readOnly = true)
    public PageResponse<PartyView> parties(
            @Nullable PartyType type, @Nullable PartyStatus status, @Nullable UUID customerGroupId, PageQuery query) {
        String cursor = query.cursor();
        UUID before = cursor == null ? null : uuid(cursor);
        List<PartyView> page = parties.findPage(type, status, customerGroupId, before, query.fetchSize()).stream()
                .map(PartyView::of)
                .toList();
        return PageResponse.of(page, query, p -> List.of(p.id().toString()));
    }

    @Transactional(readOnly = true)
    public PartyView party(UUID id) {
        return PartyView.of(loader.party(id));
    }

    /**
     * Active parties whose name has a word starting with {@code q}, or whose phone starts with the number {@code q}
     * stands for; CUSTOMER and SUPPLIER also find BOTH.
     */
    @Transactional(readOnly = true)
    public List<PartySummaryView> search(String q, @Nullable PartyType type, int limit) {
        String phone =
                PhoneNumbers.searchPrefix(q, PhoneNumbers.DEFAULT_CALLING_CODE).orElse(null);
        return parties.search(q.strip().toLowerCase(Locale.ROOT), phone, types(type), limit).stream()
                .map(PartySummaryView::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerGroupView> groups(PageQuery query) {
        String cursor = query.cursor();
        String after = cursor == null ? null : CursorCodec.decode(cursor, 1).getFirst();
        List<CustomerGroupView> page = groups.findPage(after, query.fetchSize()).stream()
                .map(CustomerGroupView::of)
                .toList();
        return PageResponse.of(page, query, g -> List.of(g.code()));
    }

    @Transactional(readOnly = true)
    public CustomerGroupView group(UUID id) {
        return CustomerGroupView.of(loader.group(id));
    }

    static Set<PartyType> types(@Nullable PartyType type) {
        if (type == null) {
            return EnumSet.allOf(PartyType.class);
        }
        return type == PartyType.BOTH ? EnumSet.of(PartyType.BOTH) : EnumSet.of(type, PartyType.BOTH);
    }

    private static UUID uuid(String cursor) {
        try {
            return UUID.fromString(CursorCodec.decode(cursor, 1).getFirst());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.INVALID_PARAMETER, "The cursor is invalid", Map.of("parameter", "cursor"));
        }
    }
}

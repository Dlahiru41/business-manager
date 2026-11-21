package com.bmp.iam.application;

import com.bmp.iam.domain.model.Role;
import com.bmp.iam.domain.port.RoleRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.security.SystemRole;
import com.bmp.web.security.PermissionResolver;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Expands role codes from the token into permission grants using the tenant role table (08-security.md §3), cached
 * for 60 seconds per tenant and role set. A change of a role evicts its tenant on this instance after commit; other
 * instances converge within the cache lifetime.
 *
 * <p>A system role code missing from the table (a tenant not yet seeded) falls back to the documented defaults of
 * {@link SystemRole}: rows can only narrow the defaults once they exist. Unknown or inactive custom roles grant
 * nothing.
 */
@Component
public class RolePermissionResolver implements PermissionResolver {

    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final long MAX_ENTRIES = 50_000;

    private final RoleRepository roles;
    private final TransactionTemplate readOnly;
    private final Cache<Key, Set<String>> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(MAX_ENTRIES)
            .build();

    public RolePermissionResolver(RoleRepository roles, PlatformTransactionManager transactionManager) {
        this.roles = roles;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    private record Key(TenantId tenant, Set<String> roles) {}

    @Override
    public Set<String> grantsFor(TenantId tenant, Set<String> roleCodes) {
        if (roleCodes.isEmpty()) {
            return Set.of();
        }
        return cache.get(new Key(tenant, Set.copyOf(roleCodes)), this::load);
    }

    /** Forgets every cached expansion of the tenant once the current transaction commits (or now, without one). */
    public void evictAfterCommit(TenantId tenant) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(tenant);
                }
            });
        } else {
            evict(tenant);
        }
    }

    void evict(TenantId tenant) {
        cache.asMap().keySet().removeIf(key -> key.tenant().equals(tenant));
    }

    private Set<String> load(Key key) {
        // Expanded inside the transaction: the permission collections are loaded lazily.
        return Objects.requireNonNull(TenantContext.callWith(key.tenant(), () -> readOnly.execute(tx -> expand(key))));
    }

    private Set<String> expand(Key key) {
        Map<String, Role> stored =
                roles.findByCodeIn(key.roles()).stream().collect(Collectors.toMap(Role::code, Function.identity()));
        Set<String> grants = new HashSet<>();
        for (String code : key.roles()) {
            Role role = stored.get(code);
            if (role != null) {
                grants.addAll(role.effectiveGrants());
            } else {
                SystemRole.fromCode(code)
                        .filter(SystemRole::isTenantRole)
                        .ifPresent(system -> grants.addAll(system.defaultPermissions()));
            }
        }
        return Set.copyOf(grants);
    }
}

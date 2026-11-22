package com.bmp.iam.api.dto;

import com.bmp.shared.id.UserId;
import java.util.List;
import java.util.Objects;

/**
 * An identity provider account that already exists (seeded realm users, onboarding), to be given a profile.
 *
 * @param subject the account's subject, which becomes the user id
 * @param roleCodes codes of active roles of the tenant
 */
public record ExistingAccount(UserId subject, String displayName, List<String> roleCodes) {

    public ExistingAccount {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(displayName, "displayName");
        roleCodes = List.copyOf(roleCodes);
    }
}

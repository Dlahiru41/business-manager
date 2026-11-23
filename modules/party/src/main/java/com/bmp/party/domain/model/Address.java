package com.bmp.party.domain.model;

import com.bmp.shared.security.Sensitive;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.jspecify.annotations.Nullable;

/** A postal address of a party (personal data, PTY-04); every part optional. */
@Embeddable
@Sensitive
public record Address(
        @Column(name = "address_line1") @Nullable String line1,
        @Column(name = "address_line2") @Nullable String line2,
        @Column(name = "address_city") @Nullable String city,
        @Column(name = "address_district") @Nullable String district,
        @Column(name = "address_postal_code") @Nullable String postalCode,
        @Column(name = "address_country") @Nullable String country) {

    /** Whether no part is given, so nothing needs storing. */
    public boolean isEmpty() {
        return line1 == null
                && line2 == null
                && city == null
                && district == null
                && postalCode == null
                && country == null;
    }

    @Override
    public String toString() {
        return "Address[***]";
    }
}

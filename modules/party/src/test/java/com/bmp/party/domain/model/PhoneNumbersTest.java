package com.bmp.party.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.party.domain.error.PartyErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import org.junit.jupiter.api.Test;

class PhoneNumbersTest {

    private static String normalise(String typed) {
        return PhoneNumbers.normalise(typed, PhoneNumbers.DEFAULT_CALLING_CODE);
    }

    @Test
    void pty01PhonesAreStoredInE164ReadInSriLankaWithoutACountryCode() {
        assertThat(normalise("077 123 4567")).isEqualTo("+94771234567");
        assertThat(normalise("(077) 123-4567")).isEqualTo("+94771234567");
        assertThat(normalise("771234567"))
                .as("national number without trunk prefix")
                .isEqualTo("+94771234567");
        assertThat(normalise("94771234567")).as("country code without plus").isEqualTo("+94771234567");
        assertThat(normalise("0094 77 123 4567")).isEqualTo("+94771234567");
        assertThat(normalise(" +44 20 7946 0958 ")).isEqualTo("+442079460958");
    }

    @Test
    void pty01AnythingThatCannotBeAPhoneIsInvalidWithoutEchoingIt() {
        for (String typed : new String[] {"call me", "12345", "+0771234567", "+9477123456789012", "077-12a-4567"}) {
            assertThatThrownBy(() -> normalise(typed))
                    .as(typed)
                    .isInstanceOfSatisfying(BusinessRuleViolation.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(PartyErrorCode.INVALID_PHONE);
                        assertThat(e.details()).doesNotContainValue(typed);
                    });
        }
    }

    @Test
    void searchesForTheStartOfAPhoneInTheSameForm() {
        assertThat(PhoneNumbers.searchPrefix("0771", "94")).contains("+94771");
        assertThat(PhoneNumbers.searchPrefix("+4420", "94")).contains("+4420");
        assertThat(PhoneNumbers.searchPrefix("0044 20", "94")).contains("+4420");
        assertThat(PhoneNumbers.searchPrefix("771", "94")).contains("+94771");
        assertThat(PhoneNumbers.searchPrefix("94771", "94"))
                .as("a short search is read as national")
                .contains("+9494771");
        assertThat(PhoneNumbers.searchPrefix("07", "94")).as("too short").isEmpty();
        assertThat(PhoneNumbers.searchPrefix("Kegalle", "94")).isEmpty();
    }
}

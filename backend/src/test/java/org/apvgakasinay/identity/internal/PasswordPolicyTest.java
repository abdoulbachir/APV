package org.apvgakasinay.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.apvgakasinay.shared.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Security acceptance 2.1 to 2.12, the parts that need no database. */
class PasswordPolicyTest {

    private static final List<String> CONTEXT = List.of("sample.donor", "Sample Donor");

    static IdentityProperties testProperties() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        return new IdentityProperties(environment);
    }

    private final PasswordPolicy accepted = new PasswordPolicy(testProperties());

    private void refused(String password, String code) {
        assertThatThrownBy(() -> accepted.accept("password", password, CONTEXT))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo("validation_error");
                    assertThat(error.fieldName()).isEqualTo("password");
                    assertThat(error.fieldCode()).isEqualTo(code);
                });
    }

    @Test
    void acceptance_2_1_lengthBounds() {
        refused("x".repeat(14), "too_short");
        assertThat(accepted.accept("password", "k".repeat(7) + "j".repeat(8), CONTEXT)).hasSize(15);
        assertThat(accepted.accept("password", "kj".repeat(64), CONTEXT)).hasSize(128);
        refused("kj".repeat(64) + "k", "too_long");
    }

    @Test
    void acceptance_2_2_lengthIsCountedInCodePoints() {
        String emoji = "🌍😀🌎".repeat(5);
        assertThat(emoji.length()).isEqualTo(30);
        assertThat(accepted.accept("password", emoji, CONTEXT)).isEqualTo(emoji);
    }

    @Test
    void acceptance_2_3_spacesAreKeptExactly() {
        String spaced = "  leading and trailing  ";
        assertThat(accepted.accept("password", spaced, CONTEXT)).isEqualTo(spaced);
    }

    @Test
    void acceptance_2_4_composedAndDecomposedFormsBecomeTheSamePassword() {
        String composed = "café au lait s'il vous plaît";
        String decomposed = "café au lait s'il vous plaît";
        assertThat(accepted.accept("password", decomposed, CONTEXT)).isEqualTo(accepted.accept("password", composed, CONTEXT));
    }

    @Test
    void acceptance_2_5_compatibilityCharactersStayDistinct() {
        assertThat(accepted.accept("password", "ofﬁce passphrase 2027", CONTEXT)).isNotEqualTo("office passphrase 2027");
    }

    @Test
    void acceptance_2_6_minimumIsMeasuredAfterNormalization() {
        // 16 code points as sent, 14 after NFC composes the two accents.
        String shrinking = "é" + "ô" + "abcdefghijkl";
        assertThat(PasswordPolicy.codePoints(shrinking)).isEqualTo(16);
        assertThat(PasswordPolicy.codePoints(PasswordPolicy.normalize(shrinking))).isEqualTo(14);
        refused(shrinking, "too_short");
    }

    @Test
    void acceptance_2_7_maximumAsSentIsCheckedFirst() {
        String shrinking = "é" + "kj".repeat(63) + "k";
        assertThat(PasswordPolicy.codePoints(shrinking)).isEqualTo(129);
        assertThat(PasswordPolicy.codePoints(PasswordPolicy.normalize(shrinking))).isEqualTo(128);
        refused(shrinking, "too_long");
    }

    @Test
    void acceptance_2_9_blockedPasswords() {
        refused("passwordpassword", "compromised");
        refused("123456789012345", "compromised");
        refused("correct horse battery staple", "compromised");
    }

    @Test
    void acceptance_2_10_passwordsBuiltFromTheContext() {
        refused("apv-gakasinay-2026", "compromised");
        refused("sample.donor123456", "compromised");
        refused("Sample Donor Sample Donor", "compromised");
        refused("APV GAKASINAY apv gakasinay", "compromised");
    }

    @Test
    void acceptance_2_11_contextWordWithEnoughElseIsAccepted() {
        assertThat(accepted.accept("password", "gakasinay helps my neighbours every winter", CONTEXT)).isNotBlank();
        assertThat(PasswordPolicy.builtFromContext("a river of quiet apvelocity", List.of())).isFalse();
    }

    @Test
    void acceptance_2_12_noCompositionRules() {
        assertThat(accepted.accept("password", "kjhgfdsazxcvbnm", CONTEXT)).isEqualTo("kjhgfdsazxcvbnm");
    }

    @Test
    void shortContextWordsAreIgnored() {
        assertThat(PasswordPolicy.builtFromContext("bobbobbobbobbobbob", List.of("bob"))).isFalse();
        assertThat(PasswordPolicy.builtFromContext("bobbybobbybobby1", List.of("bobby"))).isTrue();
    }
}

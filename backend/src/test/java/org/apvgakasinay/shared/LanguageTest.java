package org.apvgakasinay.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LanguageTest {

    @Test
    void missingOrUnsupportedMeansEnglish() {
        assertThat(Language.negotiate(null)).isEqualTo(Language.EN);
        assertThat(Language.negotiate("")).isEqualTo(Language.EN);
        assertThat(Language.negotiate("de, es;q=0.8")).isEqualTo(Language.EN);
        assertThat(Language.negotiate("*")).isEqualTo(Language.EN);
        assertThat(Language.negotiate("not a header;;;")).isEqualTo(Language.EN);
    }

    @Test
    void regionSubtagsAreIgnored() {
        assertThat(Language.negotiate("fr-CA")).isEqualTo(Language.FR);
        assertThat(Language.negotiate("en-GB")).isEqualTo(Language.EN);
        assertThat(Language.negotiate("FR-fr")).isEqualTo(Language.FR);
    }

    @Test
    void qualityValuesDecide() {
        assertThat(Language.negotiate("en;q=0.5, fr;q=0.9")).isEqualTo(Language.FR);
        assertThat(Language.negotiate("fr;q=0.2, en;q=0.8")).isEqualTo(Language.EN);
        assertThat(Language.negotiate("de, fr;q=0.7, en;q=0.3")).isEqualTo(Language.FR);
        assertThat(Language.negotiate("fr;q=0, en")).isEqualTo(Language.EN);
    }

    @Test
    void fallbackReportsTheLanguageActuallyReturned() {
        assertThat(Localized.pick(Language.FR, "hello", "bonjour")).isEqualTo(new Localized("bonjour", "fr"));
        assertThat(Localized.pick(Language.FR, "hello", null)).isEqualTo(new Localized("hello", "en"));
        assertThat(Localized.pick(Language.EN, null, "bonjour")).isEqualTo(new Localized("bonjour", "fr"));
        assertThat(Localized.pick(Language.EN, null, null)).isEqualTo(Localized.NONE);
    }
}

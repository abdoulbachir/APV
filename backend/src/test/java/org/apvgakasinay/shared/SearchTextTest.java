package org.apvgakasinay.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SearchTextTest {

    @Test
    void removesAccentsAndCase() {
        assertThat(SearchText.normalize("Rentrée")).isEqualTo("rentree");
        assertThat(SearchText.normalize("ÉCOLE Française")).isEqualTo("ecole francaise");
        assertThat(SearchText.normalize("Ünïcödé")).isEqualTo("unicode");
    }

    @Test
    void handlesPrecomposedAndDecomposedInputTheSameWay() {
        assertThat(SearchText.normalize("é")).isEqualTo(SearchText.normalize("é")).isEqualTo("e");
    }

    @Test
    void appliesNoFoldingBeyondTheContract() {
        assertThat(SearchText.normalize("cœur")).isEqualTo("cœur");
        assertThat(SearchText.normalize("straße")).isEqualTo("straße");
    }

    @Test
    void lowerCasesWithoutLocaleRules() {
        // Turkish dotted capital I decomposes to I + U+0307; the mark is removed, then "I" becomes "i".
        assertThat(SearchText.normalize("İ")).isEqualTo("i");
        assertThat(SearchText.normalize("TITLE")).isEqualTo("title");
    }

    @Test
    void trimsExactlyTheEcmaScriptWhiteSpaceSet() {
        String everyEdgeCharacter = "\t\n\u000B\f\r                  　﻿";
        assertThat(SearchText.trim(everyEdgeCharacter + "a b" + everyEdgeCharacter)).isEqualTo("a b");
        // Not in the set: zero-width space, and the "next line" control.
        assertThat(SearchText.trim("​a​")).isEqualTo("​a​");
        assertThat(SearchText.trim("\u0085a")).isEqualTo("\u0085a");
    }

    @Test
    void keepsWhiteSpaceInsideTheText() {
        assertThat(SearchText.normalize("  sample   household  ")).isEqualTo("sample   household");
    }

    @Test
    void countsCodePointsNotUtf16Units() {
        String astral = "😀";
        assertThat(astral.length()).isEqualTo(2);
        assertThat(SearchText.codePoints(astral.repeat(80))).isEqualTo(80);
    }

    @Test
    void treatsMissingTextAsEmpty() {
        assertThat(SearchText.normalize(null)).isEmpty();
    }
}

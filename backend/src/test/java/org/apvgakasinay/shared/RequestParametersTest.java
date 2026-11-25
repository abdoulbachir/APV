package org.apvgakasinay.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.apvgakasinay.cases.CaseQuery;
import org.junit.jupiter.api.Test;

class RequestParametersTest {

    @Test
    void defaults() {
        CaseQuery query = CaseQuery.from(Map.of());
        assertThat(query.statuses()).containsExactly("active", "funded");
        assertThat(query.sort()).isEqualTo(CaseQuery.Sort.NEWEST);
        assertThat(query.category()).isNull();
        assertThat(query.normalizedSearch()).isEmpty();
        assertThat(query.page()).isEqualTo(new PageRequest(1, 12));
    }

    @Test
    void completedMeansDeliveredAndClosed() {
        assertThat(CaseQuery.from(Map.of("status", "completed")).statuses()).containsExactly("delivered", "closed");
    }

    @Test
    void pageBounds() {
        assertThat(PageRequest.from(Map.of("page", "100000", "pageSize", "48"))).isEqualTo(new PageRequest(100000, 48));
        assertThat(new PageRequest(3, 12).offset()).isEqualTo(24);
        assertInvalid(Map.of("page", "0"), "page", "out_of_range");
        assertInvalid(Map.of("page", "100001"), "page", "out_of_range");
        assertInvalid(Map.of("pageSize", "0"), "pageSize", "out_of_range");
        assertInvalid(Map.of("pageSize", "49"), "pageSize", "out_of_range");
        assertInvalid(Map.of("pageSize", "twelve"), "pageSize", "invalid_type");
        assertInvalid(Map.of("page", "1.5"), "page", "invalid_type");
        assertInvalid(Map.of("page", "99999999999999999999"), "page", "invalid_type");
    }

    @Test
    void unknownEnumerationValuesAreRejected() {
        assertInvalid(Map.of("status", "draft"), "status", "invalid_value");
        assertInvalid(Map.of("sort", "random"), "sort", "invalid_value");
        assertInvalid(Map.of("category", "Not An Id"), "category", "invalid_value");
    }

    @Test
    void searchLengthIsCountedInCodePointsBeforeNormalization() {
        String astral = "😀";
        assertThat(CaseQuery.from(Map.of("q", astral.repeat(80))).normalizedSearch()).hasSize(160);
        assertThat(CaseQuery.from(Map.of("q", " ".repeat(60) + "a".repeat(80) + " ".repeat(60))).normalizedSearch()).hasSize(80);
        assertInvalid(Map.of("q", "a".repeat(81)), "q", "too_long");
        assertInvalid(Map.of("q", astral.repeat(81)), "q", "too_long");
        assertInvalid(Map.of("q", " ".repeat(201)), "q", "too_long");
        // "é" as e + combining mark is 2 code points each: 41 of them is 82, over the limit, although it normalizes to 41.
        assertInvalid(Map.of("q", "é".repeat(41)), "q", "too_long");
    }

    @Test
    void searchThatIsOnlyWhiteSpaceAppliesNoFilter() {
        assertThat(CaseQuery.from(Map.of("q", "  \t ")).normalizedSearch()).isEmpty();
    }

    @Test
    void totalPagesIsAtLeastOne() {
        assertThat(Page.of(List.of(), new PageRequest(1, 12), 0).totalPages()).isEqualTo(1);
        assertThat(Page.of(List.of(), new PageRequest(1, 12), 12).totalPages()).isEqualTo(1);
        assertThat(Page.of(List.of(), new PageRequest(1, 12), 13).totalPages()).isEqualTo(2);
        assertThat(Page.of(List.of(), new PageRequest(1, 12), 200).totalPages()).isEqualTo(17);
    }

    private static void assertInvalid(Map<String, String> query, String field, String code) {
        assertThatThrownBy(() -> CaseQuery.from(query))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo("validation_error");
                    assertThat(error.fieldName()).isEqualTo(field);
                    assertThat(error.fieldCode()).isEqualTo(code);
                });
    }
}

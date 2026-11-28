package org.apvgakasinay.donations.internal;

import java.util.List;
import org.apvgakasinay.donations.internal.PaymentMethodRepository.Localized;
import org.apvgakasinay.donations.internal.PaymentMethodRepository.RecipientField;

/**
 * The responses of the giving and donation review operations, exactly as the contract's schemas
 * define them. Each is built field by field from named values; none is a serialized row.
 *
 * <p>The ones that carry a secret or a donor's private details print nothing of their contents:
 * frameworks print the object they are about to write when logging at DEBUG.
 */
public final class Views {

    private Views() {
    }

    public record PaymentMethod(String id, Localized labels, int position, String detailsId, Localized instructions, List<RecipientField> recipient) {
    }

    public record PaymentMethodList(List<PaymentMethod> methods) {
    }

    public record EvidenceUpload(String token, String expiresAt, int maxBytes, List<String> formats) {

        @Override
        public String toString() {
            return "EvidenceUpload[not shown]";
        }
    }

    public record Receipt(String reference, String trackingCode, String state, String caseCode, long amount, String reportedAt,
                          EvidenceUpload evidenceUpload) {

        @Override
        public String toString() {
            return "Receipt[not shown]";
        }
    }

    public record Status(String reference, String state, String caseCode, long reportedAmount, String reportedAt, String decidedAt,
                         Long confirmedAmount, String confirmedCaseCode, Long netAmount, String rejectionReason, boolean evidenceAttached) {
    }

    public record StaffActor(String id, String displayName) {
    }

    public record ShownDetails(String detailsId, int revision, boolean current, String supersededAt, List<RecipientField> recipient) {
    }

    public record MethodRef(String id, String description) {
    }

    public record Reported(String caseCode, long amount, String paidOn, String at) {
    }

    public record Decision(String state, String at, StaffActor by, String caseCode, Long amount, String receivedOn,
                           String providerReference, String listing, String rejectionReason, String note) {

        @Override
        public String toString() {
            return "Decision[not shown]";
        }
    }

    public record Correction(String ledgerEntryId, String kind, long amount, String at, String reason, String providerReference) {
    }

    public record HistoryEntry(String at, String action, StaffActor by) {
    }

    public record DuplicateHint(String id, String reference, String state, long amount, String reportedAt) {
    }

    public record DisplayState(String mode, String publicName) {

        @Override
        public String toString() {
            return "DisplayState[not shown]";
        }
    }

    public record ListItem(String id, String reference, String origin, String state, String caseCode, Long reportedAmount,
                           Long confirmedAmount, Long netAmount, String methodId, String payerName, String reportedAt, String decidedAt,
                           String evidence, List<String> flags) {

        @Override
        public String toString() {
            return "ListItem[not shown]";
        }
    }

    public record DonationPage(List<ListItem> items, int page, int pageSize, long totalItems, long totalPages, long pendingCount) {

        @Override
        public String toString() {
            return "DonationPage[not shown]";
        }
    }

    public record Detail(String id, String reference, String origin, String state, Reported reported, MethodRef method,
                         ShownDetails shownDetails, String payerName, String paymentReference, String contact, DisplayState display,
                         boolean linkedAccount, String evidence, List<String> flags, Decision decision, String ledgerEntryId,
                         Long netAmount, List<Correction> corrections, String reentryOf, String reenteredAs,
                         List<DuplicateHint> possibleDuplicates, List<HistoryEntry> history, String privateDetailsErasedAt) {

        @Override
        public String toString() {
            return "Detail[not shown]";
        }
    }
}

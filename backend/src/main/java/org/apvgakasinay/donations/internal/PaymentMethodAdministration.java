package org.apvgakasinay.donations.internal;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apvgakasinay.audit.AuditService;
import org.apvgakasinay.audit.AuditService.AuditEvent;
import org.apvgakasinay.audit.AuditService.Outcome;
import org.apvgakasinay.donations.internal.PaymentMethodRepository.Details;
import org.apvgakasinay.donations.internal.PaymentMethodRepository.Localized;
import org.apvgakasinay.donations.internal.PaymentMethodRepository.Method;
import org.apvgakasinay.donations.internal.PaymentMethodRepository.RecipientField;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Managing payment methods (contract/donation-lifecycle.md, 2.5). Used by the operator command
 * only: in milestone 2 there is no network operation that changes a method or a recipient
 * detail, because whoever can change a recipient detail can redirect every future gift.
 *
 * <p>Every change is one transaction with its audit record, which names the operating-system
 * user who ran the command.
 */
@Service
@Transactional
public class PaymentMethodAdministration {

    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9_]{1,31}$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A refusal the operator must read. */
    public static class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Refused(String message) {
            super(message);
        }
    }

    /** Who checked the provider's eligibility rules, when, and what was checked. An attestation, not proof. */
    public record Attestation(String checkedBy, LocalDate checkedOn, String notes) {
    }

    /** The content of a details version before it is published. */
    public record Definition(Localized instructions, List<RecipientField> recipient) {
    }

    private final PaymentMethodRepository methods;
    private final AuditService audit;
    private final DonationClock clock;
    private final DonationSettings settings;

    public PaymentMethodAdministration(PaymentMethodRepository methods, AuditService audit, DonationClock clock, DonationSettings settings) {
        this.methods = methods;
        this.audit = audit;
        this.clock = clock;
        this.settings = settings;
    }

    /** Creates a method, inactive. Its identifier never changes and is never reused. */
    public void create(String id, String nameEn, String nameFr, int position, String operator) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new Refused("the identifier must be 2 to 32 lower-case letters, digits or underscores, starting with a letter");
        }
        name(nameEn, "the English name");
        name(nameFr, "the French name");
        if (position < 1) {
            throw new Refused("the position must be 1 or more");
        }
        if (methods.positionTaken(position)) {
            throw new Refused("another method already has position " + position);
        }
        if (!methods.insertMethod(id, nameEn, nameFr, position, clock.now())) {
            throw new Refused("a method with that identifier already exists; an identifier is never reused");
        }
        record("payment_method.created", operator, Map.of("method", id, "position", String.valueOf(position)));
    }

    /**
     * Publishes a new version of a method's recipient details. The current version, if any, is
     * superseded at the same instant, so there is exactly one current version at all times.
     *
     * @return the revision number of the new version
     */
    public int publishDetails(String methodId, Definition definition, String operator) {
        Method method = methods.lockMethod(methodId).orElseThrow(() -> new Refused("there is no payment method " + methodId));
        validate(definition);
        Instant now = clock.now();
        Details current = methods.currentDetails(method.id()).orElse(null);
        int revision = current == null ? 1 : current.revision() + 1;
        if (current != null) {
            methods.supersede(current.id(), now);
        }
        String publicId = Codes.publicId("pmd_");
        methods.insertDetails(publicId, method.id(), revision, definition.instructions(), definition.recipient(), now);
        record("payment_method.details_published", operator,
                Map.of("method", method.id(), "details", publicId, "revision", String.valueOf(revision)));
        return revision;
    }

    /**
     * Activates a method that has a details version. Outside the dev and test profiles the
     * eligibility attestation is required: the site cannot check a provider's terms, it records
     * that a person did (decision 4).
     */
    public void activate(String methodId, Attestation attestation, String operator) {
        Method method = methods.lockMethod(methodId).orElseThrow(() -> new Refused("there is no payment method " + methodId));
        if (methods.currentDetails(method.id()).isEmpty()) {
            throw new Refused("publish the recipient details of " + method.id() + " before activating it");
        }
        boolean attested = attestation != null && filled(attestation.checkedBy()) && attestation.checkedOn() != null && filled(attestation.notes());
        if (!attested && !settings.development()) {
            throw new Refused("a method cannot be activated without the eligibility attestation: who checked the provider's rules, on which date, and what was checked");
        }
        if (attested && (attestation.checkedBy().length() > 120 || attestation.notes().length() > 1000)) {
            throw new Refused("the attestation is too long: at most 120 characters for who checked and 1000 for what was checked");
        }
        if (attested && attestation.checkedOn().isAfter(LocalDate.ofInstant(clock.now(), java.time.ZoneOffset.UTC).plusDays(1))) {
            throw new Refused("the date of the eligibility check cannot be in the future");
        }
        methods.activate(method.id(), attested ? attestation.checkedBy() : null, attested ? attestation.checkedOn() : null,
                attested ? attestation.notes() : null);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("method", method.id());
        details.put("attested", String.valueOf(attested));
        if (attested) {
            details.put("eligibility_checked_by", attestation.checkedBy());
            details.put("eligibility_checked_on", attestation.checkedOn().toString());
            details.put("eligibility_notes", attestation.notes());
        }
        record("payment_method.activated", operator, details);
    }

    /** Deactivates a method. Gifts already reported keep their reference to it. */
    public void deactivate(String methodId, String operator) {
        Method method = methods.lockMethod(methodId).orElseThrow(() -> new Refused("there is no payment method " + methodId));
        if (!method.active()) {
            throw new Refused(method.id() + " is not active");
        }
        methods.deactivate(method.id(), clock.now());
        record("payment_method.deactivated", operator, Map.of("method", method.id()));
    }

    @Transactional(readOnly = true)
    public List<Method> list() {
        return methods.methods();
    }

    @Transactional(readOnly = true)
    public List<Details> versions(String methodId) {
        return methods.allDetails(methodId);
    }

    @Transactional(readOnly = true)
    public Details current(String methodId) {
        return methods.currentDetails(methodId).orElse(null);
    }

    @Transactional(readOnly = true)
    public boolean exists(String methodId) {
        return methods.method(methodId).isPresent();
    }

    private void record(String type, String operator, Map<String, String> details) {
        audit.recordWithin(AuditEvent.of(type, Outcome.SUCCESS).operator(operator).details(details));
    }

    // ------------------------------------------------------------------ the details definition

    /**
     * Reads a details definition: a JSON object with {@code instructions} ({@code en}, {@code fr})
     * and {@code recipient}, one to six fields, each with {@code labels} ({@code en}, {@code fr}),
     * a {@code value} and a {@code format} ({@code text} or {@code link}).
     */
    @SuppressWarnings("unchecked")
    public static Definition parse(String json) {
        Map<String, Object> root;
        try {
            root = JSON.readValue(json, Map.class);
        } catch (RuntimeException unreadable) {
            throw new Refused("the file is not a JSON object");
        }
        if (root == null || !Set.of("instructions", "recipient").equals(root.keySet())) {
            throw new Refused("the definition has exactly two properties: instructions and recipient");
        }
        if (!(root.get("recipient") instanceof List<?> items)) {
            throw new Refused("recipient must be a list of fields");
        }
        List<RecipientField> fields = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> field) || !Set.of("labels", "value", "format").equals(field.keySet())) {
                throw new Refused("each recipient field has exactly: labels, value and format");
            }
            if (!(field.get("value") instanceof String value) || !(field.get("format") instanceof String format)) {
                throw new Refused("the value and the format of a recipient field are text");
            }
            fields.add(new RecipientField(localized(field.get("labels"), "the labels of a recipient field"), value, format));
        }
        Definition definition = new Definition(localized(root.get("instructions"), "instructions"), fields);
        validate(definition);
        return definition;
    }

    private static Localized localized(Object value, String what) {
        if (!(value instanceof Map<?, ?> both) || !Set.of("en", "fr").equals(both.keySet())
                || !(both.get("en") instanceof String en) || !(both.get("fr") instanceof String fr)) {
            throw new Refused(what + " must give the text in both languages: en and fr");
        }
        return new Localized(en, fr);
    }

    static void validate(Definition definition) {
        text(definition.instructions().en(), 2000, "the English instructions");
        text(definition.instructions().fr(), 2000, "the French instructions");
        if (definition.recipient().isEmpty() || definition.recipient().size() > 6) {
            throw new Refused("a method shows one to six recipient fields");
        }
        for (RecipientField field : definition.recipient()) {
            text(field.labels().en(), 60, "the English label of a recipient field");
            text(field.labels().fr(), 60, "the French label of a recipient field");
            text(field.value(), 200, "the value of a recipient field");
            if (field.format().equals("link")) {
                link(field.value());
            } else if (!field.format().equals("text")) {
                throw new Refused("the format of a recipient field is text or link");
            }
        }
    }

    /** A link is an https address without user name or password (2.1). */
    private static void link(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (java.net.URISyntaxException invalid) {
            throw new Refused("a link value must be a valid https address");
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null) {
            throw new Refused("a link value must be an https address without a user name or a password");
        }
    }

    private static void name(String value, String what) {
        text(value, 40, what);
    }

    private static void text(String value, int max, String what) {
        if (value == null || value.isBlank()) {
            throw new Refused(what + " is missing");
        }
        if (value.codePointCount(0, value.length()) > max) {
            throw new Refused(what + " is longer than " + max + " characters");
        }
        if (!value.equals(value.strip()) || value.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw new Refused(what + " has surrounding white space or a control character");
        }
    }

    private static boolean filled(String value) {
        return value != null && !value.isBlank();
    }
}

# Public pictures and privacy

Rules for every picture address and every public response in `openapi.yaml`.
Uploading and storing pictures is built in a later milestone. Nothing here is implemented yet.

## 1. Picture addresses

Every picture address in a public response is a `MediaPath`:

```
/media/public/<token>/<variant>.<format>
```

- It is a path on the site's own origin. It is never an absolute URL, so a response can never make a visitor's browser load a picture from another site.
- `<token>` is at least 22 random URL-safe characters (128 bits or more). It is not the case code, not a counter and not derived from the file, so addresses cannot be guessed or listed.
- `<variant>` is the size, for example `display` or `thumb`.
- The file served is a re-encoded copy made by the server. The original upload, its file name and its embedded data (location, camera, date) are never served.

The schema pattern enforces the shape. `npm run validate` rejects a response with an absolute URL, a private route or a short token.

## 2. What the public route serves

`GET /media/public/<token>/<variant>` answers only when all of these are true at the moment of the request:

1. The token belongs to a picture of a public kind: profile picture, case cover, case gallery, update picture.
2. The picture is approved for publication and its consent, where one is required, is recorded and not withdrawn.
3. Its owner is public: the case is not a draft, or the person has agreed to show their profile picture.

Otherwise the answer is 404, the same as for a token that never existed. Draft pictures, withdrawn pictures, private supporting documents, payment screenshots and disbursement evidence have no public token at all; they are reachable only through authenticated routes.

## 3. Caching

| Response | Header | Reason |
|---|---|---|
| Public pictures | `Cache-Control: public, max-age=300` | Five minutes keeps pages fast and bounds how long a withdrawn picture can linger in a cache. |
| Public API responses | `Cache-Control: no-cache` with an `ETag` | Totals must be current. The browser may reuse a response only after checking with the server. |
| Private files and authenticated responses | `Cache-Control: no-store` | Never written to a shared or disk cache. |

## 4. Withdrawing consent

When consent for a picture is withdrawn, or a case returns to private handling:

1. The server stops serving the picture at once. The address answers 404 from the next request.
2. The token is retired and never reused. If the picture is published again later, it gets a new token.
3. Browser and proxy caches can keep showing their copy for up to five minutes.

What this cannot do, and must not be claimed to anyone:

- It does not remove copies that were already downloaded, saved or captured in a screenshot.
- It does not remove copies held by third parties, such as a messaging application that fetched a preview.
- Changing an address does not revoke anything by itself. Only the server refusing to serve the file does, and only for future requests.

Administrators must be told this before they publish a picture of a person, which is why consent is recorded first.

## 5. Public responses contain only public fields

- Every object schema in `openapi.yaml` is closed with `additionalProperties: false`. A server response carrying an extra property fails contract tests.
- `npm run validate` fails if a public schema gains a property whose name suggests private data (real name, contact, email, phone, notes, visibility, consent and similar), if a picture property does not use `MediaPath`, or if `draft` enters the public status list.
- The backend builds each public response by copying named fields into a dedicated public type. It never serializes a database row or an internal object. This is the server-side allowlist; the closed schemas are its test.
- Clients still ignore properties they do not know, so a later contract version can add fields.

## 6. Donors and authors

| Donation visibility | Ledger | Donor list | Name | Picture |
|---|---|---|---|---|
| Named | Counted | Listed | Shown | Shown only with the donor's separate consent to show their picture |
| Anonymous | Counted | Listed | Null | Never |
| Hidden | Counted | Not listed | Never | Never |

Agreeing to be named and agreeing to show a profile picture are two independent choices. An administrator's picture beside an update follows the same rule.

## 7. Requirements carried to later milestones

Agreed and unchanged. Their upload endpoints and storage are not part of milestone 0.

- Users and administrators can have a profile picture.
- A case has one cover picture and up to eight additional public gallery pictures.
- Every public picture has a description for screen readers, in English, French or both.
- A picture of a beneficiary is published only after consent is recorded: date, scope, the administrator who attests it, and whether a guardian consented. The record is an attestation by the administrator, not proof.
- Private supporting evidence is a separate kind of file that is never public.
- How long payment screenshots are kept is an open owner decision. No period is assumed.

## 8. Fixtures

The full fixture file contains drafts, hidden donors and private markers on purpose, so leak tests have something to look for. The frontend must not bundle it. It bundles the public projection described in `fixtures/README.md`.

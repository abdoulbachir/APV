# The breached-password list

Owner decision of 2026-11-25 (`docs/decisions.md`): outside the `dev` and `test` profiles the backend must have a comprehensive local list of breached passwords, and must refuse to start without one. This page says which list, how it is turned into the file the backend reads, how its integrity is checked, what it costs, and how it is updated.

Nothing here contacts another service when a password is checked. The list is downloaded once, as a whole, like any other data file; after that every check is a search in a local file. No password and no hash of a password ever leaves the server.

## What the backend requires

`APV_PASSWORD_BLOCKLIST_PATH` names a file in the format below. The server and the operator command both check it at every start and refuse to start, saying why, when any of these is true:

| Refused when | Why it matters |
|---|---|
| The variable is not set | The small list bundled with the application is for development only |
| The file or its manifest is missing or cannot be read | |
| The manifest does not declare the format `apv-sha1-sorted-v1`, or holds no entry count or SHA-256 | A file of something else |
| The file is not exactly `entries` × 20 bytes | Truncated or appended to |
| It holds fewer than 100,000,000 entries | Not comprehensive: a list of "common passwords" has thousands or a few million |
| Entries are not in ascending order (checked at 4,096 places and both ends) | The search would silently miss entries |
| It does not contain the hashes of `password`, `123456`, `qwerty` and `iloveyou` | Every real breach corpus contains them; their absence means the wrong data or the wrong hash |
| Its SHA-256 differs from the manifest (server only) | Damaged or modified since it was built |

The operator command makes every check except the last one, which reads the whole file; it evaluates no password itself. The server makes all of them at every start.

The figure of 100,000,000 is the implementation's definition of "comprehensive", chosen so that the corpus named below passes with a wide margin and no list of common passwords does. It is a constant in the code (`BreachedPasswords.MIN_PRODUCTION_ENTRIES`), not a setting, so it cannot be lowered by configuration.

In the `dev` and `test` profiles the variable is optional and the bundled starter list alone is accepted. A list configured there is still checked for format, size, order and SHA-256, at any number of entries.

## Selected source and provenance

**Pwned Passwords**, published by Have I Been Pwned (Troy Hunt): the SHA-1 hashes of passwords found in publicly known data breaches, each with the number of times it was seen. It is the corpus NIST-style "compare against known breached passwords" checks are commonly built on, it is published for exactly this use, it is distributed as hashes rather than passwords, and it is updated as new breaches are added.

It is obtained with the project's own open-source tool, `PwnedPasswordsDownloader` (https://github.com/HaveIBeenPwned/PwnedPasswordsDownloader), which fetches the whole corpus from the official service over HTTPS and writes it as text, one line per hash: forty hexadecimal characters, a colon, and the count. Follow that project's README for the current command; choose the SHA-1 form and a single output file.

Run the download on any machine with a few tens of gigabytes free. It does not have to be the server. Record in the deployment notes: the date of the download, the version of the downloader, and the SHA-256 the build step prints (next section). The source line written into the manifest should say the same, for example `Have I Been Pwned Pwned Passwords, SHA-1, downloaded 2026-12-01 with PwnedPasswordsDownloader 0.x`.

Another corpus can be used if it is at least as comprehensive and is delivered or converted to sorted SHA-1 hashes. The requirements above apply to it unchanged.

## Format

`apv-sha1-sorted-v1`: the SHA-1 of each password (of its UTF-8 bytes), 20 bytes each, in ascending order as unsigned bytes, no duplicates, no header, nothing else. So the file is exactly 20 bytes times the number of entries.

Beside it, `<file>.manifest`, a small text file:

```
format=apv-sha1-sorted-v1
entries=<number of entries>
sha256=<SHA-256 of the binary file, hexadecimal>
source=<where the data came from and when>
built=<when the file was built>
```

A password being chosen is put in Unicode NFC form, as it would be before hashing for storage, its SHA-1 is computed in memory, and the file is searched by binary search: about thirty reads of 20 bytes each. A match is refused with the field code `compromised`, exactly like a password on the bundled list. If the file cannot be read at that moment the request fails with an error; it is never treated as "not breached".

## Building the file

The backend's own jar converts the downloaded text. This needs no database and no network.

```bash
java -jar apv-backend.jar blocklist build pwnedpasswords.txt /var/lib/apv/breached-sha1.bin \
     "Have I Been Pwned Pwned Passwords, SHA-1, downloaded 2026-12-01 with PwnedPasswordsDownloader 0.x"
```

It reads one hash per line (anything after a colon is ignored), refuses a line that is not a hash, and refuses input that is not strictly ascending, which is what the binary search depends on. If the download is not in order, sort it first: `LC_ALL=C sort -u pwnedpasswords.txt | java -jar apv-backend.jar blocklist build - ...` (`-` reads standard input). It writes the binary file and the manifest, then verifies the result against the production requirements and prints the number of entries.

## Integrity verification

Three layers:

1. **Transport.** The download is made by the publisher's tool from the publisher's service over HTTPS.
2. **Build.** The manifest records the SHA-256 of the file as built. Copy that value into the deployment notes, kept apart from the server, so a later replacement of both file and manifest can be noticed.
3. **Every start, and on demand.** The server recomputes the SHA-256 at start-up and refuses a mismatch. At any time:
   ```bash
   java -jar apv-backend.jar blocklist verify /var/lib/apv/breached-sha1.bin
   ```
   prints `Suitable for production: ...` or `Refused: ...` with the reason, and exits 0 or 1.

Store the file and its manifest readable by the backend's user and writable only by the administrator.

## Size, memory and time

- **Disk:** 20 bytes per entry. The corpus has grown with every release; count on the order of a billion entries, so roughly 20 GB, plus the downloaded text (about twice that) until the build is done and the text is deleted. The build prints the exact numbers.
- **Memory:** none to speak of. The file is not loaded; opening it adds a few hundred kilobytes of heap. The operating system's file cache makes repeated searches fast and gives the memory back under pressure.
- **Per password check:** tens of microseconds, against roughly 25 milliseconds for the Argon2id hash that follows.
- **Start-up:** the SHA-256 of the whole file is computed once per start. Measured with a 1.9 GB list of 100,000,005 entries: about one second with the file in cache; expect ten times that per 20 GB, and more when it must come from a slow disk. The operator command skips this step and starts in milliseconds.

The CI run publishes these measurements as the evidence item `breached-password-list`.

## Updating

New breaches are added to the corpus over time. Update on a schedule the owner sets (a few times a year is usual), and after any large, widely reported breach.

1. Download the current corpus as above.
2. Build it to a **new** file name, for example `breached-sha1-2027-03.bin`. The build verifies it.
3. Record its SHA-256, entry count, date and downloader version in the deployment notes.
4. Point `APV_PASSWORD_BLOCKLIST_PATH` at the new file and restart the backend. If the new file is refused the backend does not start and says why; point back at the previous file, which is still in place, and investigate.
5. When the backend runs on the new file, delete the old file, its manifest and the downloaded text.

Do not overwrite the file in use: the running server holds it open, and a partly written file would be refused at the next start.

An update never invalidates existing passwords. The list is consulted when a password is chosen: at sign-up, password change and when a password is set with a one-time credential.

## What is in the repository

The format, the builder, the verifier and the tests. No breach data: the comprehensive list is never committed. The tests that start the application as production does generate a synthetic list of the required size (evenly spread values that are the hash of nothing, plus the hashes of the four best-known passwords and one fictional passphrase), so the production requirements are exercised without any shortcut and without any real breach data.

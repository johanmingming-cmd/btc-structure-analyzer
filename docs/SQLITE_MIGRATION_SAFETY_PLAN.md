# Prediction Lab SQLite Migration — Safety Plan

Status: design/audit only. No SQLite migration is implemented by this document.

## Current storage contract

- The journal is stored as one JSON array in SharedPreferences:
  - file: `btc_structure`
  - key: `signal_journal_v1`
- Records are ordered newest first.
- Writes retain at most 2,000 records.
- A record contains identity/time/symbol/direction/price/levels, four evaluation horizons (10m/15m/30m/60m), evaluation timestamps and sources, trade outcome metadata, and cohort.
- Older records may omit newer optional fields; parser defaults them (for example, cohort defaults to `LEGACY`).
- Existing export code reads `List<SignalRecord>`; preserve this API at the storage boundary.
- `@Synchronized` currently serializes mutations only within this app process. It is not a cross-process lock.

## Findings that affect migration

1. A row keyed only by `SignalRecord.id` is not a safe migration key. The current ID is generated from symbol, direction, and millisecond timestamp; the existing journal has not established a database-enforced uniqueness invariant. A primary key on the ID could reject a legacy duplicate and lose data.
2. The journal's order is meaningful. Do not reconstruct ordering from timestamps because timestamps can tie. Store an explicit ordinal and read with `ORDER BY ordinal ASC`.
3. The existing SharedPreferences value must remain intact as a backup after migration. Do not clear or rewrite it during initial migration.
4. A malformed backup must stop migration. Never treat a parse failure as an empty journal and never mark migration complete after a failed parse.
5. The existing parser currently validates object entries and required symbol/price, but support/resistance arrays and malformed optional values still have permissive filtering/default behavior. Before using it as a lossless migration validator, define and test strict handling for malformed nested values without rejecting legitimate historical optional-field omissions.
6. JVM unit tests currently exercise the JSON parser, not Android SQLite transactions. Passing those tests alone cannot prove migration rollback or persistence behavior.

## Proposed schema (not implemented)

Use Android's built-in SQLite APIs; no third-party database dependency is required.

```sql
CREATE TABLE signal_records (
    ordinal INTEGER PRIMARY KEY NOT NULL,
    record_id TEXT NOT NULL,
    payload_json TEXT NOT NULL
);
CREATE INDEX idx_signal_records_record_id ON signal_records(record_id);

CREATE TABLE journal_meta (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
);
```

Store the complete serialized record payload to avoid a lossy field-by-field schema conversion. `ordinal` is the primary key; `record_id` is indexed but deliberately not unique, preserving any duplicate IDs already present. A future normalized schema would require a separately tested versioned migration.

## One-time migration transaction

1. Open/create the SQLite database and verify its schema.
2. If the migration marker is present, read from SQLite only.
3. Otherwise read the exact SharedPreferences raw value. Distinguish a missing key (new install; treat as an empty journal) from a present value.
4. Strictly parse and validate the entire array before writing anything. If invalid, abort and leave the database unmarked and the original preference value untouched.
5. Begin one SQLite transaction.
6. Insert every record with its original array index as `ordinal`; do not deduplicate by ID.
7. Verify inserted row count equals parsed record count.
8. Write a migration marker (including schema/version and record count) in the same transaction.
9. Commit. If the process crashes before commit, both inserts and marker roll back; next launch retries from the untouched backup. If commit succeeds, marker and all rows are durable together.
10. Keep the original SharedPreferences JSON indefinitely as a rollback/export source until a separately approved retention policy exists.

## Normal reads and writes after migration

- Read all rows ordered by ordinal, reconstructing the same newest-first list.
- Perform updates inside a single SQLite transaction.
- Apply the existing 2,000-record cap only where the current write contract applies; migration itself must first preserve and verify every record present in the source value. If the source exceeds the cap, stop and resolve the policy explicitly rather than silently truncating during migration.
- Preserve the public `SignalJournal` API so scanner, service, UI, and export callers do not need a broad simultaneous rewrite.
- Do not use `INSERT OR IGNORE` on record IDs; that would silently discard duplicate-ID records.
- Do not delete the SharedPreferences backup after successful migration.

## Required test gates before production migration

### Codec / pure unit tests
- Empty journal and missing optional legacy fields.
- Full prospective record with all four evaluation horizons and trade outcome metadata.
- Exact serialization/deserialization round-trip for every field.
- Duplicate IDs are retained as separate records.
- Record order is preserved when timestamps tie.
- Malformed top-level JSON, non-object entries, invalid required fields, malformed nested arrays, and invalid optional numeric fields fail closed.
- No migration marker is produced by a parse failure.

### Android SQLite instrumentation tests
- First migration copies all rows and metadata, preserving order.
- Re-running migration is idempotent and does not duplicate rows.
- Duplicate IDs survive migration.
- Simulated exception before commit leaves no rows and no marker; retry succeeds from the original preference backup.
- Marker and rows are atomic.
- Database reopen returns the same ordered records.
- Database integrity check succeeds.
- A database/schema version upgrade preserves all records.
- Backup preference remains byte-for-byte unchanged after migration and normal writes.

### Release/rollout gates
- CI must run the instrumentation tests on an Android emulator/device, not just compile the APK.
- Test an upgrade from the currently installed app's SharedPreferences data.
- Export a ZIP before any migration build is offered.
- Do not replace the installed APK until a separate explicit approval.
- Do not merge or release as part of the audit work.

## Current decision

SQLite migration is not ready to implement until strict legacy-compatible validation and Android transaction tests are in place. The next safe work is to harden/test the record codec and establish an instrumentation-test path.
package com.diffuse.restore

import com.diffuse.backup.model.BackupItem

/**
 * DESIGN SEAM — NOT IMPLEMENTED IN THIS BUILD.
 *
 * The write-side mirror of [com.diffuse.backup.BackupSource]. Where a `BackupSource` reads
 * items out of the device, a `RestoreSink` would write them back — putting messages/calls into
 * the telephony providers and media originals back into MediaStore, when Diffuse eventually
 * grows a guarded restore mode (see `docs/restore-design.md`).
 *
 * ## Why this exists but does nothing
 *
 * Diffuse today is *provably* read-only: its manifest declares no write/manage permission and
 * it never takes the default-SMS role, so the OS would throw on any write attempt, and
 * `scripts/check-readonly.sh` fails the build if a provider-mutating **call** ever appears in
 * source. This file changes none of that — it is an **interface with no implementation and no
 * mutating call**, so the guard stays green and the shipping APK stays read-only. It marks the
 * architectural boundary so restore is a first-class, deliberately-gated future concern rather
 * than a bolt-on.
 *
 * ## The safety contract any future implementation MUST honour
 *
 * A real `RestoreSink` may only ship behind the model in `docs/restore-design.md`:
 *  - it requests write permissions / the SMS role **only** in an explicit, user-initiated
 *    restore mode — never during a backup, never automatically, never on the daily driver by
 *    default;
 *  - the user first sees a dry-run preview of exactly what would be written and confirms;
 *  - the recommended packaging is a separate build flavor so the artifact sideloaded for
 *    backup carries no write surface at all.
 *
 * Until then, the only supported restore path is external: download the archive from Drive and
 * restore the messages/calls with the SMS Backup & Restore app on a target phone.
 */
interface RestoreSink {

    /** Stable identifier for the destination this sink writes, e.g. "sms", "call_log". */
    val id: String

    /**
     * Would write [item] back to its on-device provider. **Unimplemented by design** — no
     * concrete `RestoreSink` ships in this build; see the class docs and `docs/restore-design.md`
     * for the safety contract a future implementation must satisfy before it can.
     */
    fun restore(item: BackupItem)
}

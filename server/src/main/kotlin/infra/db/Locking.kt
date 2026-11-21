package ch.nokillswit.infra.db

import io.r2dbc.spi.IsolationLevel
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * Runs [block] inside a READ COMMITTED transaction after executing every one of [lockStatements], in the order given - the
 * cooperating-writer table-lock protocol (Toadie's `infra/db/Locking.kt`, ported via Hammurabi with the tag registry, 1.2.0;
 * `persistence.md` "Tag categories (V32)"). READ COMMITTED is explicit so a writer that waited on a lock sees the prior writer's
 * committed rows before it decides. The override is per transaction (it is passed to Exposed's `BEGIN`/`SET TRANSACTION`, which Postgres
 * scopes to the transaction): it leaks nothing to the pooled connection's next borrower, and READ COMMITTED is the server default anyway.
 *
 * **The wait for the lock is bounded** (Adder's addition to the port; the room lock's rule, `rooms/RoomLock.kt`): the statements run under
 * `SET LOCAL lock_timeout = [lockWaitMillis]`, so a request queued behind a busy registry holds one pooled connection for that long at
 * most. A wait that outruns it fails with SQLSTATE `55P03` (`isLockNotAvailable`), which the CALLER maps to its typed `429`; this file
 * is in `infra/`, which may not name `authz`. The setting goes back to the session default as soon as the locks are held, so no other
 * wait of [block] (a unique-index wait, an FK key share) runs under it and none can raise `55P03` outside the lock statements.
 *
 * **For the tag registry ONLY.** The room's write lock (`rooms/RoomLock.kt`) is a different mechanism and stays untouched; no
 * other service calls this (`LockingTransactionUsageTest`).
 *
 * **Order matters.** A caller passing more than one lock statement MUST list them in the fixed global order its own deadlock-free
 * protocol requires; this helper only executes them in the order it receives them.
 */
suspend fun <T> lockingTransaction(
    database: R2dbcDatabase,
    lockWaitMillis: Int,
    vararg lockStatements: String,
    block: suspend R2dbcTransaction.() -> T,
): T = suspendTransaction(database, transactionIsolation = IsolationLevel.READ_COMMITTED) {
    exec("SET LOCAL lock_timeout = $lockWaitMillis")
    lockStatements.forEach { exec(it) }
    exec("SET LOCAL lock_timeout TO DEFAULT")
    block()
}

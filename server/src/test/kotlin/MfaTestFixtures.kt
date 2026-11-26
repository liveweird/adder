package ch.nokillswit

/** Remove only the challenges owned by this test. Small-capacity tests share the same database inside a fork. */
internal fun discardTestMfaChallenges(userIds: Collection<UInt>) {
    if (userIds.isEmpty()) return
    PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("DELETE FROM mfa_challenges WHERE user_id = ?").use { statement ->
            userIds.forEach { userId ->
                statement.setLong(1, userId.toLong())
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
}

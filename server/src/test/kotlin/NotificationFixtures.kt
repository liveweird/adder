package ch.nokillswit

import ch.nokillswit.notifications.NotificationChannel
import ch.nokillswit.notifications.NotificationType

/**
 * Raw-JDBC fixtures for `user_notification_preferences` (V22, V28). Email is OFF by default for every kind (1.1 D7), so a test that
 * wants a person in the digest opts them in here; a test of the PUT and the defaults reads and writes through the API instead.
 * Every call goes through [PostgresTestSupport.connect], one short statement.
 */
object TestNotificationPreferences {
    /** [userId]'s switch of [channel] for [type], stored as a row (`enabled`), replacing any row it had. */
    fun set(userId: UInt, type: NotificationType, channel: NotificationChannel, enabled: Boolean) {
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement(
                "INSERT INTO user_notification_preferences (user_id, notification_type, channel, enabled) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (user_id, notification_type, channel) DO UPDATE SET enabled = EXCLUDED.enabled",
            ).use { st ->
                st.setLong(1, userId.toLong())
                st.setString(2, type.name)
                st.setString(3, channel.name)
                st.setBoolean(4, enabled)
                st.executeUpdate()
            }
        }
    }

    /** Opts [userId] in to the digest email for [types] (every kind by default): the opt-in a 1.0.0 account does not have. */
    fun optInEmail(userId: UInt, types: Collection<NotificationType> = NotificationType.entries) {
        types.forEach { set(userId, it, NotificationChannel.EMAIL, enabled = true) }
    }

    /** Deletes every stored row of [userId]: back to the defaults. */
    fun clear(userId: UInt) {
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("DELETE FROM user_notification_preferences WHERE user_id = ?").use { st ->
                st.setLong(1, userId.toLong())
                st.executeUpdate()
            }
        }
    }
}

/**
 * Raw-JDBC fixtures for what a seeded thread made that a test did not ask for. Since 1.1 a seeded thread tells the members already in the
 * room (`THREAD_CREATED`, 1.1 D4) and makes its creator a watcher (so later replies are notices): a test that counts a person's notices as
 * it counted them in 1.0.0 starts from an empty bell and an unwatched thread instead.
 */
object TestNotices {
    /** Deletes every notice of room [roomId]: back to an empty bell after the fixtures seeded the room. */
    fun clearRoom(roomId: UInt) {
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("DELETE FROM notifications WHERE room_id = ?").use { st ->
                st.setInt(1, roomId.toInt())
                st.executeUpdate()
            }
        }
    }

    /** [clearRoom], and every watch of the room's threads too: nobody is told of a reply, a status move or a hand-over but by name. */
    fun quietRoom(roomId: UInt) {
        clearRoom(roomId)
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("DELETE FROM thread_watches WHERE room_id = ?").use { st ->
                st.setInt(1, roomId.toInt())
                st.executeUpdate()
            }
        }
    }
}

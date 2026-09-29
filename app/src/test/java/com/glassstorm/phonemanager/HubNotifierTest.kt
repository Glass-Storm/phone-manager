package com.glassstorm.phonemanager

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HubNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `ensureChannel creates the hub channel and is idempotent`() {
        // When the channel is ensured twice
        HubNotifier.ensureChannel(manager)
        HubNotifier.ensureChannel(manager)

        // Then exactly one channel exists with the hub id and name
        val channel = manager.getNotificationChannel(HubNotifier.CHANNEL_ID)
        assertThat(channel).isNotNull()
        assertThat(channel.id).isEqualTo(HubNotifier.CHANNEL_ID)
        assertThat(channel.name.toString()).isEqualTo(HubNotifier.CHANNEL_NAME)
        assertThat(manager.notificationChannels).hasSize(1)
    }

    @Test
    fun `foregroundNotification carries the hub channel title and text`() {
        // Given the channel exists
        HubNotifier.ensureChannel(manager)

        // When the notification is built
        val notification = HubNotifier.foregroundNotification(context)

        // Then it is the ongoing hub notification on the hub channel with its text
        assertThat(notification.channelId).isEqualTo(HubNotifier.CHANNEL_ID)
        val shadow = shadowOf(notification)
        assertThat(shadow.contentTitle.toString()).isEqualTo(HubNotifier.NOTIFICATION_TITLE)
        assertThat(shadow.contentText.toString()).isEqualTo(HubNotifier.NOTIFICATION_TEXT)
    }

    @Test
    fun `the channel id and notification id are the pinned production values`() {
        assertThat(HubNotifier.CHANNEL_ID).isEqualTo("hub-foreground")
        assertThat(HubNotifier.NOTIFICATION_ID).isEqualTo(1)
    }
}

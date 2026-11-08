package com.glassstorm.phonemanager.transport.grpc

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class ConversationLinkTest {
    @Test
    fun `two real sockets exchange text audio image and subtitles after handshake`() =
        runBlocking {
            withTimeout(15_000) {
                val received = Channel<ConversationPacket>(10)
                val replies = Channel<ConversationPacket>(10)
                val host = ConversationHost()
                host.start(0, { _, packet -> received.send(packet) }, {})
                val client = ConversationClient("127.0.0.1", host.port)
                val collecting = launch { client.events().collect { replies.send(it) } }
                try {
                    assertThat(replies.receive().ready).isTrue()
                    host.peerCount.first { it == 1 }
                    client.send(ConversationPacket(text = "眼鏡傳送文字"))
                    assertThat(received.receive().text).isEqualTo("眼鏡傳送文字")
                    assertThat(host.send(ConversationPacket(text = "手機回覆"))).isTrue()
                    assertThat(replies.receive().text).isEqualTo("手機回覆")
                    val pcm = byteArrayOf(1, 0, 2, 0, 3, 0)
                    client.send(ConversationPacket(pcm16 = pcm))
                    assertThat(received.receive().pcm16).isEqualTo(pcm)
                    client.send(ConversationPacket(pcm16 = byteArrayOf()))
                    assertThat(received.receive().pcm16).isEmpty()
                    val jpeg = byteArrayOf(-1, -40, 3, 4, -1, -39)
                    client.send(ConversationPacket(jpeg = jpeg))
                    assertThat(received.receive().jpeg).isEqualTo(jpeg)
                    assertThat(host.send(ConversationPacket(subtitle = "辨識完成嘅一句話"))).isTrue()
                    assertThat(replies.receive().subtitle).isEqualTo("辨識完成嘅一句話")
                } finally {
                    collecting.cancelAndJoin()
                    client.close()
                    host.peerCount.first { it == 0 }
                    assertThat(host.send(ConversationPacket(text = "沒有接收者"))).isFalse()
                    host.stop()
                }
            }
        }
}

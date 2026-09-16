package com.aimusic.player.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProtocolAdaptersTest {

    @Test
    fun `三种协议各拿到自己的适配器`() {
        val policy = JsonEnforcementPolicy.PromptOnly

        assertThat(protocolAdapterFor(ProtocolKind.OPENAI, policy))
            .isInstanceOf(OpenAiChatProtocol::class.java)
        assertThat(protocolAdapterFor(ProtocolKind.RESPONSES, policy))
            .isInstanceOf(ResponsesProtocol::class.java)
        assertThat(protocolAdapterFor(ProtocolKind.ANTHROPIC, policy))
            .isInstanceOf(AnthropicMessagesProtocol::class.java)
    }
}

package com.aimusic.player.llm

/** 协议类型 → 适配器。加第四种方言只需要在这里多一支。 */
fun protocolAdapterFor(kind: ProtocolKind, jsonPolicy: JsonEnforcementPolicy): ProtocolAdapter =
    when (kind) {
        ProtocolKind.OPENAI -> OpenAiChatProtocol(jsonPolicy)
        ProtocolKind.RESPONSES -> ResponsesProtocol(jsonPolicy)
        ProtocolKind.ANTHROPIC -> AnthropicMessagesProtocol(jsonPolicy)
    }

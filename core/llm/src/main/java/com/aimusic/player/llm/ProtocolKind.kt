package com.aimusic.player.llm

/** LLM 协议方言。决定请求怎么封、响应从哪儿取（spec §3）。 */
enum class ProtocolKind { OPENAI, RESPONSES, ANTHROPIC }

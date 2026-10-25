-- Anthropic thinking / redacted_thinking 内容块、Gemini model 轮 parts 的原样快照（JSON 文本）。
-- 这些块回传时不得修改且需保持原序，signature 单字段无法承载 redacted 的 opaque data 与 part 级签名。
ALTER TABLE agent_messages ADD COLUMN thinkingBlocksJson TEXT DEFAULT NULL

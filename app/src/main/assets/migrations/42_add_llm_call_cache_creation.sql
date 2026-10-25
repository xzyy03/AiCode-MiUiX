-- 写入服务端缓存的 token 数（Anthropic cache_creation_input_tokens），按单独单价计入费用估算。
ALTER TABLE llm_call_records ADD COLUMN cacheCreationTokens INTEGER NOT NULL DEFAULT 0

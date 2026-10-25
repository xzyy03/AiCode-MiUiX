-- 助手消息落库本次调用命中服务端缓存的输入 token 数，供气泡下方展示每条消息的缓存命中率。
ALTER TABLE agent_messages ADD COLUMN cachedInputTokens INTEGER NOT NULL DEFAULT 0;

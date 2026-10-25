-- 为 LLM 调用记录表添加重试次数字段，记录单次交互中底层发生的重试次数
ALTER TABLE llm_call_records ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0;

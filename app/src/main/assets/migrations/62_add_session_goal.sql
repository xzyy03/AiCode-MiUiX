-- 会话级持续目标（goal）：目标文本、生命周期状态、续跑轮次与 token 消耗基线
ALTER TABLE chat_sessions ADD COLUMN goal TEXT;
ALTER TABLE chat_sessions ADD COLUMN goalStatus TEXT;
ALTER TABLE chat_sessions ADD COLUMN goalTurnCount INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chat_sessions ADD COLUMN goalStartInputTokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chat_sessions ADD COLUMN goalStartOutputTokens INTEGER NOT NULL DEFAULT 0;

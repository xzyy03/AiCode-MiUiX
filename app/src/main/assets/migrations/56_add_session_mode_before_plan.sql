-- 记录进入 PLAN 模式前的模式（如 AUTO），供退出 PLAN 时恢复：
-- 此前退出 PLAN 一律落回 BUILD，导致用户从 AUTO 进入 PLAN 后自动模式被覆盖。
ALTER TABLE chat_sessions ADD COLUMN modeBeforePlan TEXT DEFAULT NULL;
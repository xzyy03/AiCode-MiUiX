-- 清理 agent_messages 中超大 JSON 快照字段，防止单行超 SQLite CursorWindow 约 2MB 限制
-- 导致读取消息时抛 SQLiteBlobTooBigException、进聊天页即崩。
-- toolCallsJson / thinkingBlocksJson / attachmentsJson 此前在落库时未做长度上限，
-- 超大工具入参（如 writeFile 写大文件）或长思考快照会撑爆数据行。截断后 JSON 不可解析，
-- 读取方经 runCatching 降级为空列表/空快照，不会崩溃。
UPDATE agent_messages SET toolCallsJson = CASE WHEN length(toolCallsJson) > 200000 THEN substr(toolCallsJson, 1, 200000) ELSE toolCallsJson END;
UPDATE agent_messages SET thinkingBlocksJson = CASE WHEN length(thinkingBlocksJson) > 200000 THEN substr(thinkingBlocksJson, 1, 200000) ELSE thinkingBlocksJson END;
UPDATE agent_messages SET attachmentsJson = CASE WHEN length(attachmentsJson) > 200000 THEN substr(attachmentsJson, 1, 200000) ELSE attachmentsJson END;

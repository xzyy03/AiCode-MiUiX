-- 兜底清理 agent_messages 中文本总量超限的行。
-- 单行所有大文本字段之和超过 SQLite CursorWindow（约 2MB）安全预算时，该行可能无法被
-- 载入窗口，读取消息时抛 IllegalStateException「Couldn't read row N, col 0 from CursorWindow」，
-- 进聊天页即崩。落库侧已改为按 UTF-8 字节限制各字段，此处清理历史存量。
-- 字符上限取字节预算的三分之一（中文单字符最多 3 字节），确保削减后真实字节数落入预算。
UPDATE agent_messages SET
    content = substr(content, 1, 50000),
    toolCallsJson = CASE WHEN toolCallsJson IS NULL THEN NULL ELSE substr(toolCallsJson, 1, 33333) END,
    toolArgs = CASE WHEN toolArgs IS NULL THEN NULL ELSE substr(toolArgs, 1, 666) END,
    reasoning = CASE WHEN reasoning IS NULL THEN NULL ELSE substr(reasoning, 1, 33333) END,
    signature = CASE WHEN signature IS NULL THEN NULL ELSE substr(signature, 1, 33333) END,
    attachmentsJson = CASE WHEN attachmentsJson IS NULL THEN NULL ELSE substr(attachmentsJson, 1, 6666) END,
    thinkingBlocksJson = CASE WHEN thinkingBlocksJson IS NULL THEN NULL ELSE substr(thinkingBlocksJson, 1, 33333) END
WHERE (length(CAST(content AS BLOB))
    + coalesce(length(CAST(toolCallsJson AS BLOB)), 0)
    + coalesce(length(CAST(toolArgs AS BLOB)), 0)
    + coalesce(length(CAST(reasoning AS BLOB)), 0)
    + coalesce(length(CAST(signature AS BLOB)), 0)
    + coalesce(length(CAST(attachmentsJson AS BLOB)), 0)
    + coalesce(length(CAST(thinkingBlocksJson AS BLOB)), 0)) > 600000;

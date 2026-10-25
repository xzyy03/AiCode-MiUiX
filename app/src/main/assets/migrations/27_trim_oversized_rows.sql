-- 清理 agent_messages 中因生图/多模态模型返回超大 base64 图片而超限的数据行
-- （SQLiteBlobTooBigException / CursorWindow 单行约 2MB 限制导致启动即崩）。
-- 含内嵌 data:image base64 的行：截断到 base64 起点前并附说明；
-- 其余超长字段一律截断到 200000 字符的安全阈值。
-- 注意：本文件的 SQL 字符串字面量内不能出现分号，否则会被 MigrationLoader 分割破坏。

UPDATE agent_messages SET content = CASE
    WHEN instr(content, 'base64,') > 0 AND length(content) > 200000 THEN substr(content, 1, instr(content, 'base64,') - 1) || char(10) || '[图片数据已省略：内嵌图片数据过大]'
    WHEN length(content) > 200000 THEN substr(content, 1, 200000) || char(10) || '[内容过长，已截断]'
    ELSE content
END;

UPDATE agent_messages SET reasoning = CASE WHEN length(reasoning) > 200000 THEN substr(reasoning, 1, 200000) ELSE reasoning END;

UPDATE agent_messages SET toolArgs = CASE WHEN length(toolArgs) > 200000 THEN substr(toolArgs, 1, 200000) ELSE toolArgs END;

UPDATE agent_messages SET toolCallsJson = CASE WHEN length(toolCallsJson) > 200000 THEN substr(toolCallsJson, 1, 200000) ELSE toolCallsJson END;

UPDATE agent_messages SET attachmentsJson = CASE WHEN length(attachmentsJson) > 200000 THEN substr(attachmentsJson, 1, 200000) ELSE attachmentsJson END;

-- 为消息表 sessionId 与会话表 workspacePath 建立索引，消除冷启动与消息查询时的全表扫描
CREATE INDEX IF NOT EXISTS index_agent_messages_sessionId ON agent_messages(sessionId);
CREATE INDEX IF NOT EXISTS index_chat_sessions_workspacePath ON chat_sessions(workspacePath);

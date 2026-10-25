-- 移除「本地通道」（RemoteProtocol.LOCAL）：旧版本创建的本地目录同步连接与其挂载记录一并清理。
-- 先删挂载再删连接，不依赖外键级联行为。
DELETE FROM remote_mounts WHERE connectionId IN (SELECT id FROM remote_connections WHERE protocol = 'LOCAL');
DELETE FROM remote_connections WHERE protocol = 'LOCAL';

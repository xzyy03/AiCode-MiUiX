-- 为 checkpoint 文件快照记录「拍摄时的内容指纹」，供回滚前比对。
--
-- 背景：会话 A 拍下文件快照后，会话 B（或用户）可能又改了同一个文件。此时 A 回滚
-- checkpoint 会用旧快照无条件写回，把 B 之后的改动一起抹掉，且没有任何提示。
-- 记录指纹后即可在写回前发现「该文件已被别人改过」，跳过它并告知用户。
--
-- 可空：历史快照没有指纹，回滚时按「未知版本」处理（放行），不阻断既有数据。
ALTER TABLE checkpoint_file_snapshots ADD COLUMN fingerprint TEXT;

-- 260909 用户账号生命周期：邮箱注册列 + 软删时间 + 注销邮箱墓碑表
-- t_user.email：注册用户邮箱（小写规范化）；存量用户为 NULL（邮箱验证体系只对新注册用户生效）
ALTER TABLE t_user ADD COLUMN email VARCHAR(255);
-- email_verified：0=未验证 1=已验证；未验证用户拒绝登录（仅当 email 非空时才检查）
ALTER TABLE t_user ADD COLUMN email_verified SMALLINT NOT NULL DEFAULT 0;
-- delete_time：自助注销软删时间；NULL=正常，非 NULL=处于 30 天可撤销冷静期（硬删清理由保留期任务负责）
ALTER TABLE t_user ADD COLUMN delete_time TIMESTAMP;
-- 注册用户 username=email（RFC 5321 上限 254 字符），原 64 不够；仅放宽长度不动唯一约束
ALTER TABLE t_user ALTER COLUMN username TYPE VARCHAR(255);
-- 活跃用户邮箱唯一（部分索引）：软删/硬删后同名邮箱可再注册；uk_user_username 为全表唯一，
-- 软删期内 username 仍被原行占用，恢复语义因此天然成立
CREATE UNIQUE INDEX uk_user_email_active ON t_user (email) WHERE deleted = 0 AND email IS NOT NULL;

COMMENT ON COLUMN t_user.email IS '注册邮箱（小写规范化），存量/管理员建/游客为 NULL';
COMMENT ON COLUMN t_user.email_verified IS '邮箱是否已验证 0：未验证 1：已验证';
COMMENT ON COLUMN t_user.delete_time IS '注销软删时间，NULL=正常；非 NULL=30 天可撤销期内';

-- 注销邮箱墓碑：SHA256(email) 留 180 天，防重复注册滥用回查
-- （同邮箱二次注销走 upsert 刷新 expire_time）。到期清理由保留期任务负责
CREATE TABLE t_user_email_tombstone (
    id          VARCHAR(20)  NOT NULL PRIMARY KEY,
    email_hash  VARCHAR(64)  NOT NULL,
    user_id     VARCHAR(20)  NOT NULL,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expire_time TIMESTAMP    NOT NULL,
    CONSTRAINT uk_email_tombstone_hash UNIQUE (email_hash)
);
COMMENT ON TABLE t_user_email_tombstone IS '注销邮箱哈希墓碑（180 天回查用，不存明文）';
COMMENT ON COLUMN t_user_email_tombstone.email_hash IS 'SHA-256(email 小写) 十六进制';
COMMENT ON COLUMN t_user_email_tombstone.user_id IS '已删除用户原 ID（仅回查留痕）';
COMMENT ON COLUMN t_user_email_tombstone.expire_time IS '过期时间（create_time + 180 天）';

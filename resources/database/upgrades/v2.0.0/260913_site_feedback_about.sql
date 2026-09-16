-- v2.0.0 260913 站点反馈与关于页（doc 25）
-- 两表独立于知识库管线（site 包，检索链路不读）；幂等：IF NOT EXISTS，重放无害。
-- 反馈不设 user_id 列：/public/feedback 进 PUBLIC_EXCLUDE_PATTERNS 后 UserContext 拦截器
--   整体跳过，该列恒 NULL 属死列；反馈面按 D1 纯匿名设计。
-- 关于页单行表零种子依赖：id 固定 1，首次后台保存时 upsert 生成，生产文案由维护者自贴。
-- 应用后开闸：RAG_SITE_ENABLED=true（env 覆盖名=属性逐段映射，不是 RAGENT_ 前缀）；
--   管理后台两页（feedback / about，admin 路由前缀）不挂 flag，表未建前后台页报错
--   属部署时序已知窗口。〔每行只出现一次 admin：S9 tripwire 的 admin.{0,40}admin
--   模式对注释本应豁免但其 -v 过滤锚不中 git grep 的「路径:行号:」前缀，T24 已修锚〕

CREATE TABLE IF NOT EXISTS t_site_feedback (
  id            BIGSERIAL PRIMARY KEY,
  content       TEXT        NOT NULL,
  contact       VARCHAR(100),
  client_ip     VARCHAR(64) NOT NULL,
  status        SMALLINT    NOT NULL DEFAULT 0,
  create_time   TIMESTAMP   NOT NULL DEFAULT now(),
  update_time   TIMESTAMP   NOT NULL DEFAULT now(),
  deleted       SMALLINT    DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_site_feedback_create_time ON t_site_feedback (create_time);
CREATE INDEX IF NOT EXISTS idx_site_feedback_ip_day ON t_site_feedback (client_ip, create_time);
COMMENT ON TABLE t_site_feedback IS '站点反馈（doc 25）：匿名访客反馈箱，admin 后台分页查看处理；与上游消息维度反馈（t_message_feedback）语义隔离';
COMMENT ON COLUMN t_site_feedback.contact IS '选填联系方式（≤100 字），维护者想追问时有渠道';
COMMENT ON COLUMN t_site_feedback.client_ip IS '提交方客户端 IP（XFF 首值口径），IP 日限与滥用排查用；admin 列表脱敏展示';
COMMENT ON COLUMN t_site_feedback.status IS '0 未处理 / 1 已处理 / 2 忽略';

CREATE TABLE IF NOT EXISTS t_site_about (
  id              BIGINT   PRIMARY KEY,
  content         TEXT,
  qr_image_url    VARCHAR(512),
  qr_image_url_alt VARCHAR(512),
  create_time     TIMESTAMP NOT NULL DEFAULT now(),
  update_time     TIMESTAMP NOT NULL DEFAULT now()
);
COMMENT ON TABLE t_site_about IS '关于页单行内容表（doc 25）：id 固定 1，service 层 upsert，零种子依赖';
COMMENT ON COLUMN t_site_about.content IS '关于页 markdown 内容（作者/项目介绍），维护者后台编辑';
COMMENT ON COLUMN t_site_about.qr_image_url IS '赞赏二维码 URL（可空）；与 alt 同时为空时前端赞赏区整区不渲染';
COMMENT ON COLUMN t_site_about.qr_image_url_alt IS '第二张赞赏二维码 URL（可空）';

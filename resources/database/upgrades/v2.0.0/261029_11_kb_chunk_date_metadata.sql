-- v2.0.0 261029_11 知识库分块日期元数据双列（#411，父票 #407 管道侧确定性日期标注；
-- 规则依据=research/date-structure-376-20261011 #376 结论 C 形态）
-- 变更：t_knowledge_chunk 新增两列独立日期元数据（发布≠截止分开，语义不得互替）：
--   source_date       DATE——发布时间（页面发布/更新日期，#275 口径 yyyy-MM-dd）。
--                     文档级事实：摄取内核按块序取文档内首个显式发布标记（Updated on D 等），
--                     文档内全部块同值传播；向量侧同键写入 metadata JSONB，
--                     由已合入的读回通道（PR#402）透传至组装层 data-ragent-date 标记。
--   activity_end_date DATE——活动截止日期（报名/申请/缴费截止等条目级截止事实）。
--                     条目级事实：仅含显式截止标记（Deadline: D / 报名截止：D 等）的块落值，
--                     文档内不传播；向量侧以 activity_end_date 键写入 metadata JSONB，
--                     供组装层确定性 as-of 状态计算（父票后续件）消费。
-- 缺失值：两列可空，NULL=「日期未知」——不虚构、不推算（#376 红线：学年形态 26/27、
--   2026/27 拒判为日期；仅年份不成形不落列；无显式标记的语料保持 NULL，
--   组装层既有行为渲染「日期未知」）。
-- 兼容性：纯加列可空、无默认值、无 NOT NULL——旧代码（不写此二列）与新代码
--   （不读历史行此二列做业务判定）双向兼容；可重复执行（IF NOT EXISTS，先例=261011）。
-- 执行时点：随本批镜像滚动后手工施加（deploy CI 只 scp 建表 SQL，不执行 upgrades）；
--   先施加后滚动亦可（列可空，旧代码 INSERT 不含此二列不报错）。
-- 新装库：schema_pg.sql 已同步本二列，空卷首启自动建出，不需要本脚本。
-- 存量块：两列保持 NULL（历史不回填）；带日期元数据重灌由评测语料重灌流程统一执行
--   （全量重灌属后续评测票 #414，不在本票）。

ALTER TABLE t_knowledge_chunk ADD COLUMN IF NOT EXISTS source_date DATE;
ALTER TABLE t_knowledge_chunk ADD COLUMN IF NOT EXISTS activity_end_date DATE;

COMMENT ON COLUMN t_knowledge_chunk.source_date IS '发布时间（#411 日期元数据）：页面发布/更新日期（#275 口径 yyyy-MM-dd），文档级事实按块序取首个显式发布标记后文档内同值传播；NULL=日期未知（不虚构不推算），同键透传向量 metadata JSONB 供读回通道消费';
COMMENT ON COLUMN t_knowledge_chunk.activity_end_date IS '活动截止日期（#411 日期元数据）：报名/申请/缴费截止等条目级截止事实（date-only），仅含显式截止标记的块落值、文档内不传播；NULL=无截止语义或日期未知（不虚构不推算），以 activity_end_date 键透传向量 metadata JSONB 供组装层 as-of 计算消费';

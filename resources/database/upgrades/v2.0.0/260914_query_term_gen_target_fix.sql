-- v2.0.0 260914 GEN 术语映射目标词修正（示例问题审计产出，ES 逐条实测）
-- 背景：96 条 GEN 映射全量过 ES match_phrase 验证，22 条目标词在语料 0 短语命中；
--   其中 10 条「中文源词在语料存在、英文目标词 0 命中」——归一化会把这些中文 token
--   替换成语料里不存在的英文写法，等于抹掉本可命中中文文档的关键词，帮倒忙。
-- 修法：目标词改为「语料真实英文形态 (中文原词)」双语写法——注入实测非零的官方
--   英文 token（Subject Registration×39 / Subject Code×5 / hall application×20 /
--   Group Rooms×6 / credit×73 / withdrawal×56 / part-time×71），同时保留中文 token
--   两头命中；applyMapping 单趟扫描语义下自包含目标不会二次触发（已逐条核对包含链）。
-- 幂等：UPDATE 按 id 无条件覆盖，重放无害。
-- 〔2026-09-16 修复〕remark 拼接补 left(...,255) 封顶——schema remark=VARCHAR(255)，
-- 长 remark 行（如 245 字）拼接修正注记会超长报错；截尾仅损失注记尾部文字，映射语义不变。须在 260914_query_term_gen_seed.sql 之后执行
--   （文件名字典序已保证 seed < target_fix；seed 若插入的是新库则本文件同为收敛值）。
-- ⚠ 应用后必须清映射缓存：DEL ragent:query-term:mappings

UPDATE t_query_term_mapping SET target_term = 'Subject Registration (科目注册)', remark = left(coalesce(remark,'') || '｜260914 修正：course registration 语料 0 短语命中，官方形态 Subject Registration（AR 页面标题）', 255) WHERE id = '2096913104632860672';
UPDATE t_query_term_mapping SET target_term = 'Subject Code (科目代码)', remark = left(coalesce(remark,'') || '｜260914 修正：course code 0 命中，官方形态 Subject Code×5', 255) WHERE id = '2096913104846770176';
UPDATE t_query_term_mapping SET target_term = 'hall application (宿舍申请)', remark = left(coalesce(remark,'') || '｜260914 修正：hostel application 0 命中，语料 hall application×20', 255) WHERE id = '2096913109485670400';
UPDATE t_query_term_mapping SET target_term = '小组讨论室 (Group Rooms)', remark = left(coalesce(remark,'') || '｜260914 修正：group study room 0 命中，语料 Group Rooms×6', 255) WHERE id = '2096913108437094400';
UPDATE t_query_term_mapping SET target_term = 'acceptance fee (留位费)', remark = left(coalesce(remark,'') || '｜260914 修正：acceptance fee 0 短语命中，保留中文 token（中文文档×3）', 255) WHERE id = '2096913105861791744';
UPDATE t_query_term_mapping SET target_term = 'withdrawal (退学)', remark = left(coalesce(remark,'') || '｜260914 修正：withdrawal from studies 0 命中，语料 withdrawal×56', 255) WHERE id = '2096913105371058176';
UPDATE t_query_term_mapping SET target_term = 'credit (学分)', remark = left(coalesce(remark,'') || '｜260914 修正：credit unit 0 命中，语料 credit×73', 255) WHERE id = '2096913104825798656';
UPDATE t_query_term_mapping SET target_term = 'withdrawal (退课)', remark = left(coalesce(remark,'') || '｜260914 修正：course withdrawal 0 命中，语料 withdrawal×56', 255) WHERE id = '2096913104771272704';
UPDATE t_query_term_mapping SET target_term = 'examination regulations (考试规则)', remark = left(coalesce(remark,'') || '｜260914 修正：目标 0 短语命中，保留中文 token（中文文档×5）', 255) WHERE id = '2096913104922267648';
UPDATE t_query_term_mapping SET target_term = 'part-time student (兼读制学生)', remark = left(coalesce(remark,'') || '｜260914 修正：目标 0 短语命中，语料 part-time×71，保留中文 token', 255) WHERE id = '2096913104171487232';

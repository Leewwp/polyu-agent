-- v2.0.0 260916 file 型知识文档官网下载页回填（doc 32 决策一：来源徽章两态）
-- 只回填有权威官网页的重点文档；其余 file 文档 source_location 保持 NULL=徽章回落站内预览。
-- 幂等：NOT LIKE 'http%' 守卫（只补空/非 http 值，重放无害；后续人工改过不再覆盖）。
-- 官网页核验（2026-09-16）：
--   学生手册（授课式）https://www.polyu.edu.hk/ar/students-in-taught-programmes/student-handbook/
--   学术年历         https://www.polyu.edu.hk/ar/students-in-taught-programmes/academic-calendar/

UPDATE t_knowledge_document
SET source_location = 'https://www.polyu.edu.hk/ar/students-in-taught-programmes/student-handbook/',
    update_time = now()
WHERE source_type = 'file' AND deleted = 0
  AND doc_name LIKE 'Student_Handbook_%'
  AND (source_location IS NULL OR source_location NOT LIKE 'http%');

UPDATE t_knowledge_document
SET source_location = 'https://www.polyu.edu.hk/ar/students-in-taught-programmes/academic-calendar/',
    update_time = now()
WHERE source_type = 'file' AND deleted = 0
  AND doc_name = 'AC.pdf'
  AND (source_location IS NULL OR source_location NOT LIKE 'http%');

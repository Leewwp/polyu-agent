-- v2.0.0 260915 示例问题语言列（T20：Agent 待机页双语化数据面）
-- 每语言一行：抽样按 lang 限定、无命中回落全量（中文兜底，避免待机页空 chips）。
-- 幂等：ADD COLUMN IF NOT EXISTS；英文 10 条固定 id + ON CONFLICT DO NOTHING；
--   存量 10 行中文由列默认值自动落 zh（UPDATE 兜底一段，重放无害）。
-- 术语对齐 260914_query_term_gen_target_fix 双语目标词表：
--   研讨室=Group Rooms、课程注册=Subject Registration、授课式研究生=taught postgraduate (TPG)。

ALTER TABLE t_sample_question
    ADD COLUMN IF NOT EXISTS lang VARCHAR(8) NOT NULL DEFAULT 'zh';
COMMENT ON COLUMN t_sample_question.lang IS '语言 zh / en（T20 每语言一行，抽样按语言限定，无命中回落全量）';

UPDATE t_sample_question SET lang = 'zh' WHERE lang IS NULL;

INSERT INTO t_sample_question (id, title, description, question, lang) VALUES
  ('2609130000000000201', 'Library opening hours',  'Library opening hours by section and holiday arrangements', 'What are the Library opening hours? Are they extended during exam weeks?', 'en'),
  ('2609130000000000202', 'Group Rooms booking',    'How to book Library Group Rooms and study spaces', 'How can I book a Group Room in the Library? How many days in advance?', 'en'),
  ('2609130000000000203', 'Student visa renewal',   'Timeline and documents for student visa renewal', 'How early should I renew my student visa, and what documents are needed?', 'en'),
  ('2609130000000000204', 'Scholarship applications', 'Scholarships open to taught postgraduate (TPG) students', 'What scholarships can taught postgraduate students apply for, and what are the deadlines?', 'en'),
  ('2609130000000000205', 'Halls of residence',     'How new students apply for student halls and the fees', 'How do new students apply for halls of residence, and how much does it cost?', 'en'),
  ('2609130000000000206', 'Subject registration',   'Subject registration and Add/Drop for next semester', 'When are subject registration and Add/Drop for next semester?', 'en'),
  ('2609130000000000207', 'Tuition payment',        'Tuition payment deadline and late-payment effects', 'What is the tuition payment deadline, and what happens if I pay late?', 'en'),
  ('2609130000000000208', 'Key academic dates',     'Exam weeks and Reading Week in the semester', 'Which days are the exam weeks this semester, and is there a Reading Week break?', 'en'),
  ('2609130000000000209', 'Counselling services',   'On-campus counselling services and how to book', 'What counselling services does the university offer, and how do I book one?', 'en'),
  ('2609130000000000210', 'Off-campus e-resources', 'Accessing Library e-databases from off campus', 'How can I access the Library e-databases from off campus?', 'en')
ON CONFLICT (id) DO NOTHING;

-- 创作约束：建书前 AI 追问、作者作答后拼成的硬要求。
-- 一行一条，例如「主角设定：被逐出宗门的杂役」。
ALTER TABLE t_novel ADD COLUMN constraint_text TEXT;

COMMENT ON COLUMN t_novel.constraint_text IS '创作约束：建书时 AI 追问后由作者补充，注入后续所有创作场景';

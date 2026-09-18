WITH RECURSIVE facet_subject(stype, sid, srel) AS (
  SELECT ?::text, ?::text, ?::text
  UNION
  SELECT t.object_type, t.object_id, t.relation
    FROM facet_tuple t
    JOIN facet_subject s
      ON t.subject_type = s.stype AND t.subject_id = s.sid AND t.subject_rel = s.srel
   WHERE t.rev_from <= ? AND ? < t.rev_to
)
SELECT DISTINCT p1.otype, p1.oid, (p1.otype || ':' || p1.oid) COLLATE "C" AS sort_key
  FROM (
(SELECT t.object_type AS otype, t.object_id AS oid
  FROM facet_tuple t
  JOIN facet_subject s
    ON t.subject_type = s.stype AND t.subject_id = s.sid AND t.subject_rel = s.srel
 WHERE t.relation = ?::text AND t.object_type = ?::text AND t.rev_from <= ? AND ? < t.rev_to)
EXCEPT
(SELECT t.object_type AS otype, t.object_id AS oid
  FROM facet_tuple t
  JOIN facet_subject s
    ON t.subject_type = s.stype AND t.subject_id = s.sid AND t.subject_rel = s.srel
 WHERE t.relation = ?::text AND t.object_type = ?::text AND t.rev_from <= ? AND ? < t.rev_to)
) p1
 WHERE (p1.otype || ':' || p1.oid) COLLATE "C" > ?::text
 ORDER BY sort_key
 LIMIT ?
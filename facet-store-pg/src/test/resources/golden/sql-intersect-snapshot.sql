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
SELECT f2.otype, f2.oid
  FROM (
(SELECT t.object_type AS otype, t.object_id AS oid
  FROM facet_tuple t
  JOIN facet_subject s
    ON t.subject_type = s.stype AND t.subject_id = s.sid AND t.subject_rel = s.srel
 WHERE t.relation = ?::text AND t.object_type = ?::text AND t.rev_from <= ? AND ? < t.rev_to)
INTERSECT
(SELECT t.object_type AS otype, t.object_id AS oid
  FROM facet_tuple t
  JOIN (
SELECT t.object_type AS otype, t.object_id AS oid
  FROM facet_tuple t
  JOIN facet_subject s
    ON t.subject_type = s.stype AND t.subject_id = s.sid AND t.subject_rel = s.srel
 WHERE t.relation = ?::text AND t.object_type = ?::text AND t.rev_from <= ? AND ? < t.rev_to
) i3
    ON t.subject_type = i3.otype AND t.subject_id = i3.oid AND t.subject_rel = ''
 WHERE t.relation = ?::text AND t.object_type = ?::text AND t.rev_from <= ? AND ? < t.rev_to)
) f2
 WHERE ((SELECT a.value FROM facet_attr a
  WHERE a.object_type = f2.otype AND a.object_id = f2.oid AND a.name = ?::text)) IS NOT DISTINCT FROM (?::text)
) p1
 WHERE (p1.otype || ':' || p1.oid) COLLATE "C" > ?::text
 ORDER BY sort_key
 LIMIT ?
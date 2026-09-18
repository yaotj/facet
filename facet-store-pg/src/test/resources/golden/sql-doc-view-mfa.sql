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
UNION ALL
(SELECT t.object_type AS otype, t.object_id AS oid
  FROM facet_tuple t
  JOIN (
(SELECT t.object_type AS otype, t.object_id AS oid
  FROM facet_tuple t
  JOIN facet_subject s
    ON t.subject_type = s.stype AND t.subject_id = s.sid AND t.subject_rel = s.srel
 WHERE t.relation = ?::text AND t.object_type = ?::text AND t.rev_from <= ? AND ? < t.rev_to)
UNION ALL
(SELECT clo5.otype, clo5.oid
  FROM (
    WITH RECURSIVE cl4(otype, oid) AS (
      SELECT t.object_type, t.object_id
        FROM facet_tuple t
        JOIN (
SELECT t.object_type AS otype, t.object_id AS oid
  FROM facet_tuple t
  JOIN facet_subject s
    ON t.subject_type = s.stype AND t.subject_id = s.sid AND t.subject_rel = s.srel
 WHERE t.relation = ?::text AND t.object_type = ?::text AND t.rev_from <= ? AND ? < t.rev_to
) i6
          ON t.subject_type = i6.otype AND t.subject_id = i6.oid AND t.subject_rel = ''
       WHERE t.relation = ?::text AND t.object_type = ?::text AND t.rev_from <= ? AND ? < t.rev_to
      UNION
      SELECT t.object_type, t.object_id
        FROM facet_tuple t
        JOIN cl4
          ON t.subject_type = cl4.otype AND t.subject_id = cl4.oid AND t.subject_rel = ''
       WHERE t.relation = ?::text AND t.object_type = ?::text AND t.rev_from <= ? AND ? < t.rev_to
    )
    SELECT otype, oid FROM cl4
  ) clo5)
) i3
    ON t.subject_type = i3.otype AND t.subject_id = i3.oid AND t.subject_rel = ''
 WHERE t.relation = ?::text AND t.object_type = ?::text AND t.rev_from <= ? AND ? < t.rev_to)
) f2
 WHERE (SELECT CASE WHEN lower(v7.v) IN ('true','false') THEN lower(v7.v)::boolean END FROM (SELECT (?::text) AS v) v7) IS NOT DISTINCT FROM (SELECT CASE WHEN lower(v8.v) IN ('true','false') THEN lower(v8.v)::boolean END FROM (SELECT (?::text) AS v) v8)
) p1
 WHERE (p1.otype || ':' || p1.oid) COLLATE "C" > ?::text
 ORDER BY sort_key
 LIMIT ?
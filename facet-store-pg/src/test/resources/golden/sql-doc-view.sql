WITH RECURSIVE facet_subject(stype, sid, srel) AS (
  SELECT ?::text, ?::text, ?::text
  UNION
  SELECT t.object_type, t.object_id, t.relation
    FROM facet_tuple t
    JOIN facet_subject s
      ON t.subject_type = s.stype AND t.subject_id = s.sid AND t.subject_rel = s.srel
   WHERE t.rev_from <= ? AND ? < t.rev_to
)
SELECT p1.otype, p1.oid
  FROM (
(SELECT t.object_type AS otype, t.object_id AS oid
  FROM facet_tuple t
  JOIN facet_subject s
    ON t.subject_type = s.stype AND t.subject_id = s.sid AND t.subject_rel = s.srel
 WHERE t.relation = ?::text AND t.object_type = ?::text AND t.rev_from <= ? AND ? < t.rev_to)
UNION
(SELECT t.object_type AS otype, t.object_id AS oid
  FROM facet_tuple t
  JOIN (
(SELECT t.object_type AS otype, t.object_id AS oid
  FROM facet_tuple t
  JOIN facet_subject s
    ON t.subject_type = s.stype AND t.subject_id = s.sid AND t.subject_rel = s.srel
 WHERE t.relation = ?::text AND t.object_type = ?::text AND t.rev_from <= ? AND ? < t.rev_to)
UNION
(SELECT clo4.otype, clo4.oid
  FROM (
    WITH RECURSIVE cl3(otype, oid) AS (
      SELECT t.object_type, t.object_id
        FROM facet_tuple t
        JOIN (
SELECT t.object_type AS otype, t.object_id AS oid
  FROM facet_tuple t
  JOIN facet_subject s
    ON t.subject_type = s.stype AND t.subject_id = s.sid AND t.subject_rel = s.srel
 WHERE t.relation = ?::text AND t.object_type = ?::text AND t.rev_from <= ? AND ? < t.rev_to
) i5
          ON t.subject_type = i5.otype AND t.subject_id = i5.oid AND t.subject_rel = ''
       WHERE t.relation = ?::text AND t.object_type = ?::text AND t.rev_from <= ? AND ? < t.rev_to
      UNION
      SELECT t.object_type, t.object_id
        FROM facet_tuple t
        JOIN cl3
          ON t.subject_type = cl3.otype AND t.subject_id = cl3.oid AND t.subject_rel = ''
       WHERE t.relation = ?::text AND t.object_type = ?::text AND t.rev_from <= ? AND ? < t.rev_to
    )
    SELECT otype, oid FROM cl3
  ) clo4)
) i2
    ON t.subject_type = i2.otype AND t.subject_id = i2.oid AND t.subject_rel = ''
 WHERE t.relation = ?::text AND t.object_type = ?::text AND t.rev_from <= ? AND ? < t.rev_to)
) p1
 WHERE (p1.otype || ':' || p1.oid) COLLATE "C" > ?::text
 ORDER BY (p1.otype || ':' || p1.oid) COLLATE "C"
 LIMIT ?
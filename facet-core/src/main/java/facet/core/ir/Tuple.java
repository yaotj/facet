package facet.core.ir;

/**
 * 关系元组 {@code object#relation@subject}。
 *
 * <p>放在内核里而不是各适配器各定义一份：它是整个系统的基本数据形状，两个适配器各写一遍
 * 会让"同一份数据在两个存储上跑"这种一致性测试写不出来。
 *
 * <p>注意写入<strong>不在</strong>{@code TupleSource} 端口上——端口是只读的。这个 record
 * 只是数据形状的共同定义，一致性策略（事务边界、版本分配、批量）仍由各适配器自己决定。
 */
public record Tuple(ObjectRef object, Rel relation, SubjectRef subject) {

    /** {@code object#relation@subject}，其中 subject 是具体对象。 */
    public static Tuple of(ObjectRef object, Rel relation, ObjectRef subject) {
        return new Tuple(object, relation, new SubjectRef.Principal(subject.type(), subject.id()));
    }

    /** {@code object#relation@subjectObject#subjectRel}，即 userset 授权。 */
    public static Tuple of(ObjectRef object, Rel relation, ObjectRef subjectObject, Rel subjectRel) {
        return new Tuple(object, relation, new SubjectRef.Userset(subjectObject, subjectRel));
    }
}

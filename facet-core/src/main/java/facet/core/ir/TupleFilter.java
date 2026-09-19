package facet.core.ir;

/**
 * 元组筛选条件。用于运维侧的读取与批量撤销。
 *
 * <p>为什么需要它：{@code TupleSource} 上那三个读方法都是<em>求值</em>形状的，每个都要求
 * 一个完整锚点（"doc:readme 上 viewer 的主体有谁"）。它们答不出"这个对象上到底写了什么"
 * 和"这个人被授了哪些权"，而这两个问题是迁移、审计导出、离职清理的基本操作。
 *
 * <p><strong>{@code null} 表示"任意"。</strong>空串不行——空串是一个合法的
 * {@code subject_rel}（它表示"具体主体而非 userset"），两者必须能区分开。构造期拒绝空白串，
 * 就是为了不让"我想匹配任意"和"我传了个空字符串"看起来一样。
 *
 * <p><strong>无约束的筛选条件是合法的，但必须显式构造。</strong>{@link #ANY} 匹配全部元组；
 * 批量撤销接受它，因为"清空这个租户"是真实需求。但它不能是某个字段忘填的意外结果——
 * 这也是所有工厂方法都至少钉住一个字段的原因。
 */
public record TupleFilter(ObjectType objectType,
                          String objectId,
                          Rel relation,
                          ObjectType subjectType,
                          String subjectId,
                          Rel subjectRel) {

    /** 匹配全部元组。只应当出现在明确要清空的地方。 */
    public static final TupleFilter ANY = new TupleFilter(null, null, null, null, null, null);

    public TupleFilter {
        requireNotBlank(objectId, "objectId");
        requireNotBlank(subjectId, "subjectId");
    }

    /** 某个对象上的全部元组。清理一个被删除的资源用这个。 */
    public static TupleFilter onObject(ObjectRef object) {
        return new TupleFilter(object.type(), object.id(), null, null, null, null);
    }

    /** 某个对象上某个关系的全部元组。 */
    public static TupleFilter onObject(ObjectRef object, Rel relation) {
        return new TupleFilter(object.type(), object.id(), relation, null, null, null);
    }

    /**
     * 某个主体被授予的全部元组。离职清理用这个。
     *
     * <p>注意它匹配的是<strong>直接</strong>授权。经 userset 间接获得的权限不在结果里——
     * 那些元组的主体侧是组，不是人。要连带清掉组成员关系，得对 {@code group:x#member}
     * 再发一次以该人为主体的筛选。
     */
    public static TupleFilter ofSubject(SubjectRef subject) {
        return switch (subject) {
            case SubjectRef.Principal(var type, var id) ->
                    new TupleFilter(null, null, null, type, id, null);
            case SubjectRef.Userset(var object, var relation) ->
                    new TupleFilter(null, null, null, object.type(), object.id(), relation);
            case SubjectRef.Wildcard(var type) -> wildcardsOf(type);
        };
    }

    /**
     * 只匹配某个类型的<strong>通配</strong>授权，不含该类型下任何具体主体的授权。
     *
     * <p>这个方法必须存在，而且必须与 {@link #ofObjectType} 之类的"整个类型"区分开：
     * 它的典型用途是下线一条公开资源策略，而那条命令会流进 {@code revokeWhere}。
     * 如果"找出 user 的通配授权"实际产出的是"主体类型是 user 的全部元组"，
     * 一次按文档调用就会把该类型下所有人的授权全撤掉——而 {@link #unconstrained()}
     * 还会返回 {@code false}，运维日志里连个警告都没有。
     *
     * <p>实现上借用 {@link SubjectRef#WILDCARD_ID} 做筛选层的标记。它是安全的：
     * {@code Principal} 的构造器拒绝这个 id，所以 {@code "*"} 在这里不可能指向某个真实主体。
     * 各适配器负责把它翻译成自己的存储编码（PG 与内存都是空串）。
     */
    public static TupleFilter wildcardsOf(ObjectType type) {
        return new TupleFilter(null, null, null, type, SubjectRef.WILDCARD_ID, null);
    }

    /** 某个类型的全部元组。下线一个对象类型用这个。 */
    public static TupleFilter ofObjectType(ObjectType type) {
        return new TupleFilter(type, null, null, null, null, null);
    }

    /** 是否没有任何约束。批量撤销应当在日志里把它标出来。 */
    public boolean unconstrained() {
        return objectType == null && objectId == null && relation == null
                && subjectType == null && subjectId == null && subjectRel == null;
    }

    /** 这条元组是否落在筛选范围内。内存适配器直接用它；PG 侧编成等价的 WHERE。 */
    public boolean matches(Tuple tuple) {
        if (objectType != null && !objectType.equals(tuple.object().type())) {
            return false;
        }
        if (objectId != null && !objectId.equals(tuple.object().id())) {
            return false;
        }
        if (relation != null && !relation.equals(tuple.relation())) {
            return false;
        }
        return matchesSubject(tuple.subject());
    }

    private boolean matchesSubject(SubjectRef subject) {
        var type = switch (subject) {
            case SubjectRef.Principal(var t, _) -> t;
            case SubjectRef.Userset(var object, _) -> object.type();
            case SubjectRef.Wildcard(var t) -> t;
        };
        // 通配在筛选层用 "*" 表示，与线上格式一致；它不可能撞上真实主体，
        // 因为 Principal 的构造器拒绝这个 id。适配器负责翻译成自己的存储编码
        var id = switch (subject) {
            case SubjectRef.Principal(_, var i) -> i;
            case SubjectRef.Userset(var object, _) -> object.id();
            case SubjectRef.Wildcard _ -> SubjectRef.WILDCARD_ID;
        };
        // Principal 与 Wildcard 都没有 subjectRel；要求匹配某个 rel 时它们一律不符合
        var rel = switch (subject) {
            case SubjectRef.Principal _ -> null;
            case SubjectRef.Wildcard _ -> null;
            case SubjectRef.Userset(_, var r) -> r;
        };
        if (subjectType != null && !subjectType.equals(type)) {
            return false;
        }
        if (subjectId != null && !subjectId.equals(id)) {
            return false;
        }
        return subjectRel == null || subjectRel.equals(rel);
    }

    private static void requireNotBlank(String value, String field) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " 不能是空白串；表示\"任意\"请用 null——空串在 subject_rel 上有具体含义");
        }
    }
}

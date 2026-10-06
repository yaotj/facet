package facet.store.pg;

import facet.core.sem.Conds;
import facet.core.ir.AttrKey;
import facet.core.ir.Cond;
import facet.core.ir.Plan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@link Plan} → 一条 SQL。
 *
 * <p>这个类是整套设计里最需要被验证的地方：内核声称"反查是集合代数，可以整条下推"，
 * 而这里给出该说法的可执行证据。七个算子的映射是
 * ScanReverse→索引扫描、Union/Intersect/Difference→UNION/INTERSECT/EXCEPT、
 * ExpandUp→一次反向 JOIN、ExpandUpClosure→{@code WITH RECURSIVE}、
 * Filter→属性子查询、Page→键游标 + LIMIT。没有一处需要回到应用层做循环。
 *
 * <p>CTE 不上提到顶层，而是就地内嵌成派生表。上提能省重复计算，但会让绑定参数的顺序
 * 与 SQL 文本顺序脱钩，那是一类极难排查的错误；这一版先要正确性。
 */
public final class PlanSqlCompiler {

    private PlanSqlCompiler() {
    }

    /**
     * 把整棵计划编译成一条 SQL。主体、坐标与分页值都编成 {@link Param} 标记，SQL 文本因此只与
     * 计划形状有关，可以按形状缓存并跨请求复用。
     *
     * <p>参数入列顺序必须与占位符在 SQL 文本里出现的顺序逐一对应，所以主体闭包的六个参数
     * 先入列，再编译 body。
     *
     * <p>闭包有两个种子：主体自己，以及主体类型的通配（{@code user:*}）。通配因此只是闭包里
     * 多出来的一行，{@code ScanReverse} 的连接、{@code Difference}、分页全都不用改——
     * 反查问的是"这个<em>具体</em>主体能碰哪些"，通配在这条路上不构成开放集合。
     */
    public static SqlQuery compile(Plan plan) {
        var emit = new Emit();
        // 主体闭包的参数必须先入列：它在 SQL 文本里位于最前面。
        emit.params.add(new Param.PrincipalType());
        emit.params.add(new Param.PrincipalId());
        emit.params.add(new Param.PrincipalRel());
        // 通配种子只需要类型：user:* 在表里的 id 与 rel 都是空串
        emit.params.add(new Param.PrincipalType());
        emit.params.add(new Param.At());
        emit.params.add(new Param.At());

        var body = select(plan, emit);
        var sql = """
                WITH RECURSIVE facet_subject(stype, sid, srel) AS (
                  SELECT ?::text, ?::text, ?::text
                  UNION
                  SELECT ?::text, '', ''
                  UNION
                  SELECT t.object_type, t.object_id, t.relation
                    FROM facet_tuple t
                    JOIN facet_subject s
                      ON t.subject_type = s.stype AND t.subject_id = s.sid AND t.subject_rel = s.srel
                   WHERE t.rev_from <= ? AND ? < t.rev_to
                )
                """ + body;
        return new SqlQuery(sql, emit.params);
    }

    /** 没有 {@code default} 分支：{@code Plan} 加算子，这个适配器立刻编译失败。 */
    private static String select(Plan plan, Emit emit) {
        return switch (plan) {
            case Plan.ScanReverse(var rel, var type) -> """
                    SELECT t.object_type AS otype, t.object_id AS oid
                      FROM facet_tuple t
                      JOIN facet_subject s
                        ON t.subject_type = s.stype AND t.subject_id = s.sid AND t.subject_rel = s.srel
                     WHERE t.relation = %s AND t.object_type = %s AND %s"""
                    .formatted(text(rel.name(), emit), text(type.name(), emit), rev("t", emit));

            case Plan.Union(var inputs) -> setOp(inputs, "UNION ALL", emit);
            case Plan.Intersect(var inputs) -> setOp(inputs, "INTERSECT", emit);
            case Plan.Difference(var left, var right) ->
                    '(' + select(left, emit) + ")\nEXCEPT\n(" + select(right, emit) + ')';

            case Plan.ExpandUp(var inner, var hop, var outer) -> {
                var alias = emit.next("i");
                yield """
                        SELECT t.object_type AS otype, t.object_id AS oid
                          FROM facet_tuple t
                          JOIN %s
                            ON t.subject_type = %s.otype AND t.subject_id = %s.oid AND t.subject_rel = ''
                         WHERE t.relation = %s AND t.object_type = %s AND %s"""
                        .formatted(derived(inner, alias, emit), alias, alias,
                                text(hop.name(), emit), text(outer.name(), emit), rev("t", emit));
            }

            case Plan.ExpandUpClosure(var inner, var hop, var outer) -> closure(inner, hop, outer, emit);

            case Plan.Filter(var input, var cond) -> {
                var alias = emit.next("f");
                yield "SELECT %s.otype, %s.oid\n  FROM %s\n WHERE %s".formatted(
                        alias, alias, derived(input, alias, emit), predicate(cond, alias, emit));
            }

            case Plan.Page(var input, var after, var limit) -> {
                var alias = emit.next("p");
                // COLLATE "C" 不是装饰：Keys 用 UTF-8 字节序，PG 默认走本地化排序规则。
                // 不钉住 C，同一份游标在两个适配器上会翻页到不同位置。
                var key = "(%s.otype || ':' || %s.oid) COLLATE \"C\"".formatted(alias, alias);
                // DISTINCT 在这里而不是靠 UNION 去重：千万级实测下 UNION 的去重排序会把
                // 十万行落盘（external merge），而这一层本来就要为 ORDER BY 排一次。
                // 集合语义不变——Plan.Union 编成 UNION ALL，重复行统一在这里消掉，
                // 而 Page 之上不再有集合运算。排序键必须进选择列表，否则 PG 拒绝
                // DISTINCT 与 ORDER BY 的组合；它是前两列的函数，不影响去重结果。
                // 游标与页大小走专用占位符：SQL 文本因此与分页值无关，可以按计划形状缓存
                yield """
                        SELECT DISTINCT %s.otype, %s.oid, %s AS sort_key
                          FROM %s
                         WHERE %s > %s
                         ORDER BY sort_key
                         LIMIT %s"""
                        .formatted(alias, alias, key, derived(input, alias, emit), key,
                                marker(new Param.After(), "?::text", emit),
                                marker(new Param.Limit(), "?", emit));
            }
        };
    }

    private static String closure(Plan inner, facet.core.ir.Rel hop,
                                  facet.core.ir.ObjectType outer, Emit emit) {
        var cte = emit.next("cl");
        var out = emit.next("clo");
        var alias = emit.next("i");
        return """
                SELECT %s.otype, %s.oid
                  FROM (
                    WITH RECURSIVE %s(otype, oid) AS (
                      SELECT t.object_type, t.object_id
                        FROM facet_tuple t
                        JOIN %s
                          ON t.subject_type = %s.otype AND t.subject_id = %s.oid AND t.subject_rel = ''
                       WHERE t.relation = %s AND t.object_type = %s AND %s
                      UNION
                      SELECT t.object_type, t.object_id
                        FROM facet_tuple t
                        JOIN %s
                          ON t.subject_type = %s.otype AND t.subject_id = %s.oid AND t.subject_rel = ''
                       WHERE t.relation = %s AND t.object_type = %s AND %s
                    )
                    SELECT otype, oid FROM %s
                  ) %s"""
                .formatted(out, out,
                        cte,
                        derived(inner, alias, emit), alias, alias,
                        text(hop.name(), emit), text(outer.name(), emit), rev("t", emit),
                        cte, cte, cte,
                        text(hop.name(), emit), text(outer.name(), emit), rev("t", emit),
                        cte, out);
    }

    private static String setOp(List<Plan> inputs, String op, Emit emit) {
        var parts = new ArrayList<String>(inputs.size());
        for (var input : inputs) {
            parts.add('(' + select(input, emit) + ')');
        }
        return String.join('\n' + op + '\n', parts);
    }

    private static String derived(Plan plan, String alias, Emit emit) {
        return "(\n" + select(plan, emit) + "\n) " + alias;
    }

    private static String rev(String table, Emit emit) {
        emit.params.add(new Param.At());
        emit.params.add(new Param.At());
        return "%s.rev_from <= ? AND ? < %s.rev_to".formatted(table, table);
    }

    // ---- 条件 ----

    private static String predicate(Cond cond, String alias, Emit emit) {
        return switch (cond) {
            case Cond.Always _ -> "TRUE";
            case Cond.Not(var inner) -> "NOT (" + predicate(inner, alias, emit) + ')';
            case Cond.And(var terms) -> combine(terms, " AND ", alias, emit);
            case Cond.Or(var terms) -> combine(terms, " OR ", alias, emit);
            case Cond.Cmp cmp -> compare(cmp, alias, emit);
        };
    }

    private static String combine(List<Cond> terms, String op, String alias, Emit emit) {
        var parts = new ArrayList<String>(terms.size());
        terms.forEach(term -> parts.add(predicate(term, alias, emit)));
        return '(' + String.join(op, parts) + ')';
    }

    private static String compare(Cond.Cmp cmp, String alias, Emit emit) {
        var kind = Cond.kindOf(cmp);
        if (cmp.op() == Cond.Op.IN) {
            return in(kind, cmp.left(), cmp.right(), alias, emit);
        }
        if (cmp.op() == Cond.Op.PREFIX) {
            // starts_with 是字节前缀，与 Java 的 String.startsWith 一致；也免了 LIKE 的转义坑
            return "starts_with(%s, %s)".formatted(
                    term(cmp.left(), alias, emit), term(cmp.right(), alias, emit));
        }

        var left = normalize(kind, term(cmp.left(), alias, emit), cmp.op(), emit);
        var right = normalize(kind, term(cmp.right(), alias, emit), cmp.op(), emit);
        return switch (cmp.op()) {
            // IS [NOT] DISTINCT FROM 而不是 = / <>：内核用 Objects.equals，两个 null 相等，
            // 而 SQL 的 NULL = NULL 是 NULL。不对齐这一点，缺失属性的判定会两边不同。
            case EQ -> "%s IS NOT DISTINCT FROM %s".formatted(left, right);
            case NE -> "%s IS DISTINCT FROM %s".formatted(left, right);
            case LT -> "%s < %s".formatted(left, right);
            case LE -> "%s <= %s".formatted(left, right);
            case GT -> "%s > %s".formatted(left, right);
            case GE -> "%s >= %s".formatted(left, right);
            case IN, PREFIX -> throw new IllegalStateException("已在上面处理");
        };
    }

    /**
     * 按声明的类型归一化。
     *
     * <p>转换必须是<strong>安全</strong>的：属性列是 text，直接 {@code ::numeric} 遇到脏值
     * 会让整条查询报错，而内核那边只是判为不成立。用正则先挡一层，非法值变 NULL，
     * 比较结果随之为 NULL —— 与 {@code Conds} 的"不可比即不成立"对齐。
     *
     * <p>表达式先塞进一个派生表再引用，而不是在 CASE 里写两遍：{@code expr} 里可能带
     * {@code ?} 占位符，写两遍就会多出一个没有对应参数的占位符——这类错位极难排查。
     */
    private static String normalize(AttrKey.Kind kind, String expr, Cond.Op op, Emit emit) {
        if (kind == AttrKey.Kind.STRING) {
            // 排序规则只在序比较上有意义；等值在 PG 里本就是字节比较
            return switch (op) {
                case LT, LE, GT, GE -> "((%s) COLLATE \"C\")".formatted(expr);
                default -> "(%s)".formatted(expr);
            };
        }
        var alias = emit.next("v");
        var guard = kind == AttrKey.Kind.NUMBER
                // 守卫正则与 Conds.NUMERIC 必须逐字一致：白名单不同就意味着同一份数据
                // 在 check 与反查上给出不同判定
                ? "%s.v ~ '%s' THEN %s.v::numeric".formatted(alias, Conds.NUMERIC.pattern(), alias)
                : "lower(%s.v) IN ('true','false') THEN lower(%s.v)::boolean".formatted(alias, alias);
        return "(SELECT CASE WHEN %s END FROM (SELECT (%s) AS v) %s)".formatted(guard, expr, alias);
    }

    private static String in(AttrKey.Kind kind, Cond.Term left, Cond.Term right,
                             String alias, Emit emit) {
        if (!(right instanceof Cond.Term.Lit(Collection<?> values))) {
            throw new IllegalArgumentException("IN 的右侧必须是集合字面量: " + right);
        }
        var target = normalize(kind, term(left, alias, emit), Cond.Op.EQ, emit);
        var slots = new ArrayList<String>(values.size());
        values.forEach(value ->
                slots.add(normalize(kind, text(String.valueOf(value), emit), Cond.Op.EQ, emit)));
        return "%s IN (%s)".formatted(target, String.join(", ", slots));
    }

    private static String term(Cond.Term term, String alias, Emit emit) {
        return switch (term) {
            // Conds.text 而不是 String.valueOf：null 字面量必须绑成 SQL NULL，
            // 编成文本 'null' 会让缺失属性的 EQ/NE 在两个适配器上结果相反。
            case Cond.Term.Lit(var scalar) -> text(Conds.text(scalar), emit);
            case Cond.Term.Attr(var key) -> switch (key.tier()) {
                case AttrKey.Tier.CONTEXT -> {
                    emit.params.add(new Param.ContextAttr(key.name()));
                    yield "?::text";
                }
                case AttrKey.Tier.SNAPSHOT -> """
                        (SELECT a.value FROM facet_attr a
                          WHERE a.object_type = %s.otype AND a.object_id = %s.oid AND a.name = %s)"""
                        .formatted(alias, alias, text(key.name(), emit));
                case AttrKey.Tier.EXTERNAL -> throw new IllegalArgumentException(
                        "EXTERNAL 属性不该出现在计划里，Validator 应在加载期就拒绝: " + key.name());
            };
        };
    }

    // ---- 参数 ----

    private static String text(String value, Emit emit) {
        emit.params.add(new Param.Literal(value));
        return "?::text";
    }

    /** 占位符标记：具体值由执行期绑定，因此不进入 SQL 文本。 */
    private static String marker(Param param, String placeholder, Emit emit) {
        emit.params.add(param);
        return placeholder;
    }

    private static final class Emit {

        private final List<Param> params = new ArrayList<>();
        private int seq;

        private String next(String prefix) {
            return prefix + (++seq);
        }
    }
}

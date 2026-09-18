package facet.core.eval;

import facet.core.ir.AttrKey;
import facet.core.ir.Cond;
import facet.core.ir.ObjectType;
import facet.core.ir.Perm;
import facet.core.ir.Rel;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * schema 加载期校验。
 *
 * <p>四条硬规则，全部在启动时失败，而不是等某个接口在线上表现异常：
 * <ul>
 *   <li><strong>EXTERNAL 属性不得出现在可反查路径上。</strong>否则症状是"上线三周后某个
 *       列表接口开始超时"，而不是"启动失败，报错指出是哪条规则"。</li>
 *   <li><strong>递归环上必须至少消耗一个元组。</strong>{@code view = AnyOf[view, ...]}
 *       这种左递归在求值时是栈溢出。</li>
 *   <li><strong>递归环不得穿过 Minus 的否定侧。</strong>被否定的谓词自己也在环里时（非分层
 *       否定）没有唯一最小不动点，check 与反查会收敛到不同答案——这种偏差极难在测试里
 *       抓到。反过来，递归走 Minus 的基础侧、被否定项不在环里，是分层否定，合法。</li>
 *   <li><strong>引用与关系名必须可解析。</strong>拼错的关系名在求值时只会静默返回 deny。</li>
 * </ul>
 */
public final class Validator {

    private record Node(ObjectType type, Rel rel) {}

    private Validator() {
    }

    public static void validate(Schema schema) {
        schema.types().forEach((type, typeDef) ->
                typeDef.relations().forEach((rel, relDef) -> {
                    var where = type.name() + '#' + rel.name();
                    var path = new LinkedHashMap<Node, int[]>();
                    path.put(new Node(type, rel), new int[]{0, 0});
                    walk(schema, type, relDef.rewrite(), path, 0, 0, where);
                    if (relDef.listable()) {
                        requireReversible(schema, type, relDef.rewrite(), new HashSet<>(), where);
                    }
                }));
    }

    /**
     * 解析性与递归形状检查。
     *
     * <p>{@code hops} 记录路径上消耗过的元组数，{@code minuses} 记录进入过的否定层数。
     * 重回同一个 {@code (类型, 关系)} 时，两者与入栈时的差值就是"这个环有没有消耗元组"和
     * "这个环里有没有否定"——不需要单独建图跑 SCC。
     *
     * <p>没有 {@code default} 分支：{@code Perm} 加算子，这里立刻编译失败。
     */
    private static void walk(Schema schema, ObjectType type, Perm perm,
                             Map<Node, int[]> path, int hops, int minuses, String where) {
        switch (perm) {
            case Perm.Direct(var rel) -> schema.relation(type, rel);
            case Perm.AnyOf(var terms) ->
                    terms.forEach(term -> walk(schema, type, term, path, hops, minuses, where));
            case Perm.AllOf(var terms) ->
                    terms.forEach(term -> walk(schema, type, term, path, hops, minuses, where));
            case Perm.Minus(var base, var denied) -> {
                walk(schema, type, base, path, hops, minuses, where);
                walk(schema, type, denied, path, hops, minuses + 1, where);
            }
            case Perm.Guarded(var base, var cond) -> {
                checkCond(cond, where);
                walk(schema, type, base, path, hops, minuses, where);
            }
            case Perm.Through(var hop, var then) -> {
                var targets = schema.relation(type, hop).targets();
                if (targets.isEmpty()) {
                    throw new SchemaException(
                            where + " 的 Through(" + hop.name() + ") 未在类型 " + type.name()
                                    + " 上声明目标类型");
                }
                targets.forEach(target -> walk(schema, target, then, path, hops + 1, minuses, where));
            }
            case Perm.Ref(var rel) -> {
                var node = new Node(type, rel);
                var entry = path.get(node);
                if (entry != null) {
                    if (entry[0] == hops) {
                        throw new SchemaException(
                                where + " 的递归环没有消耗元组（" + type.name() + '#' + rel.name()
                                        + "）：这种左递归在求值时是栈溢出，"
                                        + "递归必须经过至少一个 Through");
                    }
                    if (minuses > entry[1]) {
                        throw new SchemaException(
                                where + " 的递归环穿过了 Minus 的否定侧（" + type.name() + '#'
                                        + rel.name() + "）：非分层否定没有唯一最小不动点，"
                                        + "check 与反查会收敛到不同答案");
                    }
                    return;
                }
                var def = schema.relation(type, rel);
                path.put(node, new int[]{hops, minuses});
                walk(schema, type, def.rewrite(), path, hops, minuses, where);
                path.remove(node);
            }
        }
    }

    /** 可反查性：{@code Ref} 要跟进去，否则一条递归定义里的 EXTERNAL 条件会漏过检查。 */
    private static void requireReversible(Schema schema, ObjectType type, Perm perm,
                                          Set<Node> seen, String where) {
        switch (perm) {
            case Perm.Direct _ -> {
            }
            case Perm.AnyOf(var terms) ->
                    terms.forEach(term -> requireReversible(schema, type, term, seen, where));
            case Perm.AllOf(var terms) ->
                    terms.forEach(term -> requireReversible(schema, type, term, seen, where));
            case Perm.Minus(var base, var denied) -> {
                requireReversible(schema, type, base, seen, where);
                requireReversible(schema, type, denied, seen, where);
            }
            case Perm.Through(var hop, var then) -> schema.relation(type, hop).targets()
                    .forEach(target -> requireReversible(schema, target, then, seen, where));
            case Perm.Guarded(var base, var cond) -> {
                if (Cond.tierOf(cond) == AttrKey.Tier.EXTERNAL) {
                    throw new SchemaException(
                            "EXTERNAL 属性出现在可反查路径 " + where + "：" + cond
                                    + "。它不可索引，反查只能退化成逐条外部调用。");
                }
                requireReversible(schema, type, base, seen, where);
            }
            case Perm.Ref(var rel) -> {
                if (seen.add(new Node(type, rel))) {
                    requireReversible(schema, type, schema.relation(type, rel).rewrite(), seen, where);
                }
            }
        }
    }

    /**
     * 条件的类型良构性。
     *
     * <p>三条都必须在加载期挡住：两侧属性类型不一致（比较语义无从确定）、BOOL 参与序比较、
     * PREFIX 用在非字符串上。留到查询期就是"某些数据下判定莫名其妙"。
     */
    private static void checkCond(Cond cond, String where) {
        switch (cond) {
            case Cond.Always _ -> {
            }
            case Cond.Not(var inner) -> checkCond(inner, where);
            case Cond.And(var terms) -> terms.forEach(term -> checkCond(term, where));
            case Cond.Or(var terms) -> terms.forEach(term -> checkCond(term, where));
            case Cond.Cmp cmp -> checkCmp(cmp, where);
        }
    }

    private static void checkCmp(Cond.Cmp cmp, String where) {
        if (cmp.left() instanceof Cond.Term.Attr(var left)
                && cmp.right() instanceof Cond.Term.Attr(var right)
                && left.kind() != right.kind()) {
            throw new SchemaException(where + " 的比较两侧类型不一致: "
                    + left.name() + '/' + left.kind() + " vs " + right.name() + '/' + right.kind());
        }
        var kind = Cond.kindOf(cmp);
        switch (cmp.op()) {
            case LT, LE, GT, GE -> {
                if (kind == AttrKey.Kind.BOOL) {
                    throw new SchemaException(where + " 对 BOOL 属性做了序比较: " + cmp);
                }
            }
            case PREFIX -> {
                if (kind != AttrKey.Kind.STRING) {
                    throw new SchemaException(where + " 的 PREFIX 用在非 STRING 属性上: " + cmp);
                }
            }
            case EQ, NE, IN -> {
            }
        }
    }
}

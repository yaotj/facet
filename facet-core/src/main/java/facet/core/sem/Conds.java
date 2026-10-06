package facet.core.sem;

import facet.core.ir.AttrKey;
import facet.core.ir.Cond;
import facet.core.ir.ObjectRef;
import facet.core.spi.AttrSource;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

/**
 * 条件求值与属性值归一化。
 *
 * <p>放在这里而不是 {@code Checker} 内部，因为反查计划里的 {@code Plan.Filter} 也要用
 * 同一份语义——两处实现分叉的话，check 和 list 会给出不同答案，而这种偏差极难发现。
 *
 * <p>比较前一律按 {@link AttrKey.Kind} 归一化。这是跨适配器一致性的关键：属性在库里是
 * 文本，在请求上下文里是任意 Java 对象，只有先归一到同一类型，才能保证内存实现与
 * SQL 的 {@code ::numeric} / {@code COLLATE "C"} 得出相同结论。{@link #text}、
 * {@link #number}、{@link #bool} 三个归一化函数是<strong>公开契约</strong>，
 * 适配器必须复用它们而不是各写一份。
 */
public final class Conds {

    /**
     * 可被识别为数值的文本形式。
     *
     * <p>刻意比 {@code BigDecimal} 的构造器更严：后者接受 {@code 1e3}、{@code +3}、
     * {@code .5}，而 SQL 侧的守卫正则不接受。两边的白名单必须逐字一致，否则同一份数据
     * 在 check 与反查上会得出不同判定。这条正则与
     * {@code PlanSqlCompiler} 里那条是同一份，改一处必须改两处。
     */
    public static final Pattern NUMERIC = Pattern.compile("^-?[0-9]+(\\.[0-9]+)?$");

    private Conds() {
    }

    /** 求值一个条件。 */
    public static boolean eval(Cond cond, ObjectRef obj, AttrSource attrs, Map<String, Object> contextAttrs) {
        return switch (cond) {
            case Cond.Always _ -> true;
            case Cond.Not(var inner) -> !eval(inner, obj, attrs, contextAttrs);
            case Cond.And(var terms) -> terms.stream().allMatch(t -> eval(t, obj, attrs, contextAttrs));
            case Cond.Or(var terms) -> terms.stream().anyMatch(t -> eval(t, obj, attrs, contextAttrs));
            case Cond.Cmp cmp -> compare(cmp, obj, attrs, contextAttrs);
        };
    }

    private static boolean compare(Cond.Cmp cmp, ObjectRef obj, AttrSource attrs,
                                   Map<String, Object> contextAttrs) {
        var kind = Cond.kindOf(cmp);
        var left = resolve(cmp.left(), obj, attrs, contextAttrs);

        if (cmp.op() == Cond.Op.IN) {
            if (!(cmp.right() instanceof Cond.Term.Lit(Collection<?> values))) {
                throw new IllegalArgumentException("IN 的右侧必须是集合字面量: " + cmp.right());
            }
            return values.stream().anyMatch(value -> equal(kind, left, value));
        }

        var right = resolve(cmp.right(), obj, attrs, contextAttrs);
        return switch (cmp.op()) {
            case EQ -> equal(kind, left, right);
            case NE -> !equal(kind, left, right);
            case LT -> ordered(kind, left, right, c -> c < 0);
            case LE -> ordered(kind, left, right, c -> c <= 0);
            case GT -> ordered(kind, left, right, c -> c > 0);
            case GE -> ordered(kind, left, right, c -> c >= 0);
            case PREFIX -> prefix(kind, left, right);
            case IN -> throw new IllegalStateException("IN 已在上面处理");
        };
    }

    private static Object resolve(Cond.Term term, ObjectRef obj, AttrSource attrs,
                                  Map<String, Object> contextAttrs) {
        return switch (term) {
            case Cond.Term.Lit(var scalar) -> scalar;
            case Cond.Term.Attr(var key) -> switch (key.tier()) {
                case AttrKey.Tier.CONTEXT -> contextAttrs.get(key.name());
                case AttrKey.Tier.SNAPSHOT, AttrKey.Tier.EXTERNAL -> attrs.value(key, obj);
            };
        };
    }

    private static boolean equal(AttrKey.Kind kind, Object left, Object right) {
        return switch (kind) {
            case STRING -> Objects.equals(text(left), text(right));
            // compareTo 而不是 equals：BigDecimal.equals 比较 scale，"3.0" 与 3 会判为不等，
            // 而 SQL 的 numeric 等值是纯数值比较。用 equals 就等于让两个适配器给出不同判定。
            case NUMBER -> {
                var l = number(left);
                var r = number(right);
                yield l == null || r == null ? l == r : l.compareTo(r) == 0;
            }
            case BOOL -> Objects.equals(bool(left), bool(right));
        };
    }

    /**
     * 序比较。缺失或无法归一的值一律判为不成立——不抛异常，也不当成零。
     *
     * <p>"不可比"用 null 表示而不是某个哨兵整数：哨兵值总会在四个方向里意外满足一个，
     * 那种 bug 只在缺失属性的数据上才现形。
     */
    private static boolean ordered(AttrKey.Kind kind, Object left, Object right, IntPredicate test) {
        var comparison = order(kind, left, right);
        return comparison != null && test.test(comparison);
    }

    private static Integer order(AttrKey.Kind kind, Object left, Object right) {
        return switch (kind) {
            case STRING -> {
                var l = text(left);
                var r = text(right);
                yield l == null || r == null ? null : Integer.signum(Keys.compare(l, r));
            }
            case NUMBER -> {
                var l = number(left);
                var r = number(right);
                yield l == null || r == null ? null : Integer.signum(l.compareTo(r));
            }
            case BOOL -> throw new IllegalArgumentException(
                    "BOOL 属性不支持序比较，Validator 应在加载期拒绝");
        };
    }

    private static boolean prefix(AttrKey.Kind kind, Object left, Object right) {
        if (kind != AttrKey.Kind.STRING) {
            throw new IllegalArgumentException("PREFIX 只支持 STRING 属性，Validator 应在加载期拒绝");
        }
        var l = text(left);
        var r = text(right);
        return l != null && r != null && l.startsWith(r);
    }

    /** 归一化成文本。{@code null} 保持 {@code null}，不变成字符串 {@code "null"}。 */
    public static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 归一化成数值。无法识别的值返回 {@code null}，与 SQL 侧守卫正则不匹配时的 NULL 对齐。 */
    public static BigDecimal number(Object value) {
        if (value == null) {
            return null;
        }
        var text = String.valueOf(value);
        return NUMERIC.matcher(text).matches() ? new BigDecimal(text) : null;
    }

    /** 归一化成布尔。只认 {@code true}/{@code false}（忽略大小写），其余返回 {@code null}。 */
    public static Boolean bool(Object value) {
        return switch (value) {
            case null -> null;
            case Boolean b -> b;
            case Object other -> {
                var s = String.valueOf(other);
                yield "true".equalsIgnoreCase(s) ? Boolean.TRUE
                        : "false".equalsIgnoreCase(s) ? Boolean.FALSE : null;
            }
        };
    }
}

package facet.core.schema;

import facet.core.ir.ObjectType;
import facet.core.ir.Perm;
import facet.core.ir.Rel;

import java.util.Map;
import java.util.Set;

/**
 * schema：类型 → 关系定义环境。
 *
 * <p>这里<strong>不</strong>区分"关系"和"权限"——Zanzibar 也不区分。一条关系既可能持有原始
 * 元组，也可能有一份 rewrite 定义；{@code Perm.Ref} 指回的就是这份定义，递归由此闭合。
 * 拆成两个命名空间只会让 {@code Ref} 需要知道自己指向哪一边。
 *
 * <p>{@link RelDef#listable()} 是刻意要求前端显式声明的：能不能反查是个架构事实，不该由
 * "有没有人调过 lookupResources" 事后决定。声明了就要在加载期通过可反查性检查。
 */
public record Schema(Map<ObjectType, TypeDef> types) {

    /** 拷贝成不可变：schema 在加载期定型，之后被所有请求并发读，任何事后改动都是竞态。 */
    public Schema {
        types = Map.copyOf(types);
    }

    /**
     * 一个对象类型的关系环境。没有摊平成 {@code Map<ObjectType, Map<Rel, RelDef>>}：类型级的校验
     * 需要一个挂载点，摊平后每处都得重复"这个类型存在吗"的判断。
     *
     * @param relations 关系名 → 定义。缺失即"该类型上没有这条关系"，不是"默认拒绝"
     */
    public record TypeDef(Map<Rel, RelDef> relations) {

        /** 同 {@link Schema} 的理由：加载期定型，此后全程只读。 */
        public TypeDef {
            relations = Map.copyOf(relations);
        }
    }

    /**
     * @param targets  该关系的原始元组里，主体侧可以是哪些对象类型。{@code Through} 的
     *                 反查编译必须知道跳到哪个类型上，否则只能全表扫。
     * @param rewrite  该关系的定义。纯存储关系就是 {@code Direct(自己)}。
     * @param listable 是否声明可反查。
     */
    public record RelDef(Set<ObjectType> targets, Perm rewrite, boolean listable) {

        /** {@code targets} 定型成不可变：{@link Planner} 的 {@code Through} 展开与 {@link Validator} 都要反复遍历它。 */
        public RelDef {
            targets = Set.copyOf(targets);
        }
    }

    /** 纯存储关系：只有原始元组，没有 rewrite。 */
    public static RelDef tuples(Rel rel, ObjectType... targets) {
        return new RelDef(Set.of(targets), new Perm.Direct(rel), false);
    }

    /** 计算关系：有 rewrite 定义。 */
    public static RelDef computed(Perm rewrite, boolean listable) {
        return new RelDef(Set.of(), rewrite, listable);
    }

    /** 未声明的类型抛出而不是当成"没有任何关系"：静默 deny 会把 schema 拼写错误伪装成一次正常判定。 */
    public TypeDef type(ObjectType type) {
        var def = types.get(type);
        if (def == null) {
            throw new SchemaException("未声明的对象类型: " + type.name());
        }
        return def;
    }

    /**
     * 同样对拼错的关系名快速失败。{@link Validator} 已在加载期解析过 schema 内部的全部引用，
     * 这里挡的是调用方在运行时传进来的 {@link Rel}。
     */
    public RelDef relation(ObjectType type, Rel rel) {
        var def = type(type).relations().get(rel);
        if (def == null) {
            throw new SchemaException("类型 " + type.name() + " 上没有关系 " + rel.name());
        }
        return def;
    }
}

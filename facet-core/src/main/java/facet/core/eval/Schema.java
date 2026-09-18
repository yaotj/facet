package facet.core.eval;

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

    public Schema {
        types = Map.copyOf(types);
    }

    public record TypeDef(Map<Rel, RelDef> relations) {

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

    public TypeDef type(ObjectType type) {
        var def = types.get(type);
        if (def == null) {
            throw new SchemaException("未声明的对象类型: " + type.name());
        }
        return def;
    }

    public RelDef relation(ObjectType type, Rel rel) {
        var def = type(type).relations().get(rel);
        if (def == null) {
            throw new SchemaException("类型 " + type.name() + " 上没有关系 " + rel.name());
        }
        return def;
    }
}

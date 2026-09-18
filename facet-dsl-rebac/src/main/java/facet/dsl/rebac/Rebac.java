package facet.dsl.rebac;

import facet.core.eval.Schema;
import facet.core.eval.Validator;
import facet.core.ir.Cond;
import facet.core.ir.ObjectType;
import facet.core.ir.Perm;
import facet.core.ir.Rel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * ReBAC 前端：把关系模型写成 Java，编译到 {@code Perm} / {@code Schema}。
 *
 * <p>选 builder 而不是文本 DSL（ADR-008）：零依赖、编译期类型安全、IDE 能补全，而且不需要
 * 先定一套 IR 序列化格式。代价是策略变更要跟着发版——对库来说这是可接受的；需要热加载时
 * 再加一个文本前端，它同样只输出 {@code Perm}，内核不用动。
 *
 * <p>典型用法：
 * {@snippet :
 * var schema = Rebac.define()
 *         .type("group", t -> t.tuples("member"))
 *         .type("folder", t -> t
 *                 .tuples("viewer")
 *                 .tuples("parent", "folder")
 *                 .listable("view", Rebac.anyOf(
 *                         Rebac.direct("viewer"),
 *                         Rebac.through("parent", Rebac.ref("view")))))
 *         .build();
 * }
 */
public final class Rebac {

    private Rebac() {
    }

    public static Builder define() {
        return new Builder();
    }

    // ---- 表达式 ----

    /** 原始元组 {@code obj#rel@subject}。 */
    public static Perm direct(String rel) {
        return new Perm.Direct(new Rel(rel));
    }

    /** 同类型上另一条关系的定义。递归就靠它。 */
    public static Perm ref(String rel) {
        return new Perm.Ref(new Rel(rel));
    }

    /** 沿 {@code hop} 跳到目标对象，再在那里求 {@code then}。 */
    public static Perm through(String hop, Perm then) {
        return new Perm.Through(new Rel(hop), then);
    }

    public static Perm anyOf(Perm... terms) {
        return new Perm.AnyOf(List.of(terms));
    }

    public static Perm allOf(Perm... terms) {
        return new Perm.AllOf(List.of(terms));
    }

    /** deny。单调，上层不可恢复。 */
    public static Perm minus(Perm base, Perm denied) {
        return new Perm.Minus(base, denied);
    }

    /** ABAC 的挂载点。 */
    public static Perm guarded(Perm base, Cond cond) {
        return new Perm.Guarded(base, cond);
    }

    public static final class Builder {

        private final Map<ObjectType, Schema.TypeDef> types = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder type(String name, Consumer<TypeBuilder> config) {
            var typeBuilder = new TypeBuilder();
            config.accept(typeBuilder);
            var type = new ObjectType(name);
            if (types.putIfAbsent(type, new Schema.TypeDef(typeBuilder.relations)) != null) {
                throw new IllegalArgumentException("重复声明的类型: " + name);
            }
            return this;
        }

        /** 构建即校验：引用可解析、递归形状合法、可反查路径上没有 EXTERNAL 属性。 */
        public Schema build() {
            var schema = new Schema(types);
            Validator.validate(schema);
            return schema;
        }
    }

    public static final class TypeBuilder {

        private final Map<Rel, Schema.RelDef> relations = new HashMap<>();

        private TypeBuilder() {
        }

        /**
         * 纯存储关系：只有原始元组。
         *
         * @param targetTypes 该关系的主体侧可以是哪些对象类型；作为 {@code through} 的 hop 时必填
         */
        public TypeBuilder tuples(String rel, String... targetTypes) {
            var name = new Rel(rel);
            // 走 Schema 的工厂而不是自己 new RelDef："纯存储关系 = Direct(自己)" 这条约定
            // 只该有一份定义，RelDef 加字段时才不会静默偏离
            return put(name, Schema.tuples(name, objectTypes(targetTypes)));
        }

        /** 计算关系，不可反查。 */
        public TypeBuilder computed(String rel, Perm rewrite) {
            return put(new Rel(rel), Schema.computed(rewrite, false));
        }

        /** 计算关系，声明可反查——加载期会检查它真的可反查。 */
        public TypeBuilder listable(String rel, Perm rewrite) {
            return put(new Rel(rel), Schema.computed(rewrite, true));
        }

        private TypeBuilder put(Rel rel, Schema.RelDef def) {
            if (relations.putIfAbsent(rel, def) != null) {
                throw new IllegalArgumentException("重复声明的关系: " + rel.name());
            }
            return this;
        }

        private static ObjectType[] objectTypes(String... names) {
            var out = new ObjectType[names.length];
            for (int i = 0; i < names.length; i++) {
                out[i] = new ObjectType(names[i]);
            }
            return out;
        }
    }
}

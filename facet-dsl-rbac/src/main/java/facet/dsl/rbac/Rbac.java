package facet.dsl.rbac;

import facet.core.eval.Schema;
import facet.core.eval.Validator;
import facet.core.ir.ObjectType;
import facet.core.ir.Perm;
import facet.core.ir.Rel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RBAC 前端：角色、角色继承、角色到权限的映射，编译到同一份 {@code Perm} IR。
 *
 * <p>关键是角色继承<strong>不</strong>在前端展开成笛卡尔积，而是编译成 {@code Ref}：
 * {@code viewer = Direct(viewer) | Ref(editor)}，{@code editor = Direct(editor) | Ref(admin)}。
 * 展开成"admin 也要写一条 viewer 元组"会让角色调整变成数据迁移；编成引用之后，
 * 调整继承关系只是改 schema。
 *
 * <p>典型用法：
 * {@snippet :
 * var schema = Rbac.on("doc")
 *         .roles("viewer", "editor", "admin")
 *         .inherits("editor", "viewer")
 *         .inherits("admin", "editor")
 *         .permission("read", "viewer")
 *         .permission("write", "editor")
 *         .permission("delete", "admin")
 *         .build();
 * }
 */
public final class Rbac {

    private Rbac() {
    }

    /** 开始声明某个对象类型上的角色模型。一次 {@code build()} 只产出这一个类型，跨类型请各自声明。 */
    public static Builder on(String objectType) {
        return new Builder(new ObjectType(objectType));
    }

    /** 角色与权限的声明。声明顺序被保留，好让编译出的 schema 与 golden 输出稳定可 diff。 */
    public static final class Builder {

        private final ObjectType type;
        /** 角色 → 直接继承它的上级角色。 */
        private final Map<String, Set<String>> superiors = new LinkedHashMap<>();
        private final Map<String, String> permissions = new LinkedHashMap<>();

        private Builder(ObjectType type) {
            this.type = type;
        }

        /** 先声明角色再谈继承与权限：{@code inherits} / {@code permission} 只接受已声明的名字，拼错当场报错。 */
        public Builder roles(String... names) {
            for (var name : names) {
                if (superiors.putIfAbsent(name, new LinkedHashSet<>()) != null) {
                    throw new IllegalArgumentException("重复声明的角色: " + name);
                }
            }
            return this;
        }

        /** {@code senior} 拥有 {@code junior} 的一切。 */
        public Builder inherits(String senior, String junior) {
            require(senior);
            require(junior);
            if (senior.equals(junior)) {
                throw new IllegalArgumentException("角色不能继承自己: " + senior);
            }
            superiors.get(junior).add(senior);
            return this;
        }

        /** 权限由某个角色（及其所有上级）拥有。 */
        public Builder permission(String name, String role) {
            require(role);
            if (superiors.containsKey(name)) {
                throw new IllegalArgumentException("权限名与角色名冲突: " + name);
            }
            if (permissions.putIfAbsent(name, role) != null) {
                throw new IllegalArgumentException("重复声明的权限: " + name);
            }
            return this;
        }

        /**
         * 构建即校验。继承成环会被 {@code Validator} 当作"无元组消耗的递归"拒绝——
         * 角色继承确实不该有环，这里借的是同一条规则。
         */
        public Schema build() {
            var relations = new LinkedHashMap<Rel, Schema.RelDef>();
            superiors.forEach((role, seniors) -> {
                var rel = new Rel(role);
                var terms = new ArrayList<Perm>();
                terms.add(new Perm.Direct(rel));
                seniors.forEach(senior -> terms.add(new Perm.Ref(new Rel(senior))));
                var rewrite = terms.size() == 1 ? terms.getFirst() : new Perm.AnyOf(terms);
                relations.put(rel, Schema.computed(rewrite, true));
            });
            permissions.forEach((name, role) -> relations.put(new Rel(name),
                    Schema.computed(new Perm.Ref(new Rel(role)), true)));

            var schema = new Schema(Map.of(type, new Schema.TypeDef(relations)));
            Validator.validate(schema);
            return schema;
        }

        private void require(String role) {
            if (!superiors.containsKey(role)) {
                throw new IllegalArgumentException("未声明的角色: " + role
                        + "，已声明: " + List.copyOf(superiors.keySet()));
            }
        }
    }
}

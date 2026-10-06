package facet.ir.json;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import facet.core.schema.Schema;
import facet.core.schema.Validator;
import facet.core.ir.AttrKey;
import facet.core.ir.Cond;
import facet.core.ir.ObjectType;
import facet.core.ir.Perm;
import facet.core.ir.Rel;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code Schema} 与 JSON 的双向编解码。
 *
 * <p>手写 tagged union，只用 Jackson 的树模型——IR 的 record 不挂任何注解。挂注解等于把
 * "用哪个 JSON 库"这个部署决策焊进内核，而内核的零依赖是刻意守住的边界。
 *
 * <p>三条格式约定：
 * <ul>
 *   <li><strong>算子用显式 {@code op} 标签</strong>，不靠字段形状去猜。猜错的后果是解析出
 *       一个语义不同的策略，而不是报错。</li>
 *   <li><strong>未知 {@code op} 必须报错</strong>，绝不跳过。跳过一个算子会得到比原意
 *       更宽松或更严格的策略，两种都是安全事故。</li>
 *   <li><strong>带 {@code version}</strong>。将来算子集变化时，旧 PDP 读到新版本要能明确
 *       拒绝，而不是尽力解析出一个残缺策略。</li>
 * </ul>
 *
 * <p>{@link #decode} 解码后立刻跑 {@link Validator}：从进程外来的 schema 恰恰是加载期
 * 那几条规则最需要生效的地方（EXTERNAL 进可反查路径、无元组消耗的递归、非分层否定）。
 *
 * <p>往返保证的是<strong>语义</strong>而不是对象同一性。JSON 的数字没有 Java 类型信息，
 * {@code Lit(3)} 解码回来是 {@code Lit(BigDecimal("3"))}；两者在 {@code Conds} 里按
 * {@code Kind} 归一化后完全等价，但 {@code equals} 不成立。字符串与布尔字面量则精确往返。
 */
public final class SchemaJson {

    /** 当前线格式版本。算子集变化时必须递增。 */
    public static final int VERSION = 1;

    private static final ObjectMapper JSON = new ObjectMapper();

    private SchemaJson() {
    }

    // ---- 编码 ----

    public static String encode(Schema schema) {
        var root = JSON.createObjectNode();
        root.put("version", VERSION);
        var types = root.putObject("types");
        schema.types().forEach((type, typeDef) -> {
            var relations = types.putObject(type.name());
            typeDef.relations().forEach((rel, def) -> relations.set(rel.name(), encode(def)));
        });
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JacksonException e) {
            throw new SchemaJsonException("序列化 schema 失败", e);
        }
    }

    private static ObjectNode encode(Schema.RelDef def) {
        var node = JSON.createObjectNode();
        if (!def.targets().isEmpty()) {
            var targets = node.putArray("targets");
            // 排序：Set 的迭代顺序不确定，不排的话同一份 schema 会编出不同的字节
            def.targets().stream().map(ObjectType::name).sorted().forEach(targets::add);
        }
        if (def.listable()) {
            node.put("listable", true);
        }
        node.set("rewrite", encode(def.rewrite()));
        return node;
    }

    /** 没有 {@code default} 分支：{@code Perm} 加算子，编码这里立刻编译失败。 */
    private static ObjectNode encode(Perm perm) {
        var node = JSON.createObjectNode();
        switch (perm) {
            case Perm.Direct(var rel) -> node.put("op", "direct").put("rel", rel.name());
            case Perm.Ref(var rel) -> node.put("op", "ref").put("rel", rel.name());
            case Perm.Through(var hop, var then) -> {
                node.put("op", "through").put("hop", hop.name());
                node.set("then", encode(then));
            }
            case Perm.AnyOf(var terms) -> encodeTerms(node, "anyOf", terms);
            case Perm.AllOf(var terms) -> encodeTerms(node, "allOf", terms);
            case Perm.Minus(var base, var denied) -> {
                node.put("op", "minus");
                node.set("base", encode(base));
                node.set("denied", encode(denied));
            }
            case Perm.Guarded(var base, var cond) -> {
                node.put("op", "guarded");
                node.set("base", encode(base));
                node.set("cond", encode(cond));
            }
        }
        return node;
    }

    private static void encodeTerms(ObjectNode node, String op, List<Perm> terms) {
        node.put("op", op);
        var array = node.putArray("terms");
        terms.forEach(term -> array.add(encode(term)));
    }

    /** 没有 {@code default} 分支：{@code Cond} 加算子，编码这里立刻编译失败。 */
    private static ObjectNode encode(Cond cond) {
        var node = JSON.createObjectNode();
        switch (cond) {
            case Cond.Always _ -> node.put("op", "always");
            case Cond.Not(var inner) -> {
                node.put("op", "not");
                node.set("cond", encode(inner));
            }
            case Cond.And(var terms) -> encodeConds(node, "and", terms);
            case Cond.Or(var terms) -> encodeConds(node, "or", terms);
            case Cond.Cmp(var op, var left, var right) -> {
                node.put("op", "cmp").put("cmp", op.name());
                node.set("left", encode(left));
                node.set("right", encode(right));
            }
        }
        return node;
    }

    private static void encodeConds(ObjectNode node, String op, List<Cond> terms) {
        node.put("op", op);
        var array = node.putArray("terms");
        terms.forEach(term -> array.add(encode(term)));
    }

    private static ObjectNode encode(Cond.Term term) {
        var node = JSON.createObjectNode();
        switch (term) {
            case Cond.Term.Attr(var key) -> node.set("attr", JSON.createObjectNode()
                    .put("name", key.name())
                    .put("kind", key.kind().name())
                    .put("tier", key.tier().name()));
            case Cond.Term.Lit(var scalar) -> node.set("lit", literal(scalar));
        }
        return node;
    }

    private static JsonNode literal(Object scalar) {
        return switch (scalar) {
            case null -> JSON.getNodeFactory().nullNode();
            case Boolean b -> JSON.getNodeFactory().booleanNode(b);
            case BigDecimal d -> JSON.getNodeFactory().numberNode(d);
            case Number n -> JSON.getNodeFactory().numberNode(new BigDecimal(n.toString()));
            case String s -> JSON.getNodeFactory().textNode(s);
            case Iterable<?> items -> {
                var array = JSON.createArrayNode();
                items.forEach(item -> array.add(literal(item)));
                yield array;
            }
            // 不兜底成 toString：那会把一个类型错误变成一条语义不同的策略
            case Object other -> throw new SchemaJsonException(
                    "不支持的字面量类型 " + other.getClass().getName() + "；条件只比较标量与集合");
        };
    }

    // ---- 解码 ----

    public static Schema decode(byte[] json) {
        return decode(new String(json, StandardCharsets.UTF_8));
    }

    /** 解码并跑加载期校验：从进程外来的 schema 更需要那几条规则生效。 */
    public static Schema decode(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new SchemaJsonException("不是合法 JSON", e);
        }
        int version = require(root, "version").asInt();
        if (version != VERSION) {
            throw new SchemaJsonException(
                    "线格式版本不匹配：收到 " + version + "，本进程支持 " + VERSION
                            + "。宁可拒绝，也不解析出一个残缺策略。");
        }
        var types = new LinkedHashMap<ObjectType, Schema.TypeDef>();
        require(root, "types").properties().forEach(typeEntry -> {
            var relations = new LinkedHashMap<Rel, Schema.RelDef>();
            typeEntry.getValue().properties().forEach(relEntry ->
                    relations.put(new Rel(relEntry.getKey()), relDef(relEntry.getValue())));
            types.put(new ObjectType(typeEntry.getKey()), new Schema.TypeDef(relations));
        });

        var schema = new Schema(types);
        Validator.validate(schema);
        return schema;
    }

    private static Schema.RelDef relDef(JsonNode node) {
        var targets = new LinkedHashSet<ObjectType>();
        var declared = node.get("targets");
        if (declared != null) {
            declared.forEach(target -> targets.add(new ObjectType(target.asText())));
        }
        boolean listable = node.has("listable") && node.get("listable").asBoolean();
        return new Schema.RelDef(Set.copyOf(targets), perm(require(node, "rewrite")), listable);
    }

    private static Perm perm(JsonNode node) {
        var op = require(node, "op").asText();
        return switch (op) {
            case "direct" -> new Perm.Direct(new Rel(require(node, "rel").asText()));
            case "ref" -> new Perm.Ref(new Rel(require(node, "rel").asText()));
            case "through" -> new Perm.Through(
                    new Rel(require(node, "hop").asText()), perm(require(node, "then")));
            case "anyOf" -> new Perm.AnyOf(perms(node));
            case "allOf" -> new Perm.AllOf(perms(node));
            case "minus" -> new Perm.Minus(perm(require(node, "base")), perm(require(node, "denied")));
            case "guarded" -> new Perm.Guarded(perm(require(node, "base")), cond(require(node, "cond")));
            // 未知算子必须报错：跳过它会得到一份比原意更宽或更严的策略，两种都是事故
            default -> throw new SchemaJsonException("未知的权限算子: " + op);
        };
    }

    private static List<Perm> perms(JsonNode node) {
        var out = new ArrayList<Perm>();
        require(node, "terms").forEach(term -> out.add(perm(term)));
        return out;
    }

    private static Cond cond(JsonNode node) {
        var op = require(node, "op").asText();
        return switch (op) {
            case "always" -> new Cond.Always();
            case "not" -> new Cond.Not(cond(require(node, "cond")));
            case "and" -> new Cond.And(conds(node));
            case "or" -> new Cond.Or(conds(node));
            case "cmp" -> new Cond.Cmp(
                    operator(require(node, "cmp").asText()),
                    term(require(node, "left")),
                    term(require(node, "right")));
            default -> throw new SchemaJsonException("未知的条件算子: " + op);
        };
    }

    private static List<Cond> conds(JsonNode node) {
        var out = new ArrayList<Cond>();
        require(node, "terms").forEach(term -> out.add(cond(term)));
        return out;
    }

    private static Cond.Op operator(String name) {
        try {
            return Cond.Op.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new SchemaJsonException("未知的比较运算符: " + name);
        }
    }

    private static Cond.Term term(JsonNode node) {
        var attr = node.get("attr");
        if (attr != null) {
            return new Cond.Term.Attr(new AttrKey(
                    require(attr, "name").asText(),
                    enumValue(AttrKey.Kind.class, require(attr, "kind").asText()),
                    enumValue(AttrKey.Tier.class, require(attr, "tier").asText())));
        }
        if (node.has("lit")) {
            return new Cond.Term.Lit(literal(node.get("lit")));
        }
        throw new SchemaJsonException("比较项既不是 attr 也不是 lit: " + node);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new SchemaJsonException("未知的 " + type.getSimpleName() + ": " + name);
        }
    }

    /**
     * JSON 标量还原成 Java 值。
     *
     * <p>数字统一成 {@code BigDecimal}：JSON 不带 Java 类型信息，而 {@code Conds} 的数值
     * 比较本来就走 {@code BigDecimal}，所以这样还原在语义上是精确的。
     */
    private static Object literal(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node instanceof ArrayNode array) {
            var out = new ArrayList<>();
            array.forEach(item -> out.add(literal(item)));
            return List.copyOf(out);
        }
        throw new SchemaJsonException("字面量只能是标量或数组: " + node);
    }

    private static JsonNode require(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null) {
            throw new SchemaJsonException("缺少字段 " + field + ": " + node);
        }
        return value;
    }
}

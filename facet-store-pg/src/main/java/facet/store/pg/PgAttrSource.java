package facet.store.pg;

import facet.core.ir.AttrKey;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.spi.AttrSource;

import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SNAPSHOT 属性源。
 *
 * <p>属性值以 {@code text} 存储，读回来也是 {@code text}——比较语义由 {@code AttrKey.Kind}
 * 决定，{@code Conds} 与 SQL 两端各自按同一个声明归一化，所以写入时不必保留 Java 类型。
 *
 * <p>EXTERNAL 明确不支持：数据库不是 PIP，把外部系统的属性塞进元组库只是把耦合藏起来。
 */
public final class PgAttrSource implements AttrSource {

    private final Connections connections;

    public PgAttrSource(Connections connections) {
        this.connections = connections;
    }

    public void put(ObjectRef obj, AttrKey key, Object value) {
        if (key.tier() != AttrKey.Tier.SNAPSHOT) {
            throw new IllegalArgumentException("只存 SNAPSHOT 属性: " + key.name() + '/' + key.tier());
        }
        if (value == null) {
            // value 列是 NOT NULL；不在这里挡就要等到约束违例才发现，且错误信息毫无线索
            throw new IllegalArgumentException("属性值不能为 null: " + key.name());
        }
        var sql = """
                INSERT INTO facet_attr (object_type, object_id, name, value) VALUES (?, ?, ?, ?)
                ON CONFLICT (object_type, object_id, name) DO UPDATE SET value = EXCLUDED.value""";
        try (var conn = connections.get(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, obj.type().name());
            ps.setString(2, obj.id());
            ps.setString(3, key.name());
            ps.setString(4, Rows.text(value));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PgException("写入属性失败", e);
        }
    }

    @Override
    public Object value(AttrKey key, ObjectRef obj) {
        return switch (key.tier()) {
            case CONTEXT -> throw new IllegalArgumentException(
                    "CONTEXT 属性由请求自带，不经过 AttrSource: " + key.name());
            case EXTERNAL -> throw new UnsupportedOperationException(
                    "PG 适配器不是 PIP，不提供 EXTERNAL 属性: " + key.name());
            case SNAPSHOT -> read(key, obj);
        };
    }

    private Object read(AttrKey key, ObjectRef obj) {
        var sql = """
                SELECT value FROM facet_attr
                 WHERE object_type = ? AND object_id = ? AND name = ?""";
        try (var conn = connections.get(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, obj.type().name());
            ps.setString(2, obj.id());
            ps.setString(3, key.name());
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new PgException("读取属性失败", e);
        }
    }

    /**
     * 批量读取：一条 {@code IN} 查询取回整批。
     *
     * <p>批量判定必须走这条路。默认实现会逐条发查询，一次"这 200 个文档我能看哪些"
     * 就是 200 次往返——这正是 {@code Plan} 那套下推要消灭的东西，check 路径上也不该留。
     */
    @Override
    public Map<ObjectRef, Object> values(AttrKey key, Collection<ObjectRef> objects) {
        if (key.tier() != AttrKey.Tier.SNAPSHOT) {
            // CONTEXT 由请求自带，EXTERNAL 不该由数据库回答；两者都走单条路径去抛错
            return AttrSource.super.values(key, objects);
        }
        if (objects.isEmpty()) {
            return Map.of();
        }
        // (object_type, object_id) 成对匹配：拆成两个 IN 会把不同对象的类型与 id 交叉组合
        var placeholders = String.join(", ", java.util.Collections.nCopies(objects.size(), "(?, ?)"));
        var sql = """
                SELECT object_type, object_id, value FROM facet_attr
                 WHERE name = ? AND (object_type, object_id) IN (%s)""".formatted(placeholders);
        try (var conn = connections.get(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, key.name());
            int index = 2;
            for (var obj : objects) {
                ps.setString(index++, obj.type().name());
                ps.setString(index++, obj.id());
            }
            var out = new LinkedHashMap<ObjectRef, Object>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(new ObjectRef(new ObjectType(rs.getString(1)), rs.getString(2)),
                            rs.getString(3));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new PgException("批量读取属性失败", e);
        }
    }
}

package facet.store.pg;

import facet.core.ir.AttrKey;
import facet.core.ir.ObjectRef;
import facet.core.spi.AttrSource;

import java.sql.SQLException;

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
}

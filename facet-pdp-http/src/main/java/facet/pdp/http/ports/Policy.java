package facet.pdp.http.ports;

import facet.core.eval.Checker;
import facet.core.eval.Expander;
import facet.core.eval.Planner;
import facet.core.schema.Schema;
import facet.core.spi.AttrSource;
import facet.core.spi.TupleSource;

/**
 * 服务端对内核求值器的装配端口。
 *
 * <p>从一份已通过 {@code Validator} 的 {@link Schema} 派生出四个求值器：判定、规划、展开，
 * 以及 Schema 本身。四个必须同时替换——新 schema 配旧 {@link Planner} 会按旧语义回答。
 * 把这段装配逻辑从 {@code PdpServer} 挪出来，服务器只持有"当前生效的策略"而不必知道它怎么造，
 * 也方便测试直接装一份策略而不必走完整配置。
 */
public record Policy(Schema schema, Checker checker, Planner planner, Expander expander) {

    /** 由 schema 与存储适配器装配出一份策略。 */
    public static Policy of(Schema schema, TupleSource tuples, AttrSource attrs) {
        return new Policy(schema,
                new Checker(schema, tuples, attrs),
                new Planner(schema, tuples.caps()),
                new Expander(schema, tuples, attrs));
    }
}

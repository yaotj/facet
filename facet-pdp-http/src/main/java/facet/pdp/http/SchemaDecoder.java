package facet.pdp.http;

import facet.core.eval.Schema;

/**
 * schema 解码器。
 *
 * <p>做成端口而不是让 PDP 直接依赖某个编解码模块：线格式是部署决策。默认拒绝，
 * 于是"这个 PDP 接不接受远程下发策略"是一个显式选择，而不是一个被默认打开的写入面——
 * 能改 schema 就等于能改写整套授权语义，比改元组的影响面还大。
 *
 * <p>{@code facet-ir-json} 的 {@code SchemaJson::decode} 直接可用作实现。
 */
@FunctionalInterface
public interface SchemaDecoder {

    /**
     * 解码并校验。
     *
     * <p>实现必须跑 {@code Validator}：从进程外来的 schema 恰恰是加载期规则最该生效的地方。
     */
    Schema decode(byte[] body);

    /** 不接受远程下发。schema 端点会返回 405，而不是静默忽略。 */
    SchemaDecoder DENIED = body -> {
        throw new UnsupportedOperationException("该 PDP 不接受远程下发 schema");
    };
}

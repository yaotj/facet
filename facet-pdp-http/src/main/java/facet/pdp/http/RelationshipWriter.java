package facet.pdp.http;

import facet.core.ir.Revision;
import facet.core.ir.Tuple;

import java.util.List;

/**
 * 关系写入端口。
 *
 * <p>{@code TupleSource} 是只读的，写路径由各适配器自己暴露——一致性策略（事务边界、
 * 坐标分配）本身就是存储决策。PDP 需要一个统一的入口，于是在 driving 侧定义这个端口，
 * 由部署方把具体适配器的写方法接上来。
 */
@FunctionalInterface
public interface RelationshipWriter {

    /**
     * 原子提交一批变更。
     *
     * @return 本批变更生效的坐标；调用方应当把它回传给后续读请求，以获得写后一致读
     */
    Revision apply(List<Tuple> writes, List<Tuple> deletes);

    /**
     * 只读部署。写端点会返回 405，而不是静默丢弃变更。
     *
     * <p>把"这个 PDP 不接受写"变成一个显式选择，比让写端点在某个不支持的适配器上
     * 抛 500 要好——前者是配置，后者是故障。
     */
    RelationshipWriter READ_ONLY = (writes, deletes) -> {
        throw new UnsupportedOperationException("该 PDP 以只读模式部署");
    };
}

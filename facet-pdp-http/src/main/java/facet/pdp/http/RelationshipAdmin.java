package facet.pdp.http;

import facet.core.ir.Revision;
import facet.core.ir.Tuple;
import facet.core.ir.TupleFilter;

import java.util.List;

/**
 * 关系运维端口：按条件读取与批量撤销。
 *
 * <p>和 {@link RelationshipWriter} 一样定义在 driving 侧：读回元组与按条件撤销都要落到具体
 * 适配器的能力上（PG 有时效区间因此撤销是闭区间，内存适配器根本不支持撤销），
 * 由部署方把方法接上来。
 *
 * <p><strong>为什么不塞进 {@code TupleSource}。</strong>那是求值端口，上面三个方法都是判定
 * 需要的形状。把运维读写混进去，等于让每个新适配器都必须实现一堆和判定无关的方法，
 * 而"能不能导出全部元组"本来就该是一项可选能力。
 *
 * <p>默认 {@link #DENIED}：读回全部授权数据的影响面很大，必须显式打开。
 */
public interface RelationshipAdmin {

    /**
     * 按条件读回元组。
     *
     * @param after 上一页最后一条元组；首页传 {@code null}
     * @param limit 单页条数上限
     */
    List<Tuple> read(TupleFilter filter, Tuple after, int limit);

    /**
     * 按条件批量撤销。
     *
     * @return 本次撤销生效的坐标
     */
    Revision deleteWhere(TupleFilter filter);

    /**
     * 不开放运维接口。两个端点都返回 405。
     *
     * <p>默认关闭而不是默认打开：读端点能把整套授权关系导出去，删端点一次调用就能清空
     * 整个库。这两件事都应当是部署方明确决定要开的。
     */
    RelationshipAdmin DENIED = new RelationshipAdmin() {

        @Override
        public List<Tuple> read(TupleFilter filter, Tuple after, int limit) {
            throw new UnsupportedOperationException("该 PDP 未开放关系读取接口");
        }

        @Override
        public Revision deleteWhere(TupleFilter filter) {
            throw new UnsupportedOperationException("该 PDP 未开放按条件撤销接口");
        }
    };
}

package facet.core.spi;

/**
 * 存储故障。
 *
 * <p>端口层面定义它，而不是让每个适配器各抛一个自己的异常类型：调用方（PDP、重试装饰器、
 * 熔断器）需要回答的问题是"这次失败还能不能再试一次"，而这个问题只有适配器答得出——
 * 它才知道 {@code SQLState 40001} 是序列化冲突、{@code 08006} 是连接断了。
 * 把答案编码在异常类型里，上层就不必去认某个数据库的错误码。
 *
 * <p><strong>为什么这件事值得一个端口类型。</strong>不区分的话，"数据库重启了"和
 * "schema 里这个关系不可反查"会一起变成 500。前者重试一次就好，后者重试一万次也一样；
 * 而调用方看到的是同一个响应，于是要么全都重试（把正在恢复的数据库再压一遍），
 * 要么全都不重试（把一次一秒的抖动变成一次用户可见的失败）。
 */
public class StorageException extends RuntimeException {

    private final boolean retryable;

    /**
     * @param retryable 同样的请求稍后重试是否有可能成功。连接中断、死锁、序列化冲突、
     *                  资源不足、语句超时都属于这一类；约束违例、语法错误、权限不足不属于
     */
    public StorageException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** 稍后重试是否有可能成功。 */
    public boolean retryable() {
        return retryable;
    }
}

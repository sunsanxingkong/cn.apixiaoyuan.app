package cn.apixiaoyuan.app.core.network

import kotlinx.serialization.Serializable

/**
 * 统一响应包装。
 *
 * 服务端返回的结构是 `{ code, msg, data }` 形态（原版 `@NotNullAndValid`
 * 校验的正是这层）。[ApiResult] 把这层拆成成功/失败两态，业务层只处理
 * [Success.data] 与 [Failure.exception]，不直接碰 code 判断。
 */
sealed interface ApiResult<out T> {

    data class Success<T>(val data: T) : ApiResult<T>

    data class Failure(
        val exception: ApiException,
    ) : ApiResult<Nothing>

    companion object {
        inline fun <T> catching(block: () -> T): ApiResult<T> =
            try {
                Success(block())
            } catch (e: ApiException) {
                Failure(e)
            } catch (e: Throwable) {
                Failure(ApiException.Unknown(e))
            }
    }
}

/** 网络层错误分类。业务层按类型决定重试、跳登录、还是提示。 */
sealed class ApiException(message: String) : Exception(message) {

    /** 服务端返回了业务错误码。 */
    class Business(val code: Int, val serverMsg: String) :
        ApiException("business error $code: $serverMsg")

    /** HTTP 非 2xx。 */
    class Http(val status: Int, val body: String?) :
        ApiException("http $status")

    /** 连接/读写/超时等传输层失败。 */
    class Network(cause: Throwable) :
        ApiException("network: ${cause.message}")

    /** 反序列化失败。 */
    class Parse(cause: Throwable) :
        ApiException("parse: ${cause.message}")

    /** 登录态失效，需要重新登录。 */
    class Unauthorized :
        ApiException("unauthorized")

    /** 未分类。 */
    class Unknown(cause: Throwable) :
        ApiException("unknown: ${cause.message}")

    val isRetryable: Boolean
        get() = this is Network
}

/** 业务成功码。原版判定以 0 为成功；若实测不是 0，只改这里。 */
const val CODE_SUCCESS = 0

/** 登录态失效码。 */
const val CODE_UNAUTHORIZED = 401

/** 服务端统一信封。字段名以实测为准，这里按常见形态落盘。 */
@Serializable
data class Envelope<T>(
    val code: Int = CODE_SUCCESS,
    val msg: String = "",
    val data: T? = null,
)

/**
 * ★ 主域（`/leo-*`）实测信封（2026-10-03）。
 *
 * ## 为什么另起一个而不是改 [Envelope]
 *
 * 真机实测的主域响应长这样（`ExerciseModels.kt` KDoc 里有原始记录）：
 * ```json
 * {"ver":"1.0","status":200,"message":"","data":{"curRank":0,"curWeekScore":0,...}}
 * ```
 * 字段是 **`status` / `message`**，而 [Envelope] 写的是 `code` / `msg` —— **对不上**。
 *
 * [Envelope] 是照「常见形态」猜的，且**全项目无人使用**（只在注释里出现过）。
 * 与其去改一个可能被别处引用的既有类，不如按实测另起一个名字明确的。
 *
 * ## 为什么必须有它（★「分数显示 0」的真根因）
 *
 * 老挂此前把 `/leo-star/android/exercise/rank/pre-fetch` 等端点的返回类型
 * 直接写成**裸** `LeoUserCurrentExpData`，而 `RetrofitFactory` 只注册了
 * 一个 kotlinx converter、**没有任何拆信封逻辑**（`@GsonConverter` 注解在
 * `Annotations.kt` 里自己写明「只做标记」）。
 * 于是 `data` 被 `ignoreUnknownKeys` 忽略，`curWeekScore` 取 Kotlin 默认值 **0**
 * —— 这就是「账号分数始终显示 0」的原因（改字段名没用，两个字段都在信封里）。
 *
 * pk-node 侧是**手写** `pf.json.data.curWeekScore` 显式拆信封，所以它一直是对的。
 */
@Serializable
data class LeoEnvelope<T>(
    /** 协议版本，形如 `"1.0"`。 */
    val ver: String = "",
    /** 业务状态码。实测 200 = 成功。 */
    val status: Int = 0,
    /** 错误信息（成功时空串）。 */
    val message: String = "",
    /** 业务数据体。 */
    val data: T? = null,
)

/** 主域信封是否业务成功（实测 `status == 200`）。 */
val LeoEnvelope<*>.isBizOk: Boolean
    get() = status == 200 && data != null

/** 把信封拆成 ApiResult；data 为 null 时视为业务失败。 */
fun <T> Envelope<T>.toResult(): ApiResult<T> = when {
    code == CODE_UNAUTHORIZED -> ApiResult.Failure(ApiException.Unauthorized())
    code != CODE_SUCCESS -> ApiResult.Failure(ApiException.Business(code, msg))
    data == null -> ApiResult.Failure(ApiException.Business(code, "empty data"))
    else -> ApiResult.Success(data)
}

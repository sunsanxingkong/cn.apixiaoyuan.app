package cn.apixiaoyuan.app.core.network.api

import cn.apixiaoyuan.app.core.model.ExerciseHomepageData
import cn.apixiaoyuan.app.core.network.BASE_LEO
import cn.apixiaoyuan.app.core.network.BaseUrl
import cn.apixiaoyuan.app.core.network.GsonConverter
import cn.apixiaoyuan.app.core.network.LeoEnvelope
import cn.apixiaoyuan.app.core.network.NotNullAndValid
import retrofit2.http.GET

/**
 * 练习「星级」接口（`/leo-star/...`）。
 *
 * 独立成一个接口而不塞进 [LeoExerciseCommonLegacyApiService]：
 * 那条链路是「今日练习上报（旧版）」，这条是「练习星级首页（读数）」，
 * 生命周期与用途不同（前者写、后者读）。
 *
 * ## ★★ 2026-10-03 纠正：返回类型必须是信封
 *
 * 本接口此前声明返回**裸** `ExerciseHomepageData`，是错的 ——
 * 主域实际返回带信封（`{ver,status,message,data}`），而 `RetrofitFactory`
 * **没有**拆信封逻辑（`@GsonConverter` 只做标记，见 `Annotations.kt` 自述）。
 * 后果：`data` 被忽略，所有字段取 Kotlin 默认值 **0** —— 即用户看到的
 * 「分数始终显示 0」。
 *
 * pk-node 侧是手写 `hp.json.data.curWeekExp` 显式拆的，所以它一直对。
 * 现统一改为 [LeoEnvelope]，由 Repository 层取 `.data`。
 *
 * ## 读数口径（★ 与 `rank/pre-fetch` 的分工）
 *
 * - 本接口 `homepage` → `curWeekExp`（**本周练习经验**）/ `todayObtainedPoints` /
 *   `continuousDays` / `curRank` / `nextMultiplier`。实测 `curWeekExp` **恒为 0**。
 * - `rank/pre-fetch` → `curWeekScore`（**周排行榜分数**）—— 这才是「分数」。
 *
 * 两者是**两套数**，不可混用（见 `LeoUserCurrentExpData` 的 KDoc）。
 *
 * smali 出处：`LeoMathApiService` 里有该 GET，注解组合
 * `@GsonConverter + @NotNullAndValid + @GET`。
 */
interface ExerciseStarApiService {
    /**
     * 练习星级首页：连续天数 / 本周经验 / 今日积分 / 排名档位。
     *
     * GET `/leo-star/android/exercise/homepage`
     */
    @BaseUrl(BASE_LEO)
    @GsonConverter
    @NotNullAndValid
    @GET("/leo-star/android/exercise/homepage")
    suspend fun getHomepage(): LeoEnvelope<ExerciseHomepageData>
}
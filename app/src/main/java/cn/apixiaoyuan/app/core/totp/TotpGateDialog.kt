package cn.apixiaoyuan.app.core.totp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * TOTP 真人验证器弹窗。
 *
 * 首次启动（无已通过验证标记）时在主界面之上全屏拦截：
 *  - 标题「TOTP真人验证器」；
 *  - 展示当前 base32 密钥 + 「复制密钥」按钮（存进任意 TOTP App 对表）；
 *  - 密码输入框（6 位数字），下方实时显示当前 TOTP 码刷新倒计时进度条；
 *  - 答错提示重新输入；关闭/返回不放行（弹窗不可 dismiss）。
 *
 * 验证通过后写入标记。当前策略：**每次启动都要求验证一次**（verified 标记
 * 在 App 进程创建时清空），这才构成「门禁」。
 */
@Composable
fun TotpGateDialog(onPassed: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val secret = remember { TotpGate.secret() }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    // ★ 2026-10-04：第二条通路 —— 进群认证（与 TOTP **任一通过即可**）。
    var groupInput by remember { mutableStateOf("") }
    var groupError by remember { mutableStateOf(false) }

    // 倒计时进度：1.0 → 0.0 线性走完一个 30s 步长。
    var progress by remember {
        mutableFloatStateOf(TotpGate.secondsRemaining() / TotpGate.STEP_SECONDS.toFloat())
    }
    LaunchedEffect(Unit) {
        while (true) {
            progress = TotpGate.secondsRemaining() / TotpGate.STEP_SECONDS.toFloat()
            delay(200)
        }
    }

    AlertDialog(
        onDismissRequest = { /* 门禁：不允许关闭跳过 */ },
        title = { Text("身份验证") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "以下两种方式**任选其一**通过即可进入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text("TOTP 验证器", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "首次使用请将以下密钥录入您的验证器 App（Aegis / Google Authenticator 等），此后输入其显示的 6 位动态码即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                // 密钥展示：等宽字体 + 分组空格便于人工抄录。
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = secret.chunked(4).joinToString(" "),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(secret))
                    }) { Text("复制密钥") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        if (it.length <= TotpGate.CODE_DIGITS && it.all { c -> c.isDigit() }) {
                            input = it
                            error = false
                        }
                    },
                    label = { Text("动态码") },
                    isError = error,
                    supportingText = if (error) {
                        { Text("验证码错误，请重试") }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                // TOTP 刷新倒计时进度条：走完一格，验证器 App 换码。
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )

                // ===== 进群认证（★ 2026-10-04 用户要求：「给 totp 认证下面再加一个
                //       进群认证，然后任意通过一个认证即可」）=====
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(
                    "进群认证",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "不想用验证器？加入交流群即可通过。群号：" +
                        TotpGate.GROUP_NUMBERS.joinToString(" 或 ") +
                        "。加群时会问一个验证答案，把那个答案填在下面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                // 群号可点复制（省得手抄错）。
                Row {
                    TotpGate.GROUP_NUMBERS.forEach { num ->
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(num))
                        }) { Text("复制群号 $num") }
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = groupInput,
                    onValueChange = {
                        // 答案是数字串，但长度不限制（不假设位数）。
                        if (it.all { c -> c.isDigit() }) {
                            groupInput = it
                            groupError = false
                        }
                    },
                    label = { Text("加群验证答案") },
                    isError = groupError,
                    supportingText = if (groupError) {
                        { Text("答案不正确，请确认已按群公告的答案加群") }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                // 行内按钮：单独触发「进群认证」这条通路（与底部 TOTP 的「验证」并列）。
                TextButton(
                    enabled = groupInput.isNotBlank(),
                    onClick = {
                        if (TotpGate.verifyGroup(groupInput)) onPassed() else groupError = true
                    },
                ) { Text("使用进群认证通过") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.length == TotpGate.CODE_DIGITS && !checking,
                onClick = {
                    checking = true
                    val ok = TotpGate.verify(input)
                    checking = false
                    if (ok) {
                        onPassed()
                    } else {
                        error = true
                    }
                },
            ) { Text(if (checking) "验证中…" else "验证") }
        },
        dismissButton = {},
    )
}
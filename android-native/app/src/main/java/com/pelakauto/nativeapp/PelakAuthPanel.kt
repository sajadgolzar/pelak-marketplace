package com.pelakauto.nativeapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AuthStep(val title: String) {
    LOGIN("ورود به حساب پلاک"), SMS("ورود پیامکی"), SIGNUP("ساخت حساب"),
    SIGNUP_CODE("تأیید ایمیل"), RECOVERY("بازیابی رمز عبور"),
    RECOVERY_CODE("کد بازیابی رمز عبور"), NEW_PASSWORD("تعیین رمز تازه")
}

@Composable
internal fun PelakAuthPanel(api: PelakApi, onLogin: (PelakSession) -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(AuthStep.LOGIN) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var resetSession by remember { mutableStateOf<PelakSession?>(null) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    var resendIn by remember { mutableIntStateOf(0) }
    val address = email.trim().lowercase(java.util.Locale.ROOT)
    val validEmail = android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()
    val validPassword = password.length in 8..72 && password == confirmation
    val needsCode = step == AuthStep.SIGNUP_CODE || step == AuthStep.RECOVERY_CODE
    val recoveryCode = step == AuthStep.RECOVERY_CODE

    LaunchedEffect(resendIn) {
        if (resendIn > 0) {
            delay(1000)
            resendIn--
        }
    }

    fun goTo(next: AuthStep) {
        step = next
        password = ""
        confirmation = ""
        code = ""
        info = ""
        resetSession = null
    }

    fun submit(error: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        info = ""
        scope.launch {
            try {
                action()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                info = if (failure.message?.contains("(429)") == true)
                    "تعداد درخواست‌ها زیاد شده. کمی بعد دوباره تلاش کن." else error
            } finally {
                busy = false
            }
        }
    }

    BackHandler(enabled = step != AuthStep.LOGIN) {
        if (!busy) goTo(AuthStep.LOGIN)
    }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(step.title, style = MaterialTheme.typography.headlineSmall)
        if (step == AuthStep.SMS) {
            Text("ورود پیامکی پلاک به‌زودی فعال می‌شود.")
        }
        if (step in listOf(AuthStep.LOGIN, AuthStep.SIGNUP, AuthStep.RECOVERY)) {
            OutlinedTextField(email, { email = it }, enabled = !busy, label = { Text("ایمیل") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (step in listOf(AuthStep.LOGIN, AuthStep.SIGNUP, AuthStep.NEW_PASSWORD)) {
            PasswordField(password, { password = it }, "رمز عبور", !busy)
        }
        if (step == AuthStep.SIGNUP || step == AuthStep.NEW_PASSWORD) {
            PasswordField(confirmation, { confirmation = it }, "تکرار رمز عبور", !busy)
            Text("رمز عبور باید دست‌کم ۸ نویسه داشته باشد.", style = MaterialTheme.typography.bodySmall)
        }
        if (needsCode) {
            Text("کد تأیید ایمیل را وارد کن.")
            Text(address, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(code, { input ->
                code = input.mapNotNull { it.digitToIntOrNull() }.take(6).joinToString("")
            }, enabled = !busy, label = { Text("کد ۶ رقمی") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = !busy && resendIn == 0, onClick = {
                submit("ارسال دوبارهٔ کد ناموفق بود.") {
                    if (recoveryCode) api.requestPasswordReset(address) else api.resendSignupCode(address)
                    resendIn = 60
                    info = "اگر درخواست معتبر باشد، کد تازه به ایمیل ارسال می‌شود."
                }
            }) { Text(if (resendIn > 0) "ارسال دوباره پس از ${resendIn} ثانیه" else "ارسال دوبارهٔ کد") }
        }

        when (step) {
            AuthStep.LOGIN -> {
                Button(enabled = !busy && validEmail && password.isNotBlank(), onClick = {
                    submit("ورود انجام نشد. ایمیل، رمز عبور و اتصال اینترنت را بررسی کن.") {
                        onLogin(api.signInWithPassword(address, password))
                    }
                }) { Text("ورود با ایمیل") }
                OutlinedButton(enabled = !busy, onClick = { goTo(AuthStep.SIGNUP) }) { Text("ساخت حساب") }
                TextButton(enabled = !busy, onClick = { goTo(AuthStep.RECOVERY) }) { Text("فراموشی رمز عبور") }
                TextButton(enabled = !busy, onClick = { goTo(AuthStep.SMS) }) { Text("ورود پیامکی") }
            }
            AuthStep.SMS -> Unit
            AuthStep.SIGNUP -> Button(enabled = !busy && validEmail && validPassword, onClick = {
                submit("ساخت حساب انجام نشد. اطلاعات را بررسی کن یا اگر حساب داری، وارد شو.") {
                    val session = api.createAccount(address, password)
                    if (session != null) onLogin(session)
                    else {
                        goTo(AuthStep.SIGNUP_CODE)
                        resendIn = 60
                    }
                }
            }) { Text("ساخت حساب و ارسال کد") }
            AuthStep.RECOVERY -> Button(enabled = !busy && validEmail, onClick = {
                submit("درخواست بازیابی انجام نشد. دوباره تلاش کن.") {
                    api.requestPasswordReset(address)
                    goTo(AuthStep.RECOVERY_CODE)
                    resendIn = 60
                }
            }) { Text("ارسال کد بازیابی") }
            AuthStep.SIGNUP_CODE, AuthStep.RECOVERY_CODE ->
                Button(enabled = !busy && code.length == 6, onClick = {
                    submit("کد تأیید پذیرفته نشد. کد تازه را بررسی کن.") {
                        val verified = api.verifyAccountCode(address, code, recoveryCode)
                        if (recoveryCode) {
                            goTo(AuthStep.NEW_PASSWORD)
                            resetSession = verified
                        } else onLogin(verified)
                    }
                }) { Text("تأیید کد") }
            AuthStep.NEW_PASSWORD -> Button(enabled = !busy && validPassword && resetSession != null, onClick = {
                val verified = resetSession ?: return@Button
                submit("رمز تغییر نکرد. رمز متفاوتی وارد کن یا کد تازه بگیر.") {
                    api.updatePassword(verified, password)
                    onLogin(verified)
                }
            }) { Text("ذخیرهٔ رمز تازه") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (info.isNotBlank()) Text(info, color = MaterialTheme.colorScheme.primary)
        if (step != AuthStep.LOGIN) {
            TextButton(enabled = !busy, onClick = { goTo(AuthStep.LOGIN) }) { Text("بازگشت به ورود") }
        }
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(value, onChange, enabled = enabled, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
}

package com.artemchep.keyguard.feature.keyguard.unlock

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.andThen
import arrow.core.getOrElse
import arrow.core.identity
import arrow.core.partially1
import arrow.core.some
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.BiometricAuthException
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.usecase.ClearData
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.util.validatedTitle
import com.artemchep.keyguard.feature.loading.LoadingTask
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.platform.LeBiometricCipher
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

private val DEFAULT_PASSWORD = ""

@Composable
fun unlockScreenState(
    clearData: ClearData,
    unlockVaultByMasterPassword: VaultState.Unlock.WithPassword,
    unlockVaultByBiometric: VaultState.Unlock.WithBiometric?,
    lockInfo: VaultState.Unlock.LockInfo? = null,
): Loadable<UnlockState> = produceScreenState<Loadable<UnlockState>>(
    key = "unlock",
    initial = Loadable.Loading,
    args = arrayOf(
        unlockVaultByMasterPassword,
        unlockVaultByBiometric,
    ),
) {
    val executor = screenExecutor()

    val unlockVaultByMasterPasswordFn = UnlockVaultWithPassword(
        executor = executor,
        options = unlockVaultByMasterPassword,
    )
    val unlockVaultWithBiometricFn = unlockVaultByBiometric
        ?.let {
            UnlockVaultWithBiometric(
                executor = executor,
                options = it,
            )
        }

    val biometricPromptSink = EventFlow<BiometricAuthPrompt>()
    val biometricPromptFlow = biometricPromptSink
        .onStart {
            if (unlockVaultWithBiometricFn != null) {
                // Let the user see the reason why the vault was
                // locked. Also helps to prevent spamming biometric
                // auth on desktops.
                if (lockInfo != null) kotlin.run {
                    // If the lock has happened more than 5 minutes
                    // ago then trigger the biometrics immediately.
                    val dt = Clock.System.now() - lockInfo.timestamp
                    if (dt.inWholeMinutes >= 5) {
                        return@run
                    }
                    when (lockInfo.type) {
                        LockReason.TIMEOUT -> return@run
                        LockReason.LOCK -> return@onStart // do not trigger biometric
                    }
                }
                val prompt = withContext(Dispatchers.Main) {
                    createPromptOrNull(
                        executor = executor,
                        fn = unlockVaultWithBiometricFn,
                    )
                }
                if (prompt != null) {
                    emit(prompt)
                }
            }
        }
        .shareIn(screenScope, SharingStarted.WhileSubscribed(5000L))

    val passwordSink = mutablePersistedFlow("password") { DEFAULT_PASSWORD }
    val passwordState = mutableComposeState(passwordSink)

    val actions = persistentListOf(
        FlatItemAction(
            icon = Icons.Outlined.Delete,
            title = Res.string.pref_item_erase_data_title.wrap(),
            text = Res.string.pref_item_erase_data_text.wrap(),
            onClick = {
                clearData()
                    .effectTap {
                        val exitIntent = NavigationIntent.Exit
                        navigate(exitIntent)
                    }
                    .attempt()
                    .launchIn(GlobalScope)
            },
        ),
    )

    val biometricPrecomputed = if (unlockVaultWithBiometricFn != null) {
        val requestBiometricAuthPrompt = biometricPromptSink::emit
            .andThen { true }
        BiometricStatePrecomputed(
            disabled = createBiometricState(
                executor = executor,
                fn = unlockVaultWithBiometricFn,
                clickable = false,
                requestBiometricAuthPrompt = requestBiometricAuthPrompt,
            ),
            enabled = createBiometricState(
                executor = executor,
                fn = unlockVaultWithBiometricFn,
                clickable = true,
                requestBiometricAuthPrompt = requestBiometricAuthPrompt,
            ),
        )
    } else {
        null
    }
    combine(
        passwordSink
            .validatedTitle(this),
        executor.isExecutingFlow,
    ) { validatedPassword, taskExecuting ->
        val error = (validatedPassword as? Validated.Failure)?.error
        val canCreateVault = error == null && !taskExecuting
        val state = UnlockState(
            lockReason = lockInfo?.reason,
            password = TextFieldModel2.of(
                state = passwordState,
                validated = validatedPassword,
            ),
            biometric = if (taskExecuting) {
                biometricPrecomputed?.disabled
            } else {
                biometricPrecomputed?.enabled
            },
            sideEffects = UnlockState.SideEffects(
                showBiometricPromptFlow = biometricPromptFlow,
            ),
            isLoading = taskExecuting,
            unlockVaultByMasterPassword = if (canCreateVault) {
                unlockVaultByMasterPasswordFn.partially1(validatedPassword.model)
            } else {
                null
            },
            actions = actions,
        )
        Loadable.Ok(state)
    }
}

private class BiometricStatePrecomputed(
    val disabled: UnlockState.Biometric,
    val enabled: UnlockState.Biometric,
)

private fun RememberStateFlowScope.createBiometricState(
    executor: LoadingTask,
    fn: UnlockVaultWithBiometric,
    clickable: Boolean,
    requestBiometricAuthPrompt: (BiometricAuthPrompt) -> Boolean,
) = UnlockState.Biometric(
    onClick = if (!clickable) {
        null
    } else {
        var promptWrapper: Option<BiometricAuthPrompt?> = None
        val promptMutex by lazy {
            Mutex()
        }

        // An action should trigger the biometric prompt upon
        // execution.
        fun() {
            screenScope.launch {
                // Get or create the prompt and remember
                // it for the future invocations.
                val prompt = promptMutex.withLock {
                    promptWrapper.getOrElse {
                        createPromptOrNull(executor, fn)
                    }.also {
                        promptWrapper = it.some()
                    }
                }
                    ?: return@launch
                // Send it down the sink
                requestBiometricAuthPrompt(prompt)
            }
        }
    },
)

private suspend fun createPromptOrNull(
    executor: LoadingTask,
    fn: UnlockVaultWithBiometric,
): BiometricAuthPrompt? = run {
    val cipher = fn.getCipher()
        .fold(
            ifLeft = { e ->
                val fakeIo = ioRaise<Unit>(e)
                executor.execute(fakeIo)
                return@run null
            },
            ifRight = ::identity,
        )
    BiometricAuthPrompt(
        title = TextHolder.Res(Res.string.unlock_biometric_auth_confirm_title),
        text = TextHolder.Res(Res.string.unlock_biometric_auth_confirm_text),
        cipher = cipher,
        requireConfirmation = fn.requireConfirmation,
        onComplete = { result ->
            result.fold(
                ifLeft = { exception ->
                    when (exception.code) {
                        BiometricAuthException.ERROR_CANCELED,
                        BiometricAuthException.ERROR_USER_CANCELED,
                        BiometricAuthException.ERROR_NEGATIVE_BUTTON,
                            -> return@fold
                    }

                    val io = ioRaise<Unit>(exception)
                    executor.execute(io)
                },
                ifRight = {
                    fn.invoke()
                },
            )
        },
    )
}

private class UnlockVaultWithPassword(
    private val executor: LoadingTask,
    private val getCreateIo: (String) -> IO<Unit>,
) : (String) -> Unit {
    // Create from vault state options
    constructor(
        executor: LoadingTask,
        options: VaultState.Unlock.WithPassword,
    ) : this(
        executor = executor,
        getCreateIo = options.getCreateIo,
    )

    override fun invoke(password: String) {
        val io = getCreateIo(password)
        executor.execute(io, password)
    }
}

private class UnlockVaultWithBiometric(
    private val executor: LoadingTask,
    /**
     * A getter for the cipher to pass to the biometric
     * authentication.
     */
    val getCipher: suspend () -> Either<Throwable, LeBiometricCipher>,
    private val getCreateIo: () -> IO<Unit>,
    val requireConfirmation: Boolean,
) : () -> Unit {
    // Create from vault state options
    constructor(
        executor: LoadingTask,
        options: VaultState.Unlock.WithBiometric,
    ) : this(
        executor = executor,
        getCipher = options.getCipher,
        getCreateIo = options.getCreateIo,
        requireConfirmation = options.requireConfirmation,
    )

    override fun invoke() {
        val io = getCreateIo()
        executor.execute(io)
    }
}

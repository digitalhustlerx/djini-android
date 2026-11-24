package com.wangzan101.djini.core.agent

import com.wangzan101.djini.core.net.ProxyFailure
import com.wangzan101.djini.core.guard.GuardVerdict
import com.wangzan101.djini.core.model.ModelError
import com.wangzan101.djini.core.tool.Outcome
import kotlinx.serialization.json.JsonPrimitive

/**
 * 停止规则:任务什么时候、以什么结局结束(放弃、守卫叫停、模型出错、没给出回答、步数用完)。办完不在这里:
 * 不带工具调用的回复就是回答,由循环核对后交出。纯函数;结局里给用户看的说明不发给模型。
 */
internal object StopRules {
    /** 一个动作执行完之后:放弃了就按它结束;否则守卫([stop],屏幕守卫或不动屏幕的工具的守卫)叫停了就停。都没有为 null,接着做。 */
    fun afterAction(outcome: Outcome?, stop: GuardVerdict?): TaskState? = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stop?.let(::guard)
    }

    /** 步数用完后的最后一轮调了工具(只给了 end_not_done):放弃按它结束,否则算步数用完。 */
    fun afterFinalTurn(outcome: Outcome?, maxSteps: Int): TaskState = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stepLimit(maxSteps)
    }

    fun stepLimit(maxSteps: Int) = TaskState.Failed("Reached the $maxSteps-step limit before the task finished.", FailKind.StepLimit)

    /** 收尾请求本身失败或仍没交出结果时，按触发收尾的原因落结局，不谎报步数用完。 */
    fun closeoutFailed(reason: Closeout, maxSteps: Int): TaskState.Failed = when (reason) {
        Closeout.StepLimit -> stepLimit(maxSteps)
        Closeout.Completed -> TaskState.Failed("Task progress finished, but the model did not return a final answer.", FailKind.Model)
        Closeout.NoNewData -> TaskState.Failed("Stopped after ${NewDataTracker.LIMIT} consecutive attempts without new data.", FailKind.Guard)
    }

    /** 提醒过一次,模型仍然回了空话(或又被输出上限截断)。 */
    fun noAnswer() = TaskState.Failed("The model stopped without giving an answer.", FailKind.Model)

    /** 退回过一次,模型的工具调用又以文字形式回来了(见 [LeakedToolCall]):不把原样的标记当回答交出。 */
    fun toolCallAsText() = TaskState.Failed("The model's tool call was returned as plain text and could not be executed.", FailKind.Model)

    /** 模型调用失败(重试过仍不行,或重试也没用)。 */
    fun modelFailed(e: Throwable): TaskState.Failed = when (e) {
        is ModelError.TaskBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.DailyBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.ProactiveBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.RateLimited -> TaskState.Failed(userReason(e), FailKind.Model, FailureCode.ModelRateLimited)
        is ModelError -> TaskState.Failed(userReason(e), FailKind.Model)
        else -> TaskState.Failed("Model call error: ${e.message ?: e::class.simpleName}", FailKind.Model)
    }

    fun guard(v: GuardVerdict): TaskState.Failed {
        val treeBlind = v.evidence["treeBlind"] == JsonPrimitive(true)
        val why = when (v.code) {
            "repeating_action" -> "repeating the same action had no effect"
            "no_progress" -> if (treeBlind) "cannot read screen controls and taps/swipes had no effect; this app may not respond to Djini automation"
                else "the screen stopped changing after repeated actions"
            "read_loop" -> "the page stopped changing and repeated reads found no target content"
            "too_many_failures" -> "actions failed repeatedly"
            "sandbox_loop" -> "the same command failed repeatedly"
            else -> "action safety guard triggered"
        }
        return TaskState.Failed("Safely stopped (${v.code}): $why.", FailKind.Guard)
    }
}

internal fun userReason(e: ModelError): String = when (e) {
    is ModelError.Auth ->
        "Model service rejected the request: invalid API key or insufficient permissions." +
            (if (e.detail.isNotEmpty()) " Server replied: ${quoted(e.detail)}." else "") +
            " Check your API key, plan tier, and model permissions in Settings."
    is ModelError.IllegalHeader -> "API key contains invalid characters. In Settings › Models, paste only the key itself with no extra text, line breaks, or emoji."
    is ModelError.QuotaExceeded -> "Model account quota exceeded. Check your provider balance or plan."
    is ModelError.TaskBudgetExceeded -> "Reached this task's ${e.limit}-token budget before finishing."
    is ModelError.DailyBudgetExceeded ->
        "Provider \"${e.providerName}\" reached today's ${e.limit}-token budget. It resets automatically at midnight local time."
    // 主动能力的调用不在任务里发,正常不会走到这里;万一走到,只如实说明,不提示用户去改什么
    is ModelError.ProactiveBudgetExceeded -> "Proactive capability reached today's budget limit. It resets automatically at midnight local time."
    is ModelError.ModelNotFound -> {
        // 404 不一定是模型名不对(地址不对,或网关、中转服务说的别的情况):有原话时照原话说,不断定模型不存在
        val check = if (e.model.isEmpty()) "Check the model name and Base URL in Settings › Models."
            else "Check the model name in Settings › Models (use the full model ID, e.g. gemini-flash-latest) or switch to a matching preset."
        when {
            e.detail.isNotEmpty() ->
                (if (e.model.isEmpty()) "" else "Calling model \"${e.model}\": ") +
                    "Server replied: ${quoted(e.detail)}. The model name may be wrong or the service may be temporarily unavailable. $check"
            e.model.isEmpty() -> "Model not found. $check"
            else -> "Model \"${e.model}\" does not exist on this provider. $check"
        }
    }
    is ModelError.ModelNotAvailable ->
        "Model \"${e.model.ifEmpty { "current model" }}\" is not enabled or authorized for this account. Switch models in Settings › Models or enable it in your provider console."
    is ModelError.ToolsUnsupported ->
        "Model \"${e.model.ifEmpty { "current model" }}\" does not support tool calling, which Djini requires. Choose a model that supports tools in Settings › Models."
    is ModelError.ContextTooLong -> "Conversation exceeded the model's context length. Use a model with a larger context window or break the task into smaller parts."
    is ModelError.ContentRefused -> "The model refused to process this content. Adjust the prompt or switch models."
    is ModelError.ReasoningEffortRejected ->
        "The model rejected the selected reasoning effort. Server replied: ${quoted(e.detail)}. Change reasoning effort to Default or a supported level in Settings › Models."
    is ModelError.RateLimited -> "Model service rate-limited requests even after retries. Try again shortly or check your account rate limits."
    is ModelError.Network -> "Failed to connect to the model service even after retries. Check your network and Base URL."
    is ModelError.Proxy -> when (e.failure) {
        ProxyFailure.Unreachable ->
            "Cannot reach the network proxy or the proxy did not forward the request. Check that your proxy is running and the host/port in Settings › Network proxy are correct, or clear it if unused."
        ProxyFailure.AuthFailed -> "Network proxy rejected username or password (HTTP 407). Check proxy credentials in Settings › Network proxy."
    }
    is ModelError.Server -> "Model service error (HTTP ${e.status}). Please try again later."
    is ModelError.StreamCut ->
        "Connection dropped upstream before the response finished streaming, even after retries. This usually means an unstable network or a gateway idle timeout during long reasoning. Please try again."
    is ModelError.BadResponse -> "${e.message}. Check that the Base URL and endpoint kind match."
    is ModelError.ChatGptLimitReached ->
        "ChatGPT usage limit reached; try again later. Check usage at chatgpt.com/settings/usage (Plus 5-hour limits are shared across apps using ChatGPT sign-in), or switch to another model."
    is ModelError.ChatGptNotEligible ->
        "This account cannot use ChatGPT quota in third-party apps (requires Plus or Pro, subject to region and account policy).${if (e.detail.isEmpty()) "" else " Server note: ${quoted(e.detail)}."} You can switch to another model."
    is ModelError.SignInExpired -> "${e.account} sign-in expired. Please sign in with ${e.account} again in Settings › Models."
    is ModelError.AccountPlanNotAllowed ->
        "Your ${e.account} subscription tier cannot access this endpoint (HTTP 403).${if (e.detail.isEmpty()) "" else " Server note: ${quoted(e.detail)}."} Use an ${e.account} API key or choose another model in Settings › Models."
    is ModelError.ChainFailed -> {
        // 主消息是主模型最初的错误(上面按它的类型生成),后面附整条链每个模型的状态码与一行摘要(MK-1 第 2 批)
        val trail = e.entries.joinToString("; ") { (name, err) ->
            "\"$name\"${chainErrorTag(err)}: ${err.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(80)}"
        }
        userReason(e.primary) + " All fallback models were tried and failed: $trail."
    }
}

/** 链失败记录里每个错误的短标签:认得出的写 HTTP 状态码,其余写故障名。 */
private fun chainErrorTag(e: ModelError): String = when (e) {
    is ModelError.Server -> " HTTP ${e.status}"
    is ModelError.RateLimited -> " HTTP 429"
    is ModelError.Auth -> " " + RetryPolicy.label(e)
    else -> " ${RetryPolicy.label(e)}"
}

/** 引在句中的服务端原话:去掉末尾一个句号(原话自带的),由引它的句子补上自己的「。」;省略号(截短的「…」、「...」)不动。 */
private fun quoted(detail: String): String =
    when {
        detail.endsWith("..") -> detail
        detail.endsWith(".") || detail.endsWith("。") -> detail.dropLast(1)
        else -> detail
    }

// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.service

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.annotations.RequiresEdt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import software.amazon.awssdk.core.exception.SdkServiceException
import software.amazon.awssdk.core.util.DefaultSdkAutoConstructList
import software.amazon.awssdk.services.codewhisperer.CodeWhispererClient
import software.amazon.awssdk.services.codewhisperer.model.CodeWhispererException
import software.amazon.awssdk.services.codewhisperer.model.FileContext
import software.amazon.awssdk.services.codewhisperer.model.ListRecommendationsRequest
import software.amazon.awssdk.services.codewhisperer.model.ListRecommendationsResponse
import software.amazon.awssdk.services.codewhisperer.model.ProgrammingLanguage
import software.amazon.awssdk.services.codewhisperer.model.Recommendation
import software.amazon.awssdk.services.codewhisperer.paginators.ListRecommendationsIterable
import software.aws.toolkits.core.utils.debug
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.core.utils.info
import software.aws.toolkits.jetbrains.core.coroutines.disposableCoroutineScope
import software.aws.toolkits.jetbrains.core.coroutines.projectCoroutineScope
import software.aws.toolkits.jetbrains.services.codewhisperer.credentials.CodeWhispererClientManager
import software.aws.toolkits.jetbrains.services.codewhisperer.editor.CodeWhispererEditorManager
import software.aws.toolkits.jetbrains.services.codewhisperer.editor.CodeWhispererEditorUtil.getCaretPosition
import software.aws.toolkits.jetbrains.services.codewhisperer.editor.CodeWhispererEditorUtil.getFileContextInfo
import software.aws.toolkits.jetbrains.services.codewhisperer.explorer.CodeWhispererExplorerActionManager
import software.aws.toolkits.jetbrains.services.codewhisperer.language.CodeWhispererLanguageManager
import software.aws.toolkits.jetbrains.services.codewhisperer.model.CaretPosition
import software.aws.toolkits.jetbrains.services.codewhisperer.model.DetailContext
import software.aws.toolkits.jetbrains.services.codewhisperer.model.FileContextInfo
import software.aws.toolkits.jetbrains.services.codewhisperer.model.InvocationContext
import software.aws.toolkits.jetbrains.services.codewhisperer.model.RecommendationContext
import software.aws.toolkits.jetbrains.services.codewhisperer.model.SessionContext
import software.aws.toolkits.jetbrains.services.codewhisperer.model.TriggerTypeInfo
import software.aws.toolkits.jetbrains.services.codewhisperer.model.WorkerContext
import software.aws.toolkits.jetbrains.services.codewhisperer.popup.CodeWhispererPopupManager
import software.aws.toolkits.jetbrains.services.codewhisperer.settings.CodeWhispererSettings
import software.aws.toolkits.jetbrains.services.codewhisperer.telemetry.CodeWhispererTelemetryService
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CaretMovement
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererConstants
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererMetadata
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererUtil.checkCompletionType
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererUtil.checkEmptyRecommendations
import software.aws.toolkits.resources.message
import software.aws.toolkits.telemetry.CodewhispererCompletionType
import software.aws.toolkits.telemetry.CodewhispererSuggestionState
import software.aws.toolkits.telemetry.CodewhispererTriggerType
import java.util.concurrent.TimeUnit

class CodeWhispererService {
    fun showRecommendationsInPopup(editor: Editor, triggerTypeInfo: TriggerTypeInfo) {
        val project = editor.project ?: return

        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
        if (psiFile == null) {
            LOG.debug { "No PSI file for the current document" }
            if (triggerTypeInfo.triggerType == CodewhispererTriggerType.OnDemand) {
                showCodeWhispererInfoHint(editor, message("codewhisperer.trigger.document.unsupported"))
            }
            return
        }

        val requestContext = try {
            getRequestContext(triggerTypeInfo, editor, project, psiFile)
        } catch (e: Exception) {
            LOG.debug { e.message.toString() }
            CodeWhispererTelemetryService.getInstance().sendFailedServiceInvocationEvent(project, e::class.simpleName)
            return
        }

        val language = requestContext.fileContextInfo.programmingLanguage.languageName
        if (!CodeWhispererLanguageManager.getInstance().isLanguageSupported(language)) {
            LOG.debug { "Programming language $language is not supported by CodeWhisperer" }
            if (triggerTypeInfo.triggerType == CodewhispererTriggerType.OnDemand) {
                showCodeWhispererInfoHint(
                    requestContext.editor,
                    message("codewhisperer.language.error", psiFile.fileType.name)
                )
            }
            return
        }

        LOG.debug {
            "Calling CodeWhisperer service, trigger type: ${triggerTypeInfo.triggerType}" +
                if (triggerTypeInfo.triggerType == CodewhispererTriggerType.AutoTrigger) {
                    ", auto-trigger type: ${triggerTypeInfo.automatedTriggerType}"
                } else {
                    ""
                }
        }

        val invocationStatus = CodeWhispererInvocationStatus.getInstance()
        if (invocationStatus.checkExistingInvocationAndSet()) {
            return
        }

        invocationStatus.resetKeyStrokeCount()
        invokeCodeWhispererInBackground(requestContext)
    }

    private fun invokeCodeWhispererInBackground(requestContext: RequestContext) {
        val popup = CodeWhispererPopupManager.getInstance().initPopup()
        Disposer.register(popup) { CodeWhispererInvocationStatus.getInstance().finishInvocation() }

        val workerContexts = mutableListOf<WorkerContext>()
        // When popup is disposed we will cancel this coroutine. The only places popup can get disposed should be
        // from CodeWhispererPopupManager.cancelPopup() and CodeWhispererPopupManager.closePopup().
        // It's possible and ok that coroutine will keep running until the next time we check it's state.
        // As long as we don't show to the user extra info we are good.
        val coroutineScope = disposableCoroutineScope(popup)

        var states: InvocationContext? = null
        var lastRecommendationIndex = -1
        val responseIterable = listRecommendations(requestContext)
        coroutineScope.launch {
            try {
                var startTime = System.nanoTime()
                for (response in responseIterable.stream()) {
                    val endTime = System.nanoTime()
                    val latency = TimeUnit.NANOSECONDS.toMillis(endTime - startTime).toDouble()
                    startTime = endTime
                    val requestId = response.responseMetadata().requestId()
                    val sessionId = response.sdkHttpResponse().headers().getOrDefault(KET_SESSION_ID, listOf(requestId))[0]
                    val emptyRecommendations = checkEmptyRecommendations(response.recommendations())
                    val completionType = checkCompletionType(response.recommendations(), emptyRecommendations)
                    val responseContext = ResponseContext(sessionId, completionType)
                    logServiceInvocation(requestId, requestContext, responseContext, response.recommendations(), latency, null)
                    lastRecommendationIndex += response.recommendations().size
                    CodeWhispererTelemetryService.getInstance().sendServiceInvocationEvent(
                        requestId,
                        requestContext,
                        responseContext,
                        lastRecommendationIndex,
                        true,
                        latency,
                        null
                    )

                    val validatedResponse = validateResponse(response)

                    runInEdt {
                        // If delay is not met, add them to the worker queue and process them later.
                        // On first response, workers queue must be empty. If there's enough delay before showing,
                        // process CodeWhisperer UI rendering and workers queue will remain empty throughout this
                        // CodeWhisperer session. If there's not enough delay before showing, the CodeWhisperer UI rendering task
                        // will be added to the workers queue.
                        // On subsequent responses, if they see workers queue is not empty, it means the first worker
                        // task hasn't been finished yet, in this case simply add another task to the queue. If they
                        // see worker queue is empty, the previous tasks must have been finished before this. In this
                        // case render CodeWhisperer UI directly.
                        val workerContext = WorkerContext(requestContext, responseContext, validatedResponse, popup)
                        if (workerContexts.isNotEmpty()) {
                            workerContexts.add(workerContext)
                        } else {
                            if (states == null && !popup.isDisposed &&
                                !CodeWhispererInvocationStatus.getInstance().hasEnoughDelayToShowCodeWhisperer()
                            ) {
                                // It's the first response, and no enough delay before showing
                                projectCoroutineScope(requestContext.project).launch {
                                    while (!CodeWhispererInvocationStatus.getInstance().hasEnoughDelayToShowCodeWhisperer()) {
                                        delay(CodeWhispererConstants.POPUP_DELAY_CHECK_INTERVAL)
                                    }
                                    runInEdt {
                                        workerContexts.forEach {
                                            states = processCodeWhispererUI(it, states)
                                        }
                                        workerContexts.clear()
                                    }
                                }
                                workerContexts.add(workerContext)
                            } else {
                                // Have enough delay before showing for the first response, or it's subsequent responses
                                states = processCodeWhispererUI(workerContext, states)
                            }
                        }
                    }
                    if (!isActive) {
                        // If job is cancelled before we do another request, don't bother making
                        // another API call to save resources
                        LOG.debug { "Skipping sending remaining requests on CodeWhisperer session exit" }
                        break
                    }
                }
            } catch (e: Exception) {
                val requestId: String
                val sessionId: String
                val displayMessage: String
                if (e is CodeWhispererException) {
                    requestId = e.requestId() ?: ""
                    sessionId = e.awsErrorDetails().sdkHttpResponse().headers().getOrDefault(KET_SESSION_ID, listOf(requestId))[0]
                    displayMessage = e.awsErrorDetails().errorMessage() ?: message("codewhisperer.trigger.error.server_side")
                } else {
                    requestId = ""
                    sessionId = ""
                    val statusCode = if (e is SdkServiceException) e.statusCode() else 0
                    displayMessage =
                        if (statusCode >= 500) {
                            message("codewhisperer.trigger.error.server_side")
                        } else {
                            message("codewhisperer.trigger.error.client_side")
                        }
                }
                val exceptionType = e::class.simpleName
                val responseContext = ResponseContext(sessionId, CodewhispererCompletionType.Unknown)
                logServiceInvocation(requestId, requestContext, responseContext, emptyList(), null, exceptionType)
                CodeWhispererTelemetryService.getInstance().sendServiceInvocationEvent(
                    requestId,
                    requestContext,
                    responseContext,
                    lastRecommendationIndex,
                    false,
                    0.0,
                    exceptionType
                )

                if (requestContext.triggerTypeInfo.triggerType == CodewhispererTriggerType.OnDemand) {
                    // We should only show error hint when CodeWhisperer popup is not visible,
                    // and make it silent if CodeWhisperer popup is showing.
                    runInEdt {
                        if (!CodeWhispererInvocationStatus.getInstance().isPopupActive()) {
                            showCodeWhispererErrorHint(requestContext.editor, displayMessage)
                        }
                    }
                }
                CodeWhispererInvocationStatus.getInstance().finishInvocation()
                runInEdt {
                    states?.let {
                        CodeWhispererPopupManager.getInstance().updatePopupPanel(
                            it,
                            CodeWhispererPopupManager.getInstance().sessionContext
                        )
                    }
                }
            } finally {
                CodeWhispererInvocationStatus.getInstance().setInvocationComplete()
            }
        }
    }

    @RequiresEdt
    private fun processCodeWhispererUI(workerContext: WorkerContext, currStates: InvocationContext?): InvocationContext? {
        val requestContext = workerContext.requestContext
        val responseContext = workerContext.responseContext
        val response = workerContext.response
        val popup = workerContext.popup
        val requestId = response.responseMetadata().requestId()

        // At this point when we are in EDT, the state of the popup will be thread-safe
        // across this thread execution, so if popup is disposed, we will stop here.
        // This extra check is needed because there's a time between when we get the response and
        // when we enter the EDT.
        if (popup.isDisposed) {
            LOG.debug { "Stop showing CodeWhisperer recommendations on CodeWhisperer session exit. RequestId: $requestId" }
            return null
        }

        if (response.nextToken().isEmpty()) {
            CodeWhispererInvocationStatus.getInstance().finishInvocation()
        }

        val caretMovement = CodeWhispererEditorManager.getInstance().getCaretMovement(
            requestContext.editor,
            requestContext.caretPosition
        )
        val isPopupShowing: Boolean
        val nextStates: InvocationContext?
        if (currStates == null) {
            // first response
            nextStates = initStates(requestContext, responseContext, response, caretMovement, popup)
            isPopupShowing = false

            // receiving a null state means caret has moved backward or there's a conflict with
            // Intellisense popup, so we are going to cancel the job
            if (nextStates == null) {
                LOG.debug { "Cancelling popup and exiting CodeWhisperer session. RequestId: $requestId" }
                CodeWhispererPopupManager.getInstance().cancelPopup(popup)
                return null
            }
        } else {
            // subsequent responses
            nextStates = updateStates(currStates, response)
            isPopupShowing = checkRecommendationsValidity(currStates, false)
        }

        val hasAtLeastOneValid = checkRecommendationsValidity(nextStates, response.nextToken().isEmpty())

        // If there are no recommendations in this response, we need to manually send the user decision event here
        // since it won't be sent automatically later
        if (response.recommendations().isEmpty()) {
            LOG.debug { "Received an empty list from response, requestId: $requestId" }
            CodeWhispererTelemetryService.getInstance().sendUserDecisionEvent(
                requestId,
                requestContext,
                responseContext,
                Recommendation.builder().build(),
                -1,
                CodewhispererSuggestionState.Empty,
                nextStates.recommendationContext.details.size
            )
        }
        if (!hasAtLeastOneValid) {
            if (response.nextToken().isEmpty()) {
                LOG.debug { "None of the recommendations are valid, exiting CodeWhisperer session" }
                CodeWhispererPopupManager.getInstance().cancelPopup(popup)
                return null
            }
        } else if (response.recommendations().isNotEmpty()) {
            updateCodeWhisperer(nextStates, isPopupShowing)
        }
        return nextStates
    }

    private fun initStates(
        requestContext: RequestContext,
        responseContext: ResponseContext,
        response: ListRecommendationsResponse,
        caretMovement: CaretMovement,
        popup: JBPopup
    ): InvocationContext? {
        val requestId = response.responseMetadata().requestId()
        val recommendations = response.recommendations()
        val visualPosition = requestContext.editor.caretModel.visualPosition

        if (CodeWhispererPopupManager.getInstance().hasConflictingPopups(requestContext.editor)) {
            LOG.debug { "Detect conflicting popup window with CodeWhisperer popup, not showing CodeWhisperer popup" }
            return null
        }
        if (caretMovement == CaretMovement.MOVE_BACKWARD) {
            LOG.debug { "Caret moved backward, discarding all of the recommendations. Request ID: $requestId" }
            val detailContexts = recommendations.map { DetailContext("", it, it, true) }
            val recommendationContext = RecommendationContext(detailContexts, "", "", VisualPosition(0, 0))

            CodeWhispererTelemetryService.getInstance().sendUserDecisionEventForAll(
                requestContext, responseContext, recommendationContext, SessionContext(), false
            )
            return null
        }
        val userInputOriginal = CodeWhispererEditorManager.getInstance().getUserInputSinceInvocation(
            requestContext.editor, requestContext.caretPosition.offset
        )
        val userInput =
            if (caretMovement == CaretMovement.NO_CHANGE) {
                LOG.debug { "Caret position not changed since invocation. Request ID: $requestId" }
                ""
            } else {
                userInputOriginal.trimStart().also {
                    LOG.debug {
                        "Caret position moved forward since invocation. Request ID: $requestId, " +
                            "user input since invocation: $userInputOriginal, " +
                            "user input without leading spaces: $it"
                    }
                }
            }
        val detailContexts = CodeWhispererRecommendationManager.getInstance().buildDetailContext(
            requestContext, userInput, recommendations, requestId
        )
        val recommendationContext = RecommendationContext(detailContexts, userInputOriginal, userInput, visualPosition)
        return buildInvocationContext(requestContext, responseContext, recommendationContext, popup)
    }

    private fun updateStates(
        states: InvocationContext,
        response: ListRecommendationsResponse
    ): InvocationContext {
        val recommendationContext = states.recommendationContext
        val details = recommendationContext.details
        val newDetailContexts = CodeWhispererRecommendationManager.getInstance().buildDetailContext(
            states.requestContext,
            recommendationContext.userInputSinceInvocation,
            response.recommendations(),
            response.responseMetadata().requestId()
        )
        Disposer.dispose(states)

        val updatedStates = states.copy(
            recommendationContext = recommendationContext.copy(details = details + newDetailContexts)
        )
        Disposer.register(states.popup, updatedStates)
        CodeWhispererPopupManager.getInstance().initPopupListener(updatedStates)
        return updatedStates
    }

    private fun checkRecommendationsValidity(states: InvocationContext, showHint: Boolean): Boolean {
        val userInput = states.recommendationContext.userInputSinceInvocation
        val details = states.recommendationContext.details

        // set to true when at least one is not discarded by userInput but discarded by reference filter
        val hasAtLeastOneDiscardedByReferenceFilter = details.any {
            it.recommendation.content().startsWith(userInput) &&
                it.recommendation.hasReferences() &&
                !CodeWhispererSettings.getInstance().isIncludeCodeWithReference()
        }

        // set to true when at least one is not discarded or empty
        val hasAtLeastOneValid = details.any { !it.isDiscarded && it.recommendation.content().isNotEmpty() }

        if (!hasAtLeastOneValid && showHint) {
            if (hasAtLeastOneDiscardedByReferenceFilter) {
                // show popup hint for filter when none is valid and at least one is discarded by reference filter
                showCodeWhispererInfoHint(
                    states.requestContext.editor,
                    message("codewhisperer.popup.reference.filter")
                )
            } else if (states.requestContext.triggerTypeInfo.triggerType == CodewhispererTriggerType.OnDemand) {
                // show popup hint for no recommendation when none is valid and no one is discarded by reference filter
                showCodeWhispererInfoHint(
                    states.requestContext.editor,
                    message("codewhisperer.popup.no_recommendations")
                )
            }
        }
        return hasAtLeastOneValid
    }

    private fun updateCodeWhisperer(states: InvocationContext, recommendationAdded: Boolean) {
        CodeWhispererPopupManager.getInstance().changeStates(states, 0, "", true, recommendationAdded)
    }

    fun getRequestContext(
        triggerTypeInfo: TriggerTypeInfo,
        editor: Editor,
        project: Project,
        psiFile: PsiFile
    ): RequestContext {
        val client = CodeWhispererClientManager.getInstance().getClient()
        val fileContextInfo = getFileContextInfo(editor, psiFile)
        val caretPosition = getCaretPosition(editor)
        return RequestContext(project, editor, triggerTypeInfo, caretPosition, fileContextInfo, client)
    }

    private fun listRecommendations(requestContext: RequestContext): ListRecommendationsIterable =
        requestContext.client.listRecommendationsPaginator(buildCodeWhispererRequest(requestContext.fileContextInfo))

    private fun validateResponse(response: ListRecommendationsResponse): ListRecommendationsResponse {
        // If contentSpans in reference are not consistent with content(recommendations),
        // remove the incorrect references.
        val validatedRecommendations = response.recommendations().map {
            val validReferences = it.hasReferences() && it.references().isNotEmpty() &&
                it.references().none { reference ->
                    val span = reference.recommendationContentSpan()
                    span.start() > span.end() || span.start() < 0 || span.end() > it.content().length
                }
            if (validReferences) {
                it
            } else {
                it.toBuilder().references(DefaultSdkAutoConstructList.getInstance()).build()
            }
        }
        return response.toBuilder().recommendations(validatedRecommendations).build()
    }

    private fun buildCodeWhispererRequest(
        fileContextInfo: FileContextInfo
    ): ListRecommendationsRequest {
        val programmingLanguage = ProgrammingLanguage.builder()
            .languageName(fileContextInfo.programmingLanguage.languageName)
            .build()
        val fileContext = FileContext.builder()
            .leftFileContent(fileContextInfo.caretContext.leftFileContext)
            .rightFileContent(fileContextInfo.caretContext.rightFileContext)
            .filename(fileContextInfo.filename)
            .programmingLanguage(programmingLanguage)
            .build()

        return ListRecommendationsRequest.builder()
            .fileContext(fileContext)
            .build()
    }

    private fun buildInvocationContext(
        requestContext: RequestContext,
        responseContext: ResponseContext,
        recommendationContext: RecommendationContext,
        popup: JBPopup
    ): InvocationContext {
        addPopupChildDisposables(popup, requestContext.editor)

        // Creating a disposable for managing all listeners lifecycle attached to the popup.
        // previously(before pagination) we use popup as the parent disposable.
        // After pagination, listeners need to be updated as states are updated, for the same popup,
        // so disposable chain becomes popup -> disposable -> listeners updates, and disposable gets replaced on every
        // state update.
        val states = InvocationContext(requestContext, responseContext, recommendationContext, popup)
        Disposer.register(popup, states)
        CodeWhispererPopupManager.getInstance().initPopupListener(states)
        return states
    }

    private fun addPopupChildDisposables(popup: JBPopup, editor: Editor) {
        val originalTabExitsBracketsAndQuotes = CodeInsightSettings.getInstance().TAB_EXITS_BRACKETS_AND_QUOTES
        CodeInsightSettings.getInstance().TAB_EXITS_BRACKETS_AND_QUOTES = false
        Disposer.register(popup) {
            CodeInsightSettings.getInstance().TAB_EXITS_BRACKETS_AND_QUOTES = originalTabExitsBracketsAndQuotes
        }
        val originalAutoPopupCompletionLookup = CodeInsightSettings.getInstance().AUTO_POPUP_COMPLETION_LOOKUP
        CodeInsightSettings.getInstance().AUTO_POPUP_COMPLETION_LOOKUP = false
        Disposer.register(popup) {
            CodeInsightSettings.getInstance().AUTO_POPUP_COMPLETION_LOOKUP = originalAutoPopupCompletionLookup
        }
        Disposer.register(popup) {
            CodeWhispererPopupManager.getInstance().reset()
        }
    }

    private fun logServiceInvocation(
        requestId: String,
        requestContext: RequestContext,
        responseContext: ResponseContext,
        recommendations: List<Recommendation>,
        latency: Double?,
        exceptionType: String?
    ) {
        val recommendationLogs = recommendations.map { it.content().trimEnd() }
            .reduceIndexedOrNull { index, acc, recommendation -> "$acc\n[${index + 1}]\n$recommendation" }
        LOG.info {
            "SessionId: ${responseContext.sessionId}, " +
                "RequestId: $requestId, " +
                "Jetbrains IDE: ${ApplicationInfo.getInstance().fullApplicationName}, " +
                "IDE version: ${ApplicationInfo.getInstance().apiVersion}, " +
                "Filename: ${requestContext.fileContextInfo.filename}, " +
                "Left context of current line: ${requestContext.fileContextInfo.caretContext.leftContextOnCurrentLine}, " +
                "Cursor line: ${requestContext.caretPosition.line}, " +
                "Caret offset: ${requestContext.caretPosition.offset}, " +
                (latency?.let { "Latency: $latency, " } ?: "") +
                (exceptionType?.let { "Exception Type: $it, " } ?: "") +
                "Recommendations: \n${recommendationLogs ?: "None"}"
        }
    }

    fun canDoInvocation(editor: Editor, type: CodewhispererTriggerType): Boolean {
        if (type == CodewhispererTriggerType.AutoTrigger && !CodeWhispererExplorerActionManager.getInstance().isAutoEnabled()) {
            LOG.debug { "CodeWhisperer auto-trigger is disabled, not invoking service" }
            return false
        }
        if (type == CodewhispererTriggerType.OnDemand && !CodeWhispererExplorerActionManager.getInstance().isManualEnabled()) {
            LOG.debug { "CodeWhisperer manual-trigger is disabled, not invoking service" }
            return false
        }
        if (CodeWhispererPopupManager.getInstance().hasConflictingPopups(editor)) {
            LOG.debug { "Find other active popup windows before triggering CodeWhisperer, not invoking service" }
            return false
        }
        if (CodeWhispererInvocationStatus.getInstance().isPopupActive()) {
            LOG.debug { "Find an existing CodeWhisperer popup window before triggering CodeWhisperer, not invoking service" }
            return false
        }
        return true
    }

    fun showCodeWhispererInfoHint(editor: Editor, message: String) {
        HintManager.getInstance().showInformationHint(editor, message, HintManager.UNDER)
    }

    fun showCodeWhispererErrorHint(editor: Editor, message: String) {
        HintManager.getInstance().showErrorHint(editor, message, HintManager.UNDER)
    }

    companion object {
        private val LOG = getLogger<CodeWhispererService>()
        val KEY_CODEWHISPERER_METADATA: Key<CodeWhispererMetadata> = Key.create("codewhisperer.metadata")
        fun getInstance(): CodeWhispererService = service()
        const val KET_SESSION_ID = "x-amzn-SessionId"
    }
}

data class RequestContext(
    val project: Project,
    val editor: Editor,
    val triggerTypeInfo: TriggerTypeInfo,
    val caretPosition: CaretPosition,
    val fileContextInfo: FileContextInfo,
    val client: CodeWhispererClient
)

data class ResponseContext(
    val sessionId: String,
    val completionType: CodewhispererCompletionType
)
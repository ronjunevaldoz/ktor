/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.routing

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*

private const val ROUTING_DEFAULT_CAPACITY = 16

/**
 * Represents a context in which routing resolution is being performed
 * @param routing root node for resolution to start at
 * @param call instance of [ApplicationCall] to use during resolution
 */
public class RoutingResolveContext(
    public val routing: Route,
    public val call: ApplicationCall,
    private val tracers: List<(RoutingResolveTrace) -> Unit>
) {
    /**
     * List of path segments parsed out of a [call]
     */
    public val segments: List<String>

    /**
     * Flag showing if path ends with slash
     */
    public val hasTrailingSlash: Boolean = call.request.path().endsWith('/')

    private val trace: RoutingResolveTrace?

    private val resolveResult: ArrayList<RoutingResolveResult.Success> = ArrayList(ROUTING_DEFAULT_CAPACITY)

    private var failedEvaluation: RouteSelectorEvaluation.Failure? = RouteSelectorEvaluation.FailedPath

    init {
        try {
            segments = parse(call.request.path())
            trace = if (tracers.isEmpty()) null else RoutingResolveTrace(call, segments)
        } catch (cause: URLDecodeException) {
            throw BadRequestException("Url decode failed for ${call.request.uri}", cause)
        }
    }

    private fun parse(path: String): List<String> {
        if (path.isEmpty() || path == "/") return emptyList()
        val length = path.length
        var beginSegment = 0
        var nextSegment = 0
        val segmentCount = path.count { it == '/' }
        val segments = ArrayList<String>(segmentCount)
        while (nextSegment < length) {
            nextSegment = path.indexOf('/', beginSegment)
            if (nextSegment == -1) {
                nextSegment = length
            }
            if (nextSegment == beginSegment) {
                // empty path segment, skip it
                beginSegment = nextSegment + 1
                continue
            }
            val segment = path.decodeURLPart(beginSegment, nextSegment)
            segments.add(segment)
            beginSegment = nextSegment + 1
        }
        if (!call.ignoreTrailingSlash && path.endsWith("/")) {
            segments.add("")
        }
        return segments
    }

    /**
     * Executes resolution procedure in this context and returns [RoutingResolveResult]
     */
    public fun resolve(): RoutingResolveResult {
        handleRoute(routing, 0, ArrayList())

        val resolveResult = findBestRoute()

        trace?.registerFinalResult(resolveResult)
        trace?.apply { tracers.forEach { it(this) } }
        return resolveResult
    }

    private fun handleRoute(entry: Route, segmentIndex: Int, trait: ArrayList<RoutingResolveResult.Success>) {
        val childEvaluation = entry.selector.evaluate(this, segmentIndex)

        if (childEvaluation is RouteSelectorEvaluation.Failure) {
            trace?.skip(
                entry,
                segmentIndex,
                RoutingResolveResult.Failure(entry, "Selector didn't match", childEvaluation.failureStatusCode)
            )
            failedEvaluation = max(failedEvaluation, childEvaluation)
            return
        }

        check(childEvaluation is RouteSelectorEvaluation.Success)

        val result = RoutingResolveResult.Success(entry, childEvaluation.parameters, childEvaluation.quality)
        val newIndex = segmentIndex + childEvaluation.segmentIncrement
        trace?.begin(entry, newIndex)

        if (entry.children.isEmpty() && newIndex != segments.size) {
            trace?.skip(
                entry,
                newIndex,
                RoutingResolveResult.Failure(entry, "Not all segments matched", HttpStatusCode.NotFound)
            )

            return
        }

        trait.add(result)

        val hasHandlers = entry.handlers.isNotEmpty()
        if (hasHandlers && newIndex == segments.size && (resolveResult.isEmpty() || isBetterResolve(trait))) {
            resolveResult.clear()
            resolveResult.addAll(trait)
            failedEvaluation = null
        }

        // iterate using indices to avoid creating iterator
        for (childIndex in 0..entry.children.lastIndex) {
            val child = entry.children[childIndex]
            handleRoute(child, newIndex, trait)
        }

        trait.removeLast()

        trace?.finish(entry, newIndex, result)
    }

    private fun findBestRoute(): RoutingResolveResult {
        val finalResolve = resolveResult

        if (finalResolve.isEmpty()) {
            return RoutingResolveResult.Failure(
                routing,
                "No matched subtrees found",
                failedEvaluation?.failureStatusCode ?: HttpStatusCode.NotFound
            )
        }

        val parameters = ParametersBuilder()
        var quality = Double.MAX_VALUE

        for (index in 0..finalResolve.lastIndex) {
            val part = finalResolve[index]
            parameters.appendAll(part.parameters)

            val partQuality = if (part.quality == RouteSelectorEvaluation.qualityTransparent) {
                RouteSelectorEvaluation.qualityConstant
            } else part.quality

            quality = minOf(quality, partQuality)
        }

        return RoutingResolveResult.Success(finalResolve.last().route, parameters.build(), quality)
    }

    private fun isBetterResolve(new: List<RoutingResolveResult.Success>): Boolean {
        var index1 = 0
        var index2 = 0
        val currentResolve = resolveResult

        while (index1 < currentResolve.size && index2 < new.size) {
            val quality1 = currentResolve[index1].quality
            val quality2 = new[index2].quality
            if (quality1 == RouteSelectorEvaluation.qualityTransparent) {
                index1++
                continue
            }

            if (quality2 == RouteSelectorEvaluation.qualityTransparent) {
                index2++
                continue
            }

            if (quality1 != quality2) {
                return quality2 > quality1
            }

            index1++
            index2++
        }

        val firstQuality = currentResolve.count { it.quality != RouteSelectorEvaluation.qualityTransparent }
        val secondQuality = new.count { it.quality != RouteSelectorEvaluation.qualityTransparent }
        return secondQuality > firstQuality
    }

    private fun max(
        first: RouteSelectorEvaluation.Failure?,
        second: RouteSelectorEvaluation.Failure?
    ): RouteSelectorEvaluation.Failure? = when {
        first == null || second == null -> null
        first.quality >= second.quality -> first
        else -> second
    }
}

package com.portfoliohelper.service

import kotlin.math.exp

private fun metricReferenceToMargin(value: Double, metric: DerivedMarginReferenceMetric): Double =
      when (metric) {
        DerivedMarginReferenceMetric.MARGIN -> value
        DerivedMarginReferenceMetric.EQUITY_CUSHION ->
            if (value.isFinite() && value > 0.0) (1.0 / value - 1.0).coerceAtLeast(0.0) else Double.POSITIVE_INFINITY
        DerivedMarginReferenceMetric.MARGIN_COVERAGE ->
            if (value.isFinite() && value > 0.0) 1.0 / value else Double.POSITIVE_INFINITY
      }

internal fun DerivedTargetScaleConfig.withReferenceMetric(metric: DerivedMarginReferenceMetric): DerivedTargetScaleConfig {
    if (metric == DerivedMarginReferenceMetric.MARGIN) return this
    fun ref(value: Double) = metricReferenceToMargin(value, metric)
    return copy(
        referenceLower = ref(referenceLower),
        referenceUpper = ref(referenceUpper),
        stepBaseTarget =
            if (function == DerivedTargetScaleFunction.HYSTERESIS_STEP ||
                function == DerivedTargetScaleFunction.HYSTERESIS_STAIRS_MOMENTUM ||
                (function == DerivedTargetScaleFunction.HYSTERESIS_STAIRS &&
                    hysteresisStairsReferenceMode == HysteresisStairsReferenceMode.RESET_REF)
            ) {
              ref(stepBaseTarget)
            } else {
              stepBaseTarget
            },
        steps = steps.map { it.copy(referenceMargin = ref(it.referenceMargin)) },
    )
  }


internal enum class MarginIntentionType { BUY_LOW, SELL_HIGH }

internal data class MarginIntention(
    val type: MarginIntentionType,
    val targetMargin: Double?,
    val triggerMargin: Double?,
)

internal data class DerivedTargetSignal(
    val targetMargin: Double?,
    val adjustmentPaused: Boolean = false,
    val forceExactTarget: Boolean = false,
)

private data class DerivedTargetStair(val referenceMargin: Double, val targetMargin: Double)

private fun DerivedTargetScaleConfig.descendingStairs(strict: Boolean = false): List<DerivedTargetStair> {
  val normalized =
      steps
          .filter { it.referenceMargin.isFinite() && it.targetMargin.isFinite() }
          .sortedByDescending { it.referenceMargin }
          .map { DerivedTargetStair(it.referenceMargin, it.targetMargin.coerceAtLeast(0.0)) }

  if (!strict) return normalized
  return normalized.fold(emptyList()) { acc, stair ->
    if (acc.lastOrNull()?.referenceMargin?.let { stair.referenceMargin < it } != false) {
      acc + stair
    } else {
      acc
    }
  }
}

private fun DerivedTargetScaleConfig.resetThresholdAbove(highestReference: Double): Double =
    if (stepBaseTarget.isFinite() && stepBaseTarget > highestReference) {
      stepBaseTarget
    } else {
      Double.POSITIVE_INFINITY
    }

internal sealed interface DerivedTargetRuntime {
  val usesPostPriceMarginForTriggers: Boolean
    get() = false

  val momentumLookbackMonths: Int?
    get() = null

  fun initialTarget(baseMargin: Double): Double

  fun target(
      baseMargin: Double,
      referenceMarginIntentions: List<MarginIntention> = emptyList(),
      momentumLookbackMargin: Double? = null,
  ): DerivedTargetSignal

  fun targetReferenceIndex(currentIndex: Int): Int = currentIndex - 1

  companion object {
    fun from(scale: DerivedTargetScaleConfig): DerivedTargetRuntime =
        when (scale.function) {
          DerivedTargetScaleFunction.SIGMOID -> SigmoidDerivedTargetRuntime(scale)
          DerivedTargetScaleFunction.ADAPTIVE_LOW_SIGMOID -> AdaptiveLowSigmoidDerivedTargetRuntime(scale)
          DerivedTargetScaleFunction.LINEAR -> LinearDerivedTargetRuntime(scale)
          DerivedTargetScaleFunction.STEP -> StepDerivedTargetRuntime(scale)
          DerivedTargetScaleFunction.HYSTERESIS_STEP -> HysteresisStepDerivedTargetRuntime(scale)
          DerivedTargetScaleFunction.HYSTERESIS_STAIRS ->
              if (scale.hysteresisStairsReferenceMode == HysteresisStairsReferenceMode.BUY_LOW_INTENTION) {
                HysteresisStairsBuyLowIntentionDerivedTargetRuntime(scale)
              } else if (scale.hysteresisStairsFallMode == HysteresisStairsFallMode.MOMENTUM) {
                HysteresisStairsMomentumDerivedTargetRuntime(scale)
              } else {
                HysteresisStairsDerivedTargetRuntime(scale)
              }
          DerivedTargetScaleFunction.HYSTERESIS_STAIRS_MOMENTUM -> HysteresisStairsMomentumDerivedTargetRuntime(scale)
          DerivedTargetScaleFunction.HYSTERESIS_STAIRS_REF_BL_RESET -> HysteresisStairsRefBuyLowResetDerivedTargetRuntime(scale)
        }
  }
}

private abstract class DerivedTargetRuntimeBase(
    protected val scale: DerivedTargetScaleConfig,
) : DerivedTargetRuntime {
  override fun initialTarget(baseMargin: Double): Double =
      requireNotNull(target(baseMargin).targetMargin)
}

private abstract class InterpolatedDerivedTargetRuntime(
    scale: DerivedTargetScaleConfig,
) : DerivedTargetRuntimeBase(scale) {
  private val configuredRefLow = minOf(scale.referenceLower, scale.referenceUpper)
  private val refHigh = maxOf(scale.referenceLower, scale.referenceUpper)
  private val configuredTargetLow = minOf(scale.targetLower, scale.targetUpper)
  private val targetHigh = maxOf(scale.targetLower, scale.targetUpper)

  protected open fun refLow(baseMargin: Double): Double = configuredRefLow

  protected open fun targetLow(baseMargin: Double): Double = configuredTargetLow

  protected abstract fun shape(normalized: Double): Double

  override fun target(
      baseMargin: Double,
      referenceMarginIntentions: List<MarginIntention>,
      momentumLookbackMargin: Double?,
  ): DerivedTargetSignal {
    val lowRef = refLow(baseMargin)
    val lowTarget = targetLow(baseMargin)
    val refSpan = refHigh - lowRef
    val normalized =
        if (refSpan > 0.0) ((baseMargin - lowRef) / refSpan).coerceIn(0.0, 1.0)
        else 0.5
    return DerivedTargetSignal(lowTarget + shape(normalized) * (targetHigh - lowTarget))
  }
}

private open class SigmoidDerivedTargetRuntime(
    scale: DerivedTargetScaleConfig,
) : InterpolatedDerivedTargetRuntime(scale) {
  override fun shape(normalized: Double): Double {
    val k = scale.sigmoidSteepness.takeIf { it.isFinite() && it > 0.0 } ?: 8.0
    return 1.0 / (1.0 + exp(-k * (normalized - 0.5)))
  }
}

private class AdaptiveLowSigmoidDerivedTargetRuntime(
    scale: DerivedTargetScaleConfig,
) : SigmoidDerivedTargetRuntime(scale) {
  private val configuredRefLow = minOf(scale.referenceLower, scale.referenceUpper)
  private val configuredTargetLow = minOf(scale.targetLower, scale.targetUpper)

  override fun refLow(baseMargin: Double): Double =
      if (baseMargin < configuredRefLow) baseMargin else configuredRefLow

  override fun targetLow(baseMargin: Double): Double =
      if (baseMargin < configuredTargetLow) baseMargin else configuredTargetLow
}

private class LinearDerivedTargetRuntime(
    scale: DerivedTargetScaleConfig,
) : InterpolatedDerivedTargetRuntime(scale) {
  override fun shape(normalized: Double): Double = normalized
}

private class StepDerivedTargetRuntime(
    scale: DerivedTargetScaleConfig,
) : DerivedTargetRuntimeBase(scale) {
  override fun target(
      baseMargin: Double,
      referenceMarginIntentions: List<MarginIntention>,
      momentumLookbackMargin: Double?,
  ): DerivedTargetSignal {
    var target = scale.stepBaseTarget
    for (step in scale.steps.sortedBy { it.referenceMargin }) {
      if (baseMargin >= step.referenceMargin) target = step.targetMargin
    }
    return DerivedTargetSignal(target.coerceAtLeast(0.0))
  }
}

private class HysteresisStepDerivedTargetRuntime(
    scale: DerivedTargetScaleConfig,
) : DerivedTargetRuntimeBase(scale) {
  private enum class Stage { TARGET_HIGH, NO_TARGET, TARGET_LOW }

  override val usesPostPriceMarginForTriggers: Boolean = true

  private var stage = Stage.TARGET_HIGH

  override fun initialTarget(baseMargin: Double): Double = highTarget

  override fun targetReferenceIndex(currentIndex: Int): Int = currentIndex

  override fun target(
      baseMargin: Double,
      referenceMarginIntentions: List<MarginIntention>,
      momentumLookbackMargin: Double?,
  ): DerivedTargetSignal {
    if (stage != Stage.TARGET_HIGH && baseMargin > resetThreshold) {
      stage = Stage.TARGET_HIGH
      return DerivedTargetSignal(highTarget)
    }

    return when (stage) {
      Stage.TARGET_HIGH -> targetFromHighStage(baseMargin)
      Stage.NO_TARGET -> targetFromNoTargetStage(baseMargin)
      Stage.TARGET_LOW -> DerivedTargetSignal(fixedTarget)
    }
  }

  private fun targetFromHighStage(baseMargin: Double): DerivedTargetSignal =
      when {
        baseMargin < exitThreshold -> {
          stage = Stage.TARGET_LOW
          DerivedTargetSignal(fixedTarget)
        }
        baseMargin < enterThreshold -> {
          stage = Stage.NO_TARGET
          noTargetSignal()
        }
        else -> DerivedTargetSignal(highTarget)
      }

  private fun targetFromNoTargetStage(baseMargin: Double): DerivedTargetSignal =
      if (baseMargin < exitThreshold) {
        stage = Stage.TARGET_LOW
        DerivedTargetSignal(fixedTarget)
      } else {
        noTargetSignal()
      }

  private fun noTargetSignal(): DerivedTargetSignal =
      DerivedTargetSignal(targetMargin = null, adjustmentPaused = true)

  private val enterThreshold = maxOf(scale.referenceLower, scale.referenceUpper)
  private val exitThreshold = minOf(scale.referenceLower, scale.referenceUpper)
  private val resetThreshold =
      if (scale.stepBaseTarget.isFinite() && scale.stepBaseTarget > enterThreshold) {
        scale.stepBaseTarget
      } else {
        Double.POSITIVE_INFINITY
      }
  private val highTarget = scale.targetUpper.coerceAtLeast(0.0)
  private val fixedTarget = scale.targetLower.coerceAtLeast(0.0)
}

private class HysteresisStairsDerivedTargetRuntime(
    scale: DerivedTargetScaleConfig,
) : DerivedTargetRuntimeBase(scale) {
  override val usesPostPriceMarginForTriggers: Boolean = true

  private val stairs = scale.descendingStairs()
  private val highestReference = stairs.firstOrNull()?.referenceMargin ?: Double.NEGATIVE_INFINITY
  private val resetThreshold = scale.resetThresholdAbove(highestReference)
  private val highTarget = scale.targetUpper.coerceAtLeast(0.0)
  private var nextStairIndex = 0

  override fun initialTarget(baseMargin: Double): Double = highTarget

  override fun targetReferenceIndex(currentIndex: Int): Int = currentIndex

  override fun target(
      baseMargin: Double,
      referenceMarginIntentions: List<MarginIntention>,
      momentumLookbackMargin: Double?,
  ): DerivedTargetSignal {
    if (nextStairIndex > 0 && baseMargin > resetThreshold) {
      nextStairIndex = 0
      return DerivedTargetSignal(highTarget, forceExactTarget = true)
    }

    val crossedIndex =
        stairs
            .withIndex()
            .drop(nextStairIndex)
            .lastOrNull { (_, stair) -> baseMargin < stair.referenceMargin }
            ?.index

    if (crossedIndex != null) {
      nextStairIndex = crossedIndex + 1
      return DerivedTargetSignal(stairs[crossedIndex].targetMargin, forceExactTarget = true)
    }

    return if (nextStairIndex == 0) {
      DerivedTargetSignal(highTarget)
    } else {
      DerivedTargetSignal(targetMargin = null, adjustmentPaused = true)
    }
  }
}

private class HysteresisStairsBuyLowIntentionDerivedTargetRuntime(
    scale: DerivedTargetScaleConfig,
) : DerivedTargetRuntimeBase(scale) {
  override val usesPostPriceMarginForTriggers: Boolean = true
  override val momentumLookbackMonths: Int? =
      if (scale.hysteresisStairsFallMode == HysteresisStairsFallMode.MOMENTUM) {
        scale.momentumLookbackMonths.coerceAtLeast(1)
      } else {
        null
      }

  private val stairs = scale.descendingStairs(strict = true)
  private var stageIndex = 0
  private var resetTarget: Double? = null
  private var currentTarget: Double? = null
  private var armedStairIndex: Int? = null
  private var armedAfterExistingMomentum = false
  private var previousMomentumConfirmed = false

  override fun initialTarget(baseMargin: Double): Double = baseMargin.coerceAtLeast(0.0)

  override fun targetReferenceIndex(currentIndex: Int): Int = currentIndex

  override fun target(
      baseMargin: Double,
      referenceMarginIntentions: List<MarginIntention>,
      momentumLookbackMargin: Double?,
  ): DerivedTargetSignal {
    val momentumConfirmed =
        momentumLookbackMargin != null &&
            momentumLookbackMargin.isFinite() &&
            baseMargin < momentumLookbackMargin
    fun recordMomentum(signal: DerivedTargetSignal): DerivedTargetSignal {
      previousMomentumConfirmed = momentumConfirmed
      return signal
    }

    val referenceBuyLowIntention =
        referenceMarginIntentions.lastOrNull { it.type == MarginIntentionType.BUY_LOW }
    if (referenceBuyLowIntention != null) {
      armedStairIndex = null
      armedAfterExistingMomentum = false
      val intentionTarget = (referenceBuyLowIntention.targetMargin ?: baseMargin).coerceAtLeast(0.0)
      val intentionStage = stageForTarget(intentionTarget)
      if (intentionStage < stageIndex) {
        val heldTarget = currentTarget ?: currentStageTarget() ?: intentionTarget
        stageIndex = intentionStage
        if (stageIndex == 0) {
          currentTarget = null
          resetTarget = maxOf(resetTarget ?: intentionTarget, intentionTarget)
          return recordMomentum(DerivedTargetSignal(resetTarget))
        }

        resetTarget = null
        currentTarget = maxOf(heldTarget, intentionTarget)
        return recordMomentum(DerivedTargetSignal(currentTarget))
      }

      if (stageIndex == 0) {
        currentTarget = null
        resetTarget = maxOf(resetTarget ?: baseMargin.coerceAtLeast(0.0), intentionTarget)
        return recordMomentum(DerivedTargetSignal(resetTarget))
      }

      if (intentionStage == stageIndex) {
        val heldTarget = currentTarget ?: currentStageTarget() ?: intentionTarget
        val raisedTarget = maxOf(heldTarget, intentionTarget)
        currentTarget = raisedTarget
        return recordMomentum(if (raisedTarget > heldTarget) {
          DerivedTargetSignal(raisedTarget)
        } else {
          DerivedTargetSignal(targetMargin = null, adjustmentPaused = true)
        })
      }

      return recordMomentum(DerivedTargetSignal(targetMargin = null, adjustmentPaused = true))
    }

    val crossedIndex =
        stairs
            .withIndex()
            .drop(stageIndex)
            .lastOrNull { (_, stair) -> baseMargin < stair.referenceMargin }
            ?.index
    if (crossedIndex != null) {
      if (scale.hysteresisStairsFallMode == HysteresisStairsFallMode.MOMENTUM) {
        val currentArmedIndex = armedStairIndex
        if (currentArmedIndex == null || crossedIndex > currentArmedIndex) {
          armedStairIndex = crossedIndex
          armedAfterExistingMomentum = previousMomentumConfirmed
        }
      } else {
        stageIndex = crossedIndex + 1
        resetTarget = null
        currentTarget = stairs[crossedIndex].targetMargin
        return recordMomentum(DerivedTargetSignal(currentTarget, forceExactTarget = true))
      }
    }

    val armedIndex = armedStairIndex
    if (armedIndex != null && momentumConfirmed && !armedAfterExistingMomentum) {
      armedStairIndex = null
      armedAfterExistingMomentum = false
      stageIndex = armedIndex + 1
      resetTarget = null
      currentTarget = stairs[armedIndex].targetMargin
      return recordMomentum(DerivedTargetSignal(currentTarget, forceExactTarget = true))
    }
    if (!momentumConfirmed) armedAfterExistingMomentum = false

    return recordMomentum(if (armedIndex != null) {
      DerivedTargetSignal(targetMargin = null, adjustmentPaused = true)
    } else if (stageIndex == 0) {
      currentTarget = null
      DerivedTargetSignal(resetTarget ?: baseMargin.coerceAtLeast(0.0))
    } else {
      DerivedTargetSignal(targetMargin = null, adjustmentPaused = true)
    })
  }

  private fun currentStageTarget(): Double? =
      if (stageIndex > 0) stairs.getOrNull(stageIndex - 1)?.targetMargin else resetTarget

  private fun stageForTarget(target: Double): Int {
    val crossedIndex = stairs.indexOfLast { target < it.targetMargin }
    return if (crossedIndex < 0) 0 else crossedIndex + 1
  }
}

private class HysteresisStairsMomentumDerivedTargetRuntime(
    scale: DerivedTargetScaleConfig,
) : DerivedTargetRuntimeBase(scale) {
  override val usesPostPriceMarginForTriggers: Boolean = true
  override val momentumLookbackMonths: Int = scale.momentumLookbackMonths.coerceAtLeast(1)

  private val stairs = scale.descendingStairs(strict = true)
  private val highestReference = stairs.firstOrNull()?.referenceMargin ?: Double.NEGATIVE_INFINITY
  private val resetThreshold = scale.resetThresholdAbove(highestReference)
  private val highTarget = scale.targetUpper.coerceAtLeast(0.0)
  private var nextStairIndex = 0
  private var armedStairIndex: Int? = null
  private var armedAfterExistingMomentum = false
  private var previousMomentumConfirmed = false

  override fun initialTarget(baseMargin: Double): Double = highTarget

  override fun targetReferenceIndex(currentIndex: Int): Int = currentIndex

  override fun target(
      baseMargin: Double,
      referenceMarginIntentions: List<MarginIntention>,
      momentumLookbackMargin: Double?,
  ): DerivedTargetSignal {
    val momentumConfirmed =
        momentumLookbackMargin != null &&
            momentumLookbackMargin.isFinite() &&
            baseMargin < momentumLookbackMargin

    if ((nextStairIndex > 0 || armedStairIndex != null) && baseMargin > resetThreshold) {
      nextStairIndex = 0
      armedStairIndex = null
      armedAfterExistingMomentum = false
      previousMomentumConfirmed = momentumConfirmed
      return DerivedTargetSignal(highTarget, forceExactTarget = true)
    }

    val crossedIndex =
        stairs
            .withIndex()
            .drop(nextStairIndex)
            .lastOrNull { (_, stair) -> baseMargin < stair.referenceMargin }
            ?.index
    if (crossedIndex != null) {
      val currentArmedIndex = armedStairIndex
      if (currentArmedIndex == null || crossedIndex > currentArmedIndex) {
        armedStairIndex = crossedIndex
        armedAfterExistingMomentum = previousMomentumConfirmed
      }
    }

    val armedIndex = armedStairIndex
    if (armedIndex != null && momentumConfirmed && !armedAfterExistingMomentum) {
      armedStairIndex = null
      armedAfterExistingMomentum = false
      nextStairIndex = armedIndex + 1
      previousMomentumConfirmed = momentumConfirmed
      return DerivedTargetSignal(stairs[armedIndex].targetMargin, forceExactTarget = true)
    }
    if (!momentumConfirmed) armedAfterExistingMomentum = false
    previousMomentumConfirmed = momentumConfirmed

    return when {
      armedIndex != null -> DerivedTargetSignal(targetMargin = null, adjustmentPaused = true)
      nextStairIndex == 0 -> DerivedTargetSignal(highTarget)
      else -> DerivedTargetSignal(targetMargin = null, adjustmentPaused = true)
    }
  }
}

private class HysteresisStairsRefBuyLowResetDerivedTargetRuntime(
    scale: DerivedTargetScaleConfig,
) : DerivedTargetRuntimeBase(scale) {
  override val usesPostPriceMarginForTriggers: Boolean = true

  private val stairs = scale.descendingStairs()
  private var nextStairIndex = 0

  override fun initialTarget(baseMargin: Double): Double = baseMargin.coerceAtLeast(0.0)

  override fun targetReferenceIndex(currentIndex: Int): Int = currentIndex

  override fun target(
      baseMargin: Double,
      referenceMarginIntentions: List<MarginIntention>,
      momentumLookbackMargin: Double?,
  ): DerivedTargetSignal {
    val referenceBuyLowIntention =
        referenceMarginIntentions.lastOrNull { it.type == MarginIntentionType.BUY_LOW }
    if (nextStairIndex > 0 && referenceBuyLowIntention != null) {
      nextStairIndex = 0
      return DerivedTargetSignal(
          (referenceBuyLowIntention.targetMargin ?: baseMargin).coerceAtLeast(0.0),
          forceExactTarget = true,
      )
    }

    val crossedIndex =
        stairs
            .withIndex()
            .drop(nextStairIndex)
            .lastOrNull { (_, stair) -> baseMargin < stair.referenceMargin }
            ?.index

    if (crossedIndex != null) {
      nextStairIndex = crossedIndex + 1
      return DerivedTargetSignal(stairs[crossedIndex].targetMargin, forceExactTarget = true)
    }

    return if (nextStairIndex == 0) {
      DerivedTargetSignal(baseMargin.coerceAtLeast(0.0))
    } else {
      DerivedTargetSignal(targetMargin = null, adjustmentPaused = true)
    }
  }
}


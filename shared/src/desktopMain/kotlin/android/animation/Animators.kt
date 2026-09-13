package android.animation

/**
 * android.animation stubs. TimeInterpolator MUST live on the host classpath:
 * plugin bytecode checkcasts interpolators against it, and the plugin
 * classloader would otherwise generate a synthetic copy (splitting the type
 * identity between the app loader and the plugin loader).
 */
interface TimeInterpolator {
    fun getInterpolation(input: Float): Float
}

abstract class Animator {
    open fun setDuration(duration: Long): Animator = this
    fun setInterpolator(value: TimeInterpolator?): Animator = this
    fun setTarget(target: Any?): Animator = this
    fun addListener(listener: Any?): Animator = this
    fun start() {}
    fun cancel() {}
    fun isRunning(): Boolean = false
    fun isStarted(): Boolean = false
    fun end() {}
}

abstract class ValueAnimator : Animator() {
    fun setFloatValues(vararg values: Float) {}
    fun setIntValues(vararg values: Int) {}
    fun setObjectValues(vararg values: Any?) {}
    fun addUpdateListener(listener: AnimatorUpdateListener?) {}
    fun setRepeatCount(value: Int) {}
    fun setRepeatMode(value: Int) {}

    interface AnimatorUpdateListener {
        fun onAnimationUpdate(animation: ValueAnimator?)
    }

    companion object {
        @JvmStatic
        fun ofFloat(vararg values: Float): ValueAnimator = object : ValueAnimator() {}

        @JvmStatic
        fun ofInt(vararg values: Int): ValueAnimator = object : ValueAnimator() {}
    }
}

open class ObjectAnimator : ValueAnimator() {
    companion object {
        @JvmStatic
        fun ofFloat(target: Any?, propertyName: String?, vararg values: Float): ObjectAnimator = ObjectAnimator()

        @JvmStatic
        fun ofInt(target: Any?, propertyName: String?, vararg values: Int): ObjectAnimator = ObjectAnimator()

        @JvmStatic
        fun ofObject(target: Any?, propertyName: String?, evaluator: Any?, vararg values: Any?): ObjectAnimator = ObjectAnimator()
    }
}

class AnimatorSet : Animator() {
    fun playTogether(vararg items: Animator?) {}
    fun playSequentially(vararg items: Animator?) {}
    fun play(animator: Animator?): Builder = Builder()
    class Builder {
        fun with(animator: Animator?): Builder = this
        fun before(animator: Animator?): Builder = this
        fun after(animator: Animator?): Builder = this
        fun after(delay: Long): Builder = this
        fun start(): Unit = Unit
    }
}

abstract class AnimatorListenerAdapter {
    fun onAnimationStart(animation: Animator?) {}
    fun onAnimationEnd(animation: Animator?) {}
    fun onAnimationCancel(animation: Animator?) {}
    fun onAnimationRepeat(animation: Animator?) {}
}
